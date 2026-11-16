package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.se.modules.contenttags.service.AltTextJobService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.usermanager.JahiaUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AltTextUploadActionTest {

    private AltTextJobService jobs;
    private AltTextUploadAction action;

    @BeforeEach
    void setUp() {
        jobs = mock(AltTextJobService.class);
        action = new AltTextUploadAction();
        action.jobs = jobs;
        action.configure(Map.of());
    }

    @Test
    void anUploadInTheEditWorkspaceSchedulesOneJobInEverySiteLanguageWithoutOverwriting() throws Exception {
        JCRNodeWrapper image = image("default");

        action.executeBackgroundAction(image);

        verify(jobs).schedule(image, false, List.of(), false);
    }

    @Test
    void nothingIsScheduledWhenDisabledInLiveOrWithoutUploader() throws Exception {
        action.configure(Map.of("alt.generate.on.upload", "false"));
        action.executeBackgroundAction(image("default"));
        action.configure(Map.of());
        action.executeBackgroundAction(image("live"));
        JCRNodeWrapper anonymous = image("default");
        when(anonymous.getSession().getUser()).thenReturn(null);
        action.executeBackgroundAction(anonymous);

        verify(jobs, never()).schedule(any(), anyBoolean(), anyList(), anyBoolean());
    }

    @Test
    void aJobThatCannotBeScheduledNeverFailsTheUpload() throws Exception {
        JCRNodeWrapper image = image("default");
        when(jobs.schedule(any(), anyBoolean(), anyList(), anyBoolean()))
                .thenThrow(new RepositoryException("no scheduler"))
                .thenThrow(new GenAiNotConfiguredException("no vision tier"));

        assertDoesNotThrow(() -> action.executeBackgroundAction(image));
        assertDoesNotThrow(() -> action.executeBackgroundAction(image));
    }

    private static JCRNodeWrapper image(String workspace) throws Exception {
        JCRNodeWrapper image = mock(JCRNodeWrapper.class);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class, RETURNS_DEEP_STUBS);
        when(session.getWorkspace().getName()).thenReturn(workspace);
        when(session.getUser()).thenReturn(mock(JahiaUser.class));
        when(image.getSession()).thenReturn(session);
        when(image.getPath()).thenReturn("/sites/s/files/a.png");
        return image;
    }
}
