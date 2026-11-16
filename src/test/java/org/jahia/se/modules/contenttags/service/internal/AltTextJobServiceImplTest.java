package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.GenAiAccessDeniedException;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.modules.genai.api.GenAiQuotaExceededException;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ModelTier;
import org.jahia.se.modules.contenttags.service.AltTextResult;
import org.jahia.se.modules.contenttags.service.AltTextRun;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.scheduler.BackgroundJob;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;

import javax.jcr.ItemNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AltTextJobServiceImplTest {

    /** A job the service scheduled, with its delay in seconds. */
    private record Scheduled(JobDetail job, long delaySeconds) {

        JobDataMap data() {
            return job.getJobDataMap();
        }
    }

    private AltTextService altText;
    private GenAiService genAi;
    private final List<Scheduled> scheduled = new ArrayList<>();
    /** The images the batch opened, as "identifier/language". */
    private final List<String> opened = new ArrayList<>();
    private final Set<String> gone = new LinkedHashSet<>();
    /** The site key of an image, by identifier; an image with none has no site. */
    private final Map<String, String> siteOf = new HashMap<>();
    private final AtomicLong clock = new AtomicLong();
    private AltTextJobServiceImpl service;

    @BeforeEach
    void setUp() {
        altText = mock(AltTextService.class);
        genAi = mock(GenAiService.class);
        when(genAi.isAvailable(ModelTier.VISION)).thenReturn(true);
        service = new AltTextJobServiceImpl() {
            @Override
            void scheduleIn(JobDetail job, long delaySeconds) {
                scheduled.add(new Scheduled(job, delaySeconds));
            }

            @Override
            JCRNodeWrapper imageIn(String identifier, String language) throws ItemNotFoundException {
                if (gone.contains(identifier)) {
                    throw new ItemNotFoundException(identifier);
                }
                opened.add(identifier + "/" + language);
                JCRNodeWrapper image = mock(JCRNodeWrapper.class);
                if (siteOf.containsKey(identifier)) {
                    JCRSiteNode site = mock(JCRSiteNode.class);
                    when(site.getPath()).thenReturn("/sites/" + siteOf.get(identifier));
                    when(site.getSiteKey()).thenReturn(siteOf.get(identifier));
                    try {
                        when(image.getResolveSite()).thenReturn(site);
                    } catch (javax.jcr.RepositoryException e) {
                        throw new IllegalStateException(e);
                    }
                }
                return image;
            }
        };
        service.altTextService = altText;
        service.genAi = genAi;
        service.nanoTime = clock::get;
        service.configure(Map.of());
    }

    @Test
    void theDefaultLanguageComesFirst() throws Exception {
        assertEquals(List.of("fr", "de", "en"), AltTextJobServiceImpl.siteLanguages(image(site("fr", "en", "de", "fr"))));
    }

    @Test
    void anImageGetsOneJobAsTheUserOfItsSessionInEverySiteLanguage() throws Exception {
        JCRNodeWrapper image = image(site("fr", "en", "fr"));

        AltTextRun run = service.schedule(image, false, List.of(), false);

        assertEquals(1, scheduled.size());
        Scheduled job = scheduled.get(0);
        assertEquals(AltTextJobServiceImpl.START_DELAY_SECONDS, job.delaySeconds());
        assertEquals(AltTextJob.class, job.job().getJobClass());
        assertTrue(job.job().requestsRecovery());
        assertEquals("/users/editor", job.data().getString(BackgroundJob.JOB_USERKEY));
        assertEquals(List.of("id"), job.data().get(AltTextJobServiceImpl.IDENTIFIERS));
        assertEquals(List.of("fr", "en"), job.data().get(AltTextJobServiceImpl.LANGUAGES));
        assertEquals(new AltTextRun(job.job().getName(), 1, List.of("fr", "en")), run);
    }

    @Test
    void noJobIsScheduledWithoutAVisionModel() throws Exception {
        when(genAi.isAvailable(ModelTier.VISION)).thenReturn(false);

        assertThrows(GenAiNotConfiguredException.class,
                () -> service.schedule(image(site("en", "en")), false, List.of("en"), false));
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aJobDoesAtMostOneBatchInEachLanguageThenSchedulesTheRest() throws Exception {
        when(altText.fillImage(any(), anyBoolean())).thenReturn(result(AltTextResult.Status.GENERATED));
        List<String> images = images(AltTextJobServiceImpl.BATCH_SIZE + 3);

        service.runBatch(data(images, List.of("en", "fr"), true, 2));

        assertEquals(AltTextJobServiceImpl.BATCH_SIZE * 2, opened.size());
        assertEquals(List.of("img0/en", "img0/fr", "img1/en"), opened.subList(0, 3));
        verify(altText, times(AltTextJobServiceImpl.BATCH_SIZE * 2)).fillImage(any(), eq(true));
        assertEquals(1, scheduled.size());
        Scheduled next = scheduled.get(0);
        assertEquals(0, next.delaySeconds());
        assertEquals(images.subList(AltTextJobServiceImpl.BATCH_SIZE, images.size()),
                next.data().get(AltTextJobServiceImpl.IDENTIFIERS));
        assertEquals(0, next.data().getInt(AltTextJobServiceImpl.RETRIES));
        assertTrue(next.data().getBoolean(AltTextJobServiceImpl.OVERWRITE));
        assertEquals("/users/editor", next.data().getString(BackgroundJob.JOB_USERKEY));
        assertEquals("run1", next.data().getString(AltTextJobServiceImpl.RUN));
    }

    @Test
    void aJobMakesNoNewModelCallOnceItsTimeIsUp() throws Exception {
        when(altText.fillImage(any(), anyBoolean())).thenAnswer(invocation -> {
            clock.addAndGet(TimeUnit.SECONDS.toNanos(AltTextJobServiceImpl.JOB_SECONDS / 2 + 1));
            return result(AltTextResult.Status.GENERATED);
        });

        service.runBatch(data(images(5), List.of("en", "fr"), false, 0));

        // The first image always runs, in every language, even past the time of the job.
        assertEquals(List.of("img0/en", "img0/fr"), opened);
        assertEquals(List.of("img1", "img2", "img3", "img4"),
                scheduled.get(0).data().get(AltTextJobServiceImpl.IDENTIFIERS));
    }

    @Test
    void theLastBatchSchedulesNothingAndDropsTheListOfImages() throws Exception {
        when(altText.fillImage(any(), anyBoolean())).thenReturn(result(AltTextResult.Status.SKIPPED));
        JobDataMap data = data(images(3), List.of("en"), false, 0);

        service.runBatch(data);

        assertEquals(3, opened.size());
        assertTrue(scheduled.isEmpty());
        assertFalse(data.containsKey(AltTextJobServiceImpl.IDENTIFIERS));
    }

    @Test
    void aRetryResumesAtTheLanguageThatFailed() throws Exception {
        when(altText.fillImage(any(), anyBoolean()))
                .thenReturn(result(AltTextResult.Status.GENERATED))
                .thenThrow(new GenAiQuotaExceededException("limit"));

        service.runBatch(data(images(2), List.of("en", "fr", "de"), true, 0));

        Scheduled retry = scheduled.get(0);
        assertEquals(List.of("img0", "img1"), retry.data().get(AltTextJobServiceImpl.IDENTIFIERS));
        assertEquals(List.of("fr", "de"), retry.data().get(AltTextJobServiceImpl.FIRST_LANGUAGES));
        assertEquals(List.of("en", "fr", "de"), retry.data().get(AltTextJobServiceImpl.LANGUAGES));

        opened.clear();
        doReturn(result(AltTextResult.Status.GENERATED)).when(altText).fillImage(any(), anyBoolean());
        service.runBatch(retry.data());
        assertEquals(List.of("img0/fr", "img0/de", "img1/en", "img1/fr", "img1/de"), opened);
    }

    @Test
    void anImageThatTheJobCannotFindGetsOneMoreTryAMinuteLater() throws Exception {
        gone.add("img1");
        when(altText.fillImage(any(), anyBoolean())).thenReturn(result(AltTextResult.Status.GENERATED));

        service.runBatch(data(images(3), List.of("en"), false, 0));

        assertEquals(1, scheduled.size());
        Scheduled again = scheduled.get(0);
        assertEquals(AltTextJobServiceImpl.MISSING_DELAY_SECONDS, again.delaySeconds());
        assertEquals(List.of("img1"), again.data().get(AltTextJobServiceImpl.IDENTIFIERS));
        assertTrue(again.data().getBoolean(AltTextJobServiceImpl.MISSING_RETRY));

        service.runBatch(again.data());
        assertEquals(1, scheduled.size());
    }

    @Test
    void theRateLimitSchedulesTheImagesLeftFromTheOneItStopped() throws Exception {
        when(altText.fillImage(any(), anyBoolean()))
                .thenReturn(result(AltTextResult.Status.GENERATED))
                .thenThrow(new GenAiQuotaExceededException("limit"));

        service.runBatch(data(images(4), List.of("en"), false, 3));

        assertEquals(1, scheduled.size());
        Scheduled retry = scheduled.get(0);
        assertEquals(List.of("img1", "img2", "img3"), retry.data().get(AltTextJobServiceImpl.IDENTIFIERS));
        // The job wrote a text before the limit, so the delay starts over.
        assertEquals(1, retry.data().getInt(AltTextJobServiceImpl.RETRIES));
        assertEquals(List.of("en"), retry.data().get(AltTextJobServiceImpl.FIRST_LANGUAGES));
        assertEquals(60, retry.delaySeconds());
    }

    @Test
    void eachAttemptThatWritesNothingDoublesTheDelayUntilTheRunStops() throws Exception {
        service.configure(Map.of("alt.retry.delay.seconds", "30"));
        when(altText.fillImage(any(), anyBoolean())).thenThrow(new GenAiProviderException("busy", true, 503));

        service.runBatch(data(images(2), List.of("en"), false, 2));
        assertEquals(3, scheduled.get(0).data().getInt(AltTextJobServiceImpl.RETRIES));
        assertEquals(120, scheduled.get(0).delaySeconds());

        // The sixth attempt in a row that writes nothing ends the run.
        service.runBatch(data(images(2), List.of("en"), false, AltTextJobServiceImpl.MAX_RETRIES - 1));
        assertEquals(1, scheduled.size());
    }

    @Test
    void theDelayIsCappedAtOneHour() {
        assertEquals(60, AltTextJobServiceImpl.retryDelay(60, 1));
        assertEquals(1920, AltTextJobServiceImpl.retryDelay(60, 6));
        assertEquals(3600, AltTextJobServiceImpl.retryDelay(60, 30));
    }

    @Test
    void aConnectorWithoutModelStopsTheRun() throws Exception {
        when(altText.fillImage(any(), anyBoolean())).thenThrow(new GenAiNotConfiguredException("no vision tier"));

        service.runBatch(data(images(AltTextJobServiceImpl.BATCH_SIZE + 1), List.of("en", "fr"), false, 0));

        assertEquals(List.of("img0/en"), opened);
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aFailingImageDoesNotStopTheBatch() throws Exception {
        when(altText.fillImage(any(), anyBoolean()))
                .thenThrow(new IllegalStateException("broken"))
                .thenReturn(result(AltTextResult.Status.GENERATED));

        service.runBatch(data(images(3), List.of("en"), false, 0));

        assertEquals(List.of("img0/en", "img1/en", "img2/en"), opened);
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void aRefusedSiteStopsTheRunForThatSiteAndTheOtherSitesGoOn() throws Exception {
        siteOf.putAll(Map.of("img0", "a", "img1", "a", "img2", "b"));
        refuseSite("a");

        service.runBatch(data(images(3), List.of("en", "fr"), false, 0));

        // The second image of the refused site takes no model call.
        assertEquals(List.of("img0/en", "img1/en", "img2/en", "img2/fr"), opened);
        verify(altText, times(3)).fillImage(any(), anyBoolean());
        assertTrue(scheduled.isEmpty());
    }

    @Test
    void theNextJobOfTheRunKeepsTheRefusedSites() throws Exception {
        siteOf.putAll(Map.of("img0", "a", "img1", "b", "img2", "a"));
        when(altText.fillImage(any(), anyBoolean())).thenAnswer(invocation -> {
            String site = RequestSite.of(invocation.getArgument(0));
            if ("a".equals(site)) {
                throw new GenAiAccessDeniedException(site, "The permission 'genaiAccess' is required on the site 'a'");
            }
            throw new GenAiQuotaExceededException("limit");
        });

        service.runBatch(data(images(3), List.of("en"), false, 0));

        Scheduled retry = scheduled.get(0);
        assertEquals(List.of("img1", "img2"), retry.data().get(AltTextJobServiceImpl.IDENTIFIERS));
        assertEquals(List.of("a"), retry.data().get(AltTextJobServiceImpl.DENIED_SITES));

        opened.clear();
        doReturn(result(AltTextResult.Status.GENERATED)).when(altText).fillImage(any(), anyBoolean());
        service.runBatch(retry.data());
        assertEquals(List.of("img1/en", "img2/en"), opened);
        verify(altText, times(3)).fillImage(any(), anyBoolean());
    }

    private void refuseSite(String refused) throws Exception {
        when(altText.fillImage(any(), anyBoolean())).thenAnswer(invocation -> {
            String site = RequestSite.of(invocation.getArgument(0));
            if (refused.equals(site)) {
                throw new GenAiAccessDeniedException(site, "The permission 'genaiAccess' is required on the site '"
                        + site + "'");
            }
            return result(AltTextResult.Status.GENERATED);
        });
    }

    private static List<String> images(int count) {
        return IntStream.range(0, count).mapToObj(i -> "img" + i).toList();
    }

    private static JobDataMap data(List<String> identifiers, List<String> languages, boolean overwrite, int retries) {
        return new AltTextJobServiceImpl.Work("run1", "/users/editor", identifiers, languages, languages, overwrite,
                retries, false, List.of()).toJob().getJobDataMap();
    }

    private static AltTextResult result(AltTextResult.Status status) {
        return new AltTextResult("/sites/s/files/a.png", status, "A photo.", null);
    }

    private static JCRSiteNode site(String defaultLanguage, String... languages) {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getLanguages()).thenReturn(new LinkedHashSet<>(List.of(languages)));
        when(site.getDefaultLanguage()).thenReturn(defaultLanguage);
        return site;
    }

    private static JCRNodeWrapper image(JCRSiteNode site) throws Exception {
        JCRNodeWrapper image = mock(JCRNodeWrapper.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class, RETURNS_DEEP_STUBS);
        JahiaUser user = mock(JahiaUser.class);
        when(user.getUserKey()).thenReturn("/users/editor");
        when(session.getUser()).thenReturn(user);
        when(image.getSession()).thenReturn(session);
        when(image.getIdentifier()).thenReturn("id");
        when(image.getPath()).thenReturn("/sites/s/files/a.png");
        when(image.getResolveSite()).thenReturn(site);
        return image;
    }
}
