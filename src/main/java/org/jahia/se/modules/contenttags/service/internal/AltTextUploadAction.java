package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.api.Constants;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.se.modules.contenttags.service.AltTextJobService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.rules.BackgroundAction;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Map;

/**
 * Generates the alternative text of an image after it is uploaded or replaced, when {@code alt.generate.on.upload} is
 * true (the default).
 *
 * <p>The rule "Set alternative text mixin on image update" of {@code META-INF/rules.drl} calls this action on the
 * same trigger as Jahia's {@code jmix:image}. The rule runs inside the save of the upload, so the action only
 * schedules a background job ({@link AltTextJobService}) and returns. The job starts a few seconds later, after the
 * upload's own rules have saved, and writes as the uploader, in each language of the image's site. It keeps a text
 * that an editor wrote, changed or reviewed, and replaces the AI's own text of a former version of the image.</p>
 */
@Component(service = BackgroundAction.class, configurationPid = ConfigValues.PID, immediate = true)
public class AltTextUploadAction implements BackgroundAction {

    /** The name the rule uses to call this action. */
    static final String NAME = "automaticContentTagsAltTextOnUpload";

    private static final Logger LOGGER = LoggerFactory.getLogger(AltTextUploadAction.class);

    // Package-private for the unit tests, which inject a mock.
    @Reference
    AltTextJobService jobs;

    private volatile boolean enabled = true;

    @Activate
    @Modified
    void configure(Map<String, Object> properties) {
        enabled = ConfigValues.of(properties).bool("alt.generate.on.upload", true);
        LOGGER.info("Automatic Content Tags: alternative text on upload {}", enabled ? "enabled" : "disabled");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void executeBackgroundAction(JCRNodeWrapper node) {
        if (!enabled) {
            return;
        }
        try {
            if (!Constants.EDIT_WORKSPACE.equals(node.getSession().getWorkspace().getName())) {
                return;
            }
            if (node.getSession().getUser() == null) {
                LOGGER.debug("No uploader for the image {}: no alternative text on upload", node.getPath());
                return;
            }
            jobs.schedule(node, false, List.of(), false);
        } catch (GenAiNotConfiguredException e) {
            LOGGER.info("No alternative text on upload of {}: {}", node.getPath(), e.getMessage());
        } catch (RepositoryException | RuntimeException e) {
            // The upload itself must never fail because of its alternative text.
            LOGGER.warn("Unable to schedule the alternative text of {}", node.getPath(), e);
        }
    }
}
