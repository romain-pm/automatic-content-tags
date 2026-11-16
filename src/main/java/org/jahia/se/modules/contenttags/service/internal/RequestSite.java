package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;

import javax.jcr.RepositoryException;

/**
 * The site of a model call: the key of the site that holds the node, which genai-connector checks against the
 * permission {@code genaiAccess} of the user, and which selects the editorial frame.
 */
final class RequestSite {

    private static final String SITES = "/sites/";

    private RequestSite() {
    }

    /**
     * @return the key of the site of {@code node}, such as {@code digitall}, or {@code null} for a node that no site
     *         under {@code /sites} holds, such as a file of a module
     */
    static String of(JCRNodeWrapper node) throws RepositoryException {
        JCRSiteNode site = node.getResolveSite();
        if (site == null || site.getPath() == null || !site.getPath().startsWith(SITES)) {
            return null;
        }
        return site.getSiteKey();
    }
}
