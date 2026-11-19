package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;

import javax.jcr.RepositoryException;

/**
 * The site of a model call: the key of the site that holds the node, which ai-assistant checks against the
 * permission {@code genaiAccess} of the user, and which selects the editorial frame.
 */
final class RequestSite {

    private static final String SITES = "/sites/";

    private RequestSite() {
    }

    /**
     * @return the key of the site under {@code /sites} that holds {@code node}, such as {@code digitall}, or
     *         {@code null} for a node outside {@code /sites}, such as a file of a user or of a module. Jahia resolves
     *         such a node to {@code systemsite}, which is not the site of the node.
     */
    static String of(JCRNodeWrapper node) throws RepositoryException {
        String path = node.getPath();
        if (path == null || !path.startsWith(SITES)) {
            return null;
        }
        JCRSiteNode site = node.getResolveSite();
        return site == null ? null : site.getSiteKey();
    }
}
