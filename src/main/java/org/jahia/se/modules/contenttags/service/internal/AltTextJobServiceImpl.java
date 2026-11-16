package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.api.Constants;
import org.jahia.modules.genai.api.GenAiException;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.modules.genai.api.GenAiQuotaExceededException;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ModelTier;
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

/**
 * Default {@link AltTextJobService} implementation.
 *
 * <p>Each Jahia background job of a run ({@link AltTextJob}) processes one batch of {@value #BATCH_SIZE} images, in
 * every language of the run, then schedules a new job for the images left. A run therefore never holds a scheduler
 * thread for long, and what is left is always stored in the database. The jobs request recovery, so a job that a
 * crash stopped runs again; its images already done are skipped, as {@link AltTextService#fillImage} keeps an AI text
 * that describes the current image file.</p>
 *
 * <p>The rate limit of genai-connector, or a provider still busy after the retries of genai-connector, stops the
 * job: a new job takes the images left after {@code alt.retry.delay.seconds}, a delay that doubles on each
 * attempt that writes nothing, up to one hour. The run stops after {@value #MAX_RETRIES} such attempts in a
 * row.</p>
 *
 * <p>The component is also registered under its own class, so that the job, which Quartz creates from its class
 * name, can reach it.</p>
 */
@Component(service = {AltTextJobService.class, AltTextJobServiceImpl.class}, configurationPid = ConfigValues.PID,
        immediate = true)
public class AltTextJobServiceImpl implements AltTextJobService {

    static final int BATCH_SIZE = 20;
    static final int MAX_RETRIES = 6;
    static final long START_DELAY_SECONDS = 5;
    static final long MAX_DELAY_SECONDS = 3600;

