package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.api.Constants;
import org.jahia.modules.genai.api.GenAiAccessDeniedException;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.modules.genai.api.GenAiQuotaExceededException;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ModelTier;
import org.jahia.registries.ServicesRegistry;
import org.jahia.se.modules.contenttags.service.AltTextJobService;
import org.jahia.se.modules.contenttags.service.AltTextResult;
import org.jahia.se.modules.contenttags.service.AltTextRun;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.scheduler.SchedulerService;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.utils.LanguageCodeConverters;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.ItemNotFoundException;
import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Default {@link AltTextJobService} implementation.
 *
 * <p>Jahia runs its persistent jobs, publications included, on one scheduler thread. So each job of a run
 * ({@link AltTextJob}) makes no new model call after {@value #JOB_SECONDS} seconds, and visits at most
 * {@value #BATCH_SIZE} images. It then schedules a new job for the images left, and a publication requested
 * meanwhile runs between two jobs. The jobs are stored in the database and request recovery, so a run survives a
 * restart. A finished job drops its list of images, so that the job history stays small.</p>
 *
 * <p>The rate limit of genai-connector, or a provider still busy after the retries of genai-connector, stops the
 * job: a new job takes the images left after {@code alt.retry.delay.seconds}, a delay that doubles on each
 * attempt that writes nothing, up to one hour. The run stops after {@value #MAX_RETRIES} such attempts in a row.
 * A new job resumes at the image and the language that failed.</p>
 *
 * <p>An image that a job cannot find gets one more try, a minute later: in a cluster, the processing node that
 * runs the jobs can see an upload of another node a few seconds late.</p>
 *
 * <p>genai-connector refuses a call when the user of the run does not hold the permission {@code genaiAccess} on the
 * site of the image. The run then stops for that site, with one log line, and makes no call for its other images.
 * The images of another site go on.</p>
 *
 * <p>The component is also registered under its own class, so that the job, which Quartz creates from its class
 * name, can reach it.</p>
 */
@Component(service = {AltTextJobService.class, AltTextJobServiceImpl.class}, configurationPid = ConfigValues.PID,
        immediate = true)
public class AltTextJobServiceImpl implements AltTextJobService {

    static final int BATCH_SIZE = 50;
    static final long JOB_SECONDS = 20;
    static final int MAX_RETRIES = 6;
    static final long START_DELAY_SECONDS = 5;
    static final long MISSING_DELAY_SECONDS = 60;
    static final long DEFAULT_RETRY_DELAY_SECONDS = 60;
    static final long MAX_DELAY_SECONDS = 3600;

    // The keys of the job data, besides those of BackgroundJob.
    static final String RUN = "run";
    static final String IDENTIFIERS = "identifiers";
    static final String LANGUAGES = "languages";
    static final String FIRST_LANGUAGES = "firstLanguages";
    static final String OVERWRITE = "overwrite";
    static final String RETRIES = "retries";
    static final String MISSING_RETRY = "missingRetry";
    static final String DENIED_SITES = "deniedSites";

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextJobServiceImpl.class);

    // Package-private for the unit tests, which inject mocks.
    @Reference
    AltTextService altTextService;

    @Reference
    SchedulerService schedulerService;

    @Reference
    GenAiService genAi;

    // Package-private for the unit tests, which move the clock.
    LongSupplier nanoTime = System::nanoTime;

    private volatile long retryDelaySeconds = DEFAULT_RETRY_DELAY_SECONDS;

    @Activate
    @Modified
    void configure(Map<String, Object> properties) {
        retryDelaySeconds = ConfigValues.of(properties).integer("alt.retry.delay.seconds",
                (int) DEFAULT_RETRY_DELAY_SECONDS, 5, 3600);
    }

    @Override
    public AltTextRun schedule(JCRNodeWrapper node, boolean includeSubNodes, List<String> languages,
                               boolean overwrite) throws RepositoryException {
        JahiaUser user = node.getSession().getUser();
        if (user == null) {
            throw new RepositoryException("The session of " + node.getPath() + " has no user to run the job as");
        }
        if (!genAi.isAvailable(ModelTier.VISION)) {
            throw new GenAiNotConfiguredException("genai-connector has no model for the tier " + ModelTier.VISION.id());
        }
        List<String> codes = languages == null || languages.isEmpty() ? siteLanguages(node) : List.copyOf(languages);
        List<String> identifiers = new ArrayList<>();
        if (includeSubNodes) {
            for (JCRNodeWrapper image : AltTextServiceImpl.images(node, true).values()) {
                identifiers.add(image.getIdentifier());
            }
        } else {
            // The upload rule passes a file that may get jmix:image later in the same save; the job checks it.
            identifiers.add(node.getIdentifier());
        }
        if (identifiers.isEmpty() || codes.isEmpty()) {
            return new AltTextRun(null, identifiers.size(), codes);
        }
        Work work = new Work(UUID.randomUUID().toString().substring(0, 8), user.getUserKey(), identifiers, codes,
                codes, overwrite, 0, false, List.of());
        JobDetail job = work.toJob();
        try {
            scheduleIn(job, START_DELAY_SECONDS);
        } catch (SchedulerException e) {
            throw new RepositoryException("Unable to schedule the alternative text of " + node.getPath(), e);
        }
        LOGGER.info("Alternative text run {} scheduled by {}: {} images below {} in {}", work.run(),
                user.getUsername(), identifiers.size(), node.getPath(), codes);
        return new AltTextRun(job.getName(), identifiers.size(), codes);
    }

    /** The site languages, default language first; the session language for a node outside a site. */
    static List<String> siteLanguages(JCRNodeWrapper node) throws RepositoryException {
        List<String> languages = new ArrayList<>();
        JCRSiteNode site = node.getResolveSite();
        if (site != null && site.getLanguages() != null && !site.getLanguages().isEmpty()) {
            String defaultLanguage = site.getDefaultLanguage();
            if (defaultLanguage != null && site.getLanguages().contains(defaultLanguage)) {
                languages.add(defaultLanguage);
            }
            site.getLanguages().stream().sorted().filter(language -> !language.equals(defaultLanguage))
                    .forEach(languages::add);
        } else if (node.getSession().getLocale() != null) {
            languages.add(node.getSession().getLocale().toString());
        }
        return languages;
    }

    /**
     * Processes the images of a job until its time is up, then schedules the rest. {@link AltTextJob} calls it, as
     * the user of the run.
     */
    void runBatch(JobDataMap data) {
        Work work = Work.of(data);
        List<String> identifiers = work.identifiers();
        long deadline = nanoTime.getAsLong() + TimeUnit.SECONDS.toNanos(JOB_SECONDS);
        Map<AltTextResult.Status, Integer> counts = new EnumMap<>(AltTextResult.Status.class);
        List<String> missing = new ArrayList<>();
        int done = 0;
        // The images of a refused site take no model call, so they do not count in the batch.
        int processed = 0;
        try {
            // The first image always runs, so that every job moves the run forward.
            while (done < identifiers.size() && processed < BATCH_SIZE
                    && (done == 0 || nanoTime.getAsLong() - deadline < 0)) {
                String identifier = identifiers.get(done);
                List<String> languages = done == 0 ? work.firstLanguages() : work.languages();
                boolean refused = false;
                for (int k = 0; k < languages.size(); k++) {
                    String language = languages.get(k);
                    try {
                        JCRNodeWrapper image = imageIn(identifier, language);
                        if (!work.deniedSites().isEmpty() && work.deniedSites().contains(RequestSite.of(image))) {
                            refused = true;
                            break;
                        }
                        AltTextResult result = altTextService.fillImage(image, work.overwrite());
                        counts.merge(result.status(), 1, Integer::sum);
                        LOGGER.debug("Alternative text run {}: {} in {} {}{}", work.run(), result.path(), language,
                                result.status(), result.message() == null ? "" : " (" + result.message() + ")");
                    } catch (ItemNotFoundException e) {
                        if (!work.missingRetry()) {
                            missing.add(identifier);
                        }
                        LOGGER.debug("Alternative text run {}: no image {}", work.run(), identifier);
                        break;
                    } catch (GenAiAccessDeniedException e) {
                        work = work.deny(e.site());
                        LOGGER.info("Alternative text run {} stops for the site {}: {}. Its other images get no text "
                                + "in this run.", work.run(), e.site(), e.getMessage());
                        refused = true;
                        break;
                    } catch (GenAiQuotaExceededException | GenAiProviderException e) {
                        int inARow = counts.containsKey(AltTextResult.Status.GENERATED) ? 1 : work.retries() + 1;
                        log(work, counts);
                        retryLater(work.rest(identifiers.subList(done, identifiers.size()),
                                languages.subList(k, languages.size()), inARow), e);
                        scheduleMissing(work, missing);
                        return;
                    } catch (GenAiNotConfiguredException e) {
                        LOGGER.info("Alternative text run {} stops: genai-connector has no model for it ({}); {} "
                                + "images left", work.run(), e.getMessage(), identifiers.size() - done);
                        return;
                    } catch (RepositoryException | RuntimeException e) {
                        counts.merge(AltTextResult.Status.FAILED, 1, Integer::sum);
                        LOGGER.warn("Alternative text run {}: the image {} failed in {}", work.run(), identifier,
                                language, e);
                    }
                }
                done++;
                if (!refused) {
                    processed++;
                }
            }
            log(work, counts);
            if (done < identifiers.size()) {
                scheduleQuietly(work.rest(identifiers.subList(done, identifiers.size()), work.languages(), 0)
                        .toJob(), 0);
            }
            scheduleMissing(work, missing);
        } finally {
            // Quartz keeps the data of a finished job in its history for a day: keep it small.
            data.remove(IDENTIFIERS);
        }
    }

    private void scheduleMissing(Work work, List<String> missing) {
        if (!missing.isEmpty()) {
            scheduleQuietly(new Work(work.run(), work.userKey(), missing, work.languages(), work.languages(),
                    work.overwrite(), 0, true, work.deniedSites()).toJob(), MISSING_DELAY_SECONDS);
        }
    }

    private static void log(Work work, Map<AltTextResult.Status, Integer> counts) {
        LOGGER.info("Alternative text run {} in {}: {} generated, {} skipped, {} failed{}", work.run(),
                work.languages(), counts.getOrDefault(AltTextResult.Status.GENERATED, 0),
                counts.getOrDefault(AltTextResult.Status.SKIPPED, 0),
                counts.getOrDefault(AltTextResult.Status.FAILED, 0),
                work.deniedSites().isEmpty() ? "" : ", no AI access on the sites " + work.deniedSites());
    }

    private void retryLater(Work rest, Exception cause) {
        if (rest.retries() >= MAX_RETRIES) {
            LOGGER.warn("Alternative text run {} stops after {} attempts in a row that wrote nothing ({}); {} images "
                    + "left", rest.run(), MAX_RETRIES, cause.getMessage(), rest.identifiers().size());
            return;
        }
        long delay = retryDelay(retryDelaySeconds, rest.retries());
        LOGGER.info("Alternative text run {} goes on in {} s ({}); {} images left", rest.run(), delay,
                cause.getMessage(), rest.identifiers().size());
        scheduleQuietly(rest.toJob(), delay);
    }

    /**
     * Postpones a job that found no service, such as while genai-connector or this module restarts. {@link AltTextJob}
     * calls it, when no instance of this class is registered.
     */
    static void postpone(JobDataMap data) {
        Work work = Work.of(data);
        Work rest = work.rest(work.identifiers(), work.firstLanguages(), work.retries() + 1);
        if (rest.retries() >= MAX_RETRIES) {
            LOGGER.warn("Alternative text run {} stops: the module service is still not started; {} images left",
                    rest.run(), rest.identifiers().size());
            return;
        }
        long delay = retryDelay(DEFAULT_RETRY_DELAY_SECONDS, rest.retries());
        LOGGER.info("Alternative text run {} goes on in {} s: the module service is not started", rest.run(), delay);
        try {
            scheduleIn(ServicesRegistry.getInstance().getSchedulerService().getScheduler(), rest.toJob(), delay);
        } catch (SchedulerException e) {
            LOGGER.error("Alternative text run {} stops: unable to schedule its next job", rest.run(), e);
        } finally {
            data.remove(IDENTIFIERS);
        }
    }

    /** @return the delay before attempt {@code retries} (1 for the first): the base delay doubled each time, capped */
    static long retryDelay(long baseSeconds, int retries) {
        long delay = baseSeconds;
        for (int i = 1; i < retries && delay < MAX_DELAY_SECONDS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_DELAY_SECONDS);
    }

    private void scheduleQuietly(JobDetail job, long delaySeconds) {
        try {
            scheduleIn(job, delaySeconds);
        } catch (SchedulerException e) {
            LOGGER.error("Alternative text run {} stops: unable to schedule its next job; {} images left",
                    job.getJobDataMap().getString(RUN), ((List<?>) job.getJobDataMap().get(IDENTIFIERS)).size(), e);
        }
    }

    /** Package-private so the unit tests can see the jobs instead of a scheduler. */
    void scheduleIn(JobDetail job, long delaySeconds) throws SchedulerException {
        scheduleIn(schedulerService.getScheduler(), job, delaySeconds);
    }

    private static void scheduleIn(Scheduler scheduler, JobDetail job, long delaySeconds) throws SchedulerException {
        if (scheduler == null) {
            throw new SchedulerException("The Jahia scheduler is not available");
        }
        Date start = new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds));
        scheduler.scheduleJob(job, new SimpleTrigger(job.getName() + "_Trigger", job.getGroup(), start));
    }

    /** The image in the edit workspace, in a session of the current user. Package-private for the unit tests. */
    JCRNodeWrapper imageIn(String identifier, String language) throws RepositoryException {
        return JCRSessionFactory.getInstance()
                .getCurrentUserSession(Constants.EDIT_WORKSPACE, LanguageCodeConverters.languageCodeToLocale(language))
                .getNodeByIdentifier(identifier);
    }

    /**
     * The work of one job, as its data holds it.
     *
     * @param run            the short id of the run, in each log line
     * @param userKey        the user of the run
     * @param identifiers    the images left, in path order
     * @param languages      the languages of the run
     * @param firstLanguages the languages left for the first image, after a job stopped in the middle of it
     * @param overwrite      whether to replace any existing text
     * @param retries        the attempts in a row that wrote nothing
     * @param missingRetry   whether this job tries again the images that an earlier job could not find
     * @param deniedSites    the sites on which genai-connector refused a call of the run
     */
    record Work(String run, String userKey, List<String> identifiers, List<String> languages,
                List<String> firstLanguages, boolean overwrite, int retries, boolean missingRetry,
                List<String> deniedSites) {

        @SuppressWarnings("unchecked")
        static Work of(JobDataMap data) {
            List<String> languages = List.copyOf((List<String>) data.get(LANGUAGES));
            List<String> first = (List<String>) data.get(FIRST_LANGUAGES);
            List<String> denied = (List<String>) data.get(DENIED_SITES);
            return new Work(data.getString(RUN), data.getString(BackgroundJob.JOB_USERKEY),
                    List.copyOf((List<String>) data.get(IDENTIFIERS)), languages,
                    first == null ? languages : List.copyOf(first), data.getBoolean(OVERWRITE),
                    data.containsKey(RETRIES) ? data.getInt(RETRIES) : 0,
                    data.containsKey(MISSING_RETRY) && data.getBoolean(MISSING_RETRY),
                    denied == null ? List.of() : List.copyOf(denied));
        }

        /** The same run, on the images and the languages left. */
        Work rest(List<String> identifiersLeft, List<String> firstLanguagesLeft, int retriesInARow) {
            return new Work(run, userKey, identifiersLeft, languages, firstLanguagesLeft, overwrite, retriesInARow,
                    missingRetry, deniedSites);
        }

        /** The same run, which makes no more call for the images of {@code site}. */
        Work deny(String site) {
            if (site == null || deniedSites.contains(site)) {
                return this;
            }
            List<String> denied = new ArrayList<>(deniedSites);
            denied.add(site);
            return new Work(run, userKey, identifiers, languages, firstLanguages, overwrite, retries, missingRetry,
                    List.copyOf(denied));
        }

        /**
         * A job as {@link BackgroundJob#createJahiaJob(String, Class)} makes it, stored in the database, but run as
         * the user of the run rather than the current user, and run again after a crash.
         */
        JobDetail toJob() {
            String group = BackgroundJob.getGroupName(AltTextJob.class);
            JobDetail job = new JobDetail(group + "-" + UUID.randomUUID(), group, AltTextJob.class, false, true, true);
            job.setDescription("Alternative text of " + identifiers.size()
                    + (identifiers.size() == 1 ? " image" : " images") + " (run " + run + ")");
            JobDataMap data = new JobDataMap();
            data.put(BackgroundJob.JOB_CREATED, new Date());
            data.put(BackgroundJob.JOB_STATUS, BackgroundJob.STATUS_ADDED);
            data.put(BackgroundJob.JOB_USERKEY, userKey);
            data.put(RUN, run);
            data.put(IDENTIFIERS, new ArrayList<>(identifiers));
            data.put(LANGUAGES, new ArrayList<>(languages));
            data.put(FIRST_LANGUAGES, new ArrayList<>(firstLanguages));
            data.put(OVERWRITE, overwrite);
            data.put(RETRIES, retries);
            data.put(MISSING_RETRY, missingRetry);
            data.put(DENIED_SITES, new ArrayList<>(deniedSites));
            job.setJobDataMap(data);
            return job;
        }
    }
}
