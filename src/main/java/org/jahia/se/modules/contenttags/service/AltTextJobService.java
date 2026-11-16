package org.jahia.se.modules.contenttags.service;

import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.RepositoryException;
import java.util.List;

/**
 * Generates alternative text in Jahia background jobs, with {@link AltTextService#fillImage}.
 *
 * <p>A run lists its images when it is scheduled, then one short job after another works through them. Each
 * job runs as the user of the session of the node passed to {@link #schedule}, so the permissions of that user
 * apply. The jobs are stored in the database, so a run survives a restart. When the AI provider is busy or the rate
 * limit of genai-connector is reached, the run waits and goes on later with the images left.</p>
 */
public interface AltTextJobService {

    /**
     * Schedules a run, which starts a few seconds later.
     *
     * @param node            the image, or the node above the images when {@code includeSubNodes} is true
     * @param includeSubNodes whether to process the images below {@code node}
     * @param languages       the language codes of the texts; empty for every language of the site of the node
     * @param overwrite       whether to replace any existing alternative text; otherwise an image keeps its text,
     *                        unless that text is an AI text that {@link AltTextService#fillImage} would replace
     * @return the run, with no job when it found no image
     * @throws org.jahia.modules.genai.api.GenAiNotConfiguredException when genai-connector has no model for the
     *         tier {@code vision}
     * @throws RepositoryException when the images cannot be listed or the job cannot be scheduled
     */
    AltTextRun schedule(JCRNodeWrapper node, boolean includeSubNodes, List<String> languages, boolean overwrite)
            throws RepositoryException;
}