    // The keys of the job data, besides those of BackgroundJob.
    static final String RUN = "run";
    static final String IDENTIFIERS = "identifiers";
    static final String LANGUAGES = "languages";
    static final String OVERWRITE = "overwrite";
    static final String RETRIES = "retries";

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextJobServiceImpl.class);

    // Package-private for the unit tests, which inject mocks.
    @Reference
    AltTextService altTextService;

    @Reference
    SchedulerService schedulerService;

    @Reference
    GenAiService genAi;

    private volatile long retryDelaySeconds = 60;

    @Activate
    @Modified
    void configure(Map<String, Object> properties) {
        retryDelaySeconds = ConfigValues.of(properties).integer("alt.retry.delay.seconds", 60, 5, 3600);
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
        String run = UUID.randomUUID().toString().substring(0, 8);
        JobDetail job = job(run, identifiers, codes, overwrite, 0, user.getUserKey());
        try {
            scheduleIn(job, START_DELAY_SECONDS);
        } catch (SchedulerException e) {
            throw new RepositoryException("Unable to schedule the alternative text of " + node.getPath(), e);
        }
        LOGGER.info("Alternative text run {} scheduled by {}: {} images below {} in {}", run, user.getUsername(),
                identifiers.size(), node.getPath(), codes);
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
     * Processes the first batch of the images of a job, then schedules the rest. {@link AltTextJob} calls it, as the
     * user of the run.
     */
    @SuppressWarnings("unchecked")
    void runBatch(JobDataMap data) {
        String run = data.getString(RUN);
        String userKey = data.getString(BackgroundJob.JOB_USERKEY);
        List<String> identifiers = (List<String>) data.get(IDENTIFIERS);
        List<String> languages = (List<String>) data.get(LANGUAGES);
        boolean overwrite = data.getBoolean(OVERWRITE);
        int retries = data.containsKey(RETRIES) ? data.getInt(RETRIES) : 0;
        List<String> batch = identifiers.subList(0, Math.min(BATCH_SIZE, identifiers.size()));
        Map<AltTextResult.Status, Integer> counts = new EnumMap<>(AltTextResult.Status.class);

        for (int i = 0; i < batch.size(); i++) {
            String identifier = batch.get(i);
            for (String language : languages) {
                try {
                    AltTextResult result = altTextService.fillImage(imageIn(identifier, language), overwrite);
                    counts.merge(result.status(), 1, Integer::sum);
                    LOGGER.debug("Alternative text run {}: {} in {} {}{}", run, result.path(), language,
                            result.status(), result.message() == null ? "" : " (" + result.message() + ")");
                } catch (ItemNotFoundException e) {
                    LOGGER.debug("Alternative text run {}: the image {} is gone", run, identifier);
                    break;
                } catch (GenAiQuotaExceededException | GenAiProviderException e) {
                    int next = counts.containsKey(AltTextResult.Status.GENERATED) ? 1 : retries + 1;
                    log(run, counts, languages);
                    retryLater(run, identifiers.subList(i, identifiers.size()), languages, overwrite, next, userKey, e);
                    return;
                } catch (GenAiNotConfiguredException e) {
                    LOGGER.info("Alternative text run {} stops: genai-connector has no model for it ({}); {} images "
                            + "left", run, e.getMessage(), identifiers.size() - i);
                    return;
                } catch (RepositoryException | RuntimeException e) {
                    counts.merge(AltTextResult.Status.FAILED, 1, Integer::sum);
                    LOGGER.warn("Alternative text run {}: the image {} failed in {}", run, identifier, language, e);
                }
            }
        }
        log(run, counts, languages);
        List<String> left = identifiers.subList(batch.size(), identifiers.size());
        if (!left.isEmpty()) {
            scheduleQuietly(job(run, left, languages, overwrite, 0, userKey), 0);
        }
    }

    private static void log(String run, Map<AltTextResult.Status, Integer> counts, List<String> languages) {
        LOGGER.info("Alternative text run {} in {}: {} generated, {} skipped, {} failed", run, languages,
                counts.getOrDefault(AltTextResult.Status.GENERATED, 0),
                counts.getOrDefault(AltTextResult.Status.SKIPPED, 0),
                counts.getOrDefault(AltTextResult.Status.FAILED, 0));
    }

    private void retryLater(String run, List<String> left, List<String> languages, boolean overwrite, int retries,
                            String userKey, GenAiException cause) {
        if (retries > MAX_RETRIES) {
            LOGGER.warn("Alternative text run {} stops after {} attempts that wrote nothing ({}); {} images left",
                    run, MAX_RETRIES, cause.getMessage(), left.size());
            return;
        }
        long delay = retryDelay(retryDelaySeconds, retries);
        LOGGER.info("Alternative text run {} goes on in {} s ({}); {} images left", run, delay, cause.getMessage(),
                left.size());
        scheduleQuietly(job(run, left, languages, overwrite, retries, userKey), delay);
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
        Scheduler scheduler = schedulerService.getScheduler();
        if (scheduler == null) {
            throw new SchedulerException("The Jahia scheduler is not available");
        }
        job.getJobDataMap().put(BackgroundJob.JOB_STATUS, BackgroundJob.STATUS_ADDED);
        Date start = new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds));
        scheduler.scheduleJob(job, new SimpleTrigger(job.getName() + "_Trigger", job.getGroup(), start));
    }

    /**
     * A job as {@link BackgroundJob#createJahiaJob(String, Class)} makes it, stored in the database, but run as the
     * user of the run rather than the current user, and run again after a crash.
     */
    static JobDetail job(String run, List<String> identifiers, List<String> languages, boolean overwrite, int retries,
                         String userKey) {
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
        data.put(OVERWRITE, overwrite);
        data.put(RETRIES, retries);
        job.setJobDataMap(data);
        return job;
    }

    /** The image in the edit workspace, in a session of the current user. Package-private for the unit tests. */
    JCRNodeWrapper imageIn(String identifier, String language) throws RepositoryException {
        return JCRSessionFactory.getInstance()
                .getCurrentUserSession(Constants.EDIT_WORKSPACE, LanguageCodeConverters.languageCodeToLocale(language))
                .getNodeByIdentifier(identifier);
    }
}
