package org.jahia.se.modules.contenttags.service.internal;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
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
                return mock(JCRNodeWrapper.class);
            }
        };
        service.altTextService = altText;
        service.genAi = genAi;
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
    void aJobDoesOneBatchInEachLanguageThenSchedulesTheRest() throws Exception {
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
    void theLastBatchSchedulesNothing() throws Exception {
        when(altText.fillImage(any(), anyBoolean())).thenReturn(result(AltTextResult.Status.SKIPPED));

        service.runBatch(data(images(3), List.of("en"), false, 0));

        assertEquals(3, opened.size());
        assertTrue(scheduled.isEmpty());
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
        assertEquals(60, retry.delaySeconds());
    }

    @Test
    void eachAttemptThatWritesNothingDoublesTheDelayUntilTheRunStops() throws Exception {
        service.configure(Map.of("alt.retry.delay.seconds", "30"));
        when(altText.fillImage(any(), anyBoolean())).thenThrow(new GenAiProviderException("busy", true, 503));

        service.runBatch(data(images(2), List.of("en"), false, 2));
        assertEquals(3, scheduled.get(0).data().getInt(AltTextJobServiceImpl.RETRIES));
        assertEquals(120, scheduled.get(0).delaySeconds());

        service.runBatch(data(images(2), List.of("en"), false, AltTextJobServiceImpl.MAX_RETRIES));
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
    void aDeletedOrFailingImageDoesNotStopTheBatch() throws Exception {
        gone.add("img0");
        when(altText.fillImage(any(), anyBoolean()))
                .thenThrow(new IllegalStateException("broken"))
                .thenReturn(result(AltTextResult.Status.GENERATED));

        service.runBatch(data(images(3), List.of("en"), false, 0));

        assertEquals(List.of("img1/en", "img2/en"), opened);
        assertTrue(scheduled.isEmpty());
    }

    private static List<String> images(int count) {
        return IntStream.range(0, count).mapToObj(i -> "img" + i).toList();
    }

    private static JobDataMap data(List<String> identifiers, List<String> languages, boolean overwrite, int retries) {
        return AltTextJobServiceImpl.job("run1", identifiers, languages, overwrite, retries, "/users/editor")
                .getJobDataMap();
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
