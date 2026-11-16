package org.jahia.se.modules.contenttags.frame;

import org.jahia.pm.modules.editorialframe.api.EditorialFrameService;
import org.jahia.pm.modules.editorialframe.api.FrameContext;
import org.jahia.pm.modules.editorialframe.api.FrameRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EditorialFrameBridgeTest {

    private EditorialFrameService frames;
    private EditorialFrameBridge bridge;

    @BeforeEach
    void setUp() {
        frames = mock(EditorialFrameService.class);
        bridge = new EditorialFrameBridge(frames);
    }

    @Test
    void theFrameOfTheSiteAndTheLanguageIsItsText() {
        when(frames.buildContext(any())).thenReturn(new FrameContext("# Brand frame\n", "abc123", null, List.of(), 4,
                false));

        assertEquals("# Brand frame\n", bridge.instructions("digitall", "fr", "Un chat"));

        ArgumentCaptor<FrameRequest> request = ArgumentCaptor.forClass(FrameRequest.class);
        verify(frames).buildContext(request.capture());
        assertEquals("digitall", request.getValue().siteKey());
        assertEquals("fr", request.getValue().language());
        assertNull(request.getValue().persona(), "the default persona of the site");
        assertEquals(FrameRequest.DEFAULT_MAX_TOKENS, request.getValue().maxTokens());
        assertEquals("Un chat", request.getValue().text());
    }

    @Test
    void aSiteWithNoFrameGivesAnEmptyText() {
        when(frames.buildContext(any())).thenReturn(FrameContext.empty());

        assertEquals("", bridge.instructions("digitall", "fr", null));
    }

    @Test
    void noSiteOrNoLanguageReadsNoFrame() {
        assertEquals("", bridge.instructions(null, "fr", null));
        assertEquals("", bridge.instructions("digitall", null, null));
        verifyNoInteractions(frames);
    }

    @Test
    void aFrameThatCannotBeReadGivesAnEmptyText() {
        when(frames.buildContext(any())).thenThrow(new IllegalStateException("repository down"));

        assertEquals("", bridge.instructions("digitall", "fr", null));
    }
}
