package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestSiteTest {

    @Test
    void aNodeOfASiteNamesThatSite() throws Exception {
        assertEquals("digitall", RequestSite.of(node("/sites/digitall/files/cat.png", "digitall")));
        assertEquals("systemsite", RequestSite.of(node("/sites/systemsite/files/logo.png", "systemsite")));
    }

    @Test
    void aNodeOutsideSitesNamesNoSiteThoughJahiaResolvesItToSystemsite() throws Exception {
        assertNull(RequestSite.of(node("/users/editor/files/cat.png", "systemsite")));
        assertNull(RequestSite.of(node("/modules/luxe/1.1.0/files/cat.png", "luxe")));
    }

    private static JCRNodeWrapper node(String path, String resolvedSite) throws Exception {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getSiteKey()).thenReturn(resolvedSite);
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getPath()).thenReturn(path);
        when(node.getResolveSite()).thenReturn(site);
        return node;
    }
}
