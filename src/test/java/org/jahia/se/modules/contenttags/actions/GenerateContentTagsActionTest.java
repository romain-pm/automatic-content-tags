package org.jahia.se.modules.contenttags.actions;

import org.jahia.bin.ActionResult;
import org.jahia.modules.genai.api.GenAiAccessDeniedException;
import org.jahia.modules.genai.api.GenAiInvalidOutputException;
import org.jahia.modules.genai.api.GenAiProviderException;
import org.jahia.se.modules.contenttags.service.ContentTagsService;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateContentTagsActionTest {

    private ContentTagsService contentTagsService;
    private GenerateContentTagsAction action;
    private JCRNodeWrapper node;

    @BeforeEach
    void setUp() throws Exception {
        contentTagsService = mock(ContentTagsService.class);
        action = new GenerateContentTagsAction();
        action.setContentTagsService(contentTagsService);
        node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn("/sites/s/contents/article");
    }

    @Test
    void theRateLimitOfTheProviderAnswers429() throws Exception {
        failWith(new GenAiProviderException("busy", true, 429));

        assertEquals(429, execute());
    }

    @Test
    void anUnavailableProviderAnswers503() throws Exception {
        failWith(new GenAiProviderException("down", true, 502));

        assertEquals(503, execute());
    }

    @Test
    void aPermanentProviderFailureAnswers500() throws Exception {
        failWith(new GenAiProviderException("bad request", false, 400));

        assertEquals(500, execute());
    }

    @Test
    void aSiteWithoutAiAccessAnswers403() throws Exception {
        failWith(new GenAiAccessDeniedException("s", "The permission 'genaiAccess' is required on the site 's'"));

        ActionResult result = action.tags(node, "English", "en", 0);
        assertEquals(403, result.getResultCode());
        assertEquals("genaiAccess", result.getJson().getString("code"));
    }

    @Test
    void anAnswerWithNoJsonArrayAnswers502() throws Exception {
        failWith(new GenAiInvalidOutputException("no JSON array", true));

        assertEquals(502, execute());
    }

    @Test
    void theCodeOfTheTagLanguageReachesTheService() throws Exception {
        when(contentTagsService.generateTags(any(), anyString(), any(), anyInt())).thenReturn(List.of("jahia"));

        assertEquals(200, action.tags(node, "French", "fr", 3).getResultCode());

        verify(contentTagsService).generateTags(node, "French", "fr", 3);
    }

    private void failWith(RuntimeException failure) {
        when(contentTagsService.generateTags(any(), anyString(), any(), anyInt())).thenThrow(failure);
    }

    private int execute() throws Exception {
        return action.tags(node, "English", "en", 0).getResultCode();
    }
}
