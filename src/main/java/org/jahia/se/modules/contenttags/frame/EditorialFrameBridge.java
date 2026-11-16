package org.jahia.se.modules.contenttags.frame;

import org.jahia.pm.modules.editorialframe.api.EditorialFrameService;
import org.jahia.pm.modules.editorialframe.api.FrameContext;
import org.jahia.pm.modules.editorialframe.api.FrameRequest;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bridge to the module editorial-frame, and the only class that reads its API. Its reference is mandatory, so
 * it starts only when that module runs. The alternative text and the tags reference {@link BrandFrame} as optional.
 *
 * <p>It asks for the default persona and the default token budget of editorial-frame. A frame that cannot be read
 * gives an empty text: the call then goes without a frame, and never fails because of it.</p>
 */
@Component(service = BrandFrame.class, immediate = true)
public class EditorialFrameBridge implements BrandFrame {

    private static final Logger LOGGER = LoggerFactory.getLogger(EditorialFrameBridge.class);

    @Reference
    private EditorialFrameService frames;

    public EditorialFrameBridge() {
        // Used by OSGi.
    }

    EditorialFrameBridge(EditorialFrameService frames) {
        this.frames = frames;
    }

    @Override
    public String instructions(String siteKey, String language, String text) {
        if (siteKey == null || language == null) {
            return "";
        }
        try {
            FrameContext context = frames.buildContext(new FrameRequest(siteKey, language, null,
                    FrameRequest.DEFAULT_MAX_TOKENS, text));
            if (context == null || context.isEmpty()) {
                return "";
            }
            LOGGER.debug("Editorial frame {} of the site {} in {}: about {} tokens{}", context.fingerprint(), siteKey,
                    language, context.estimatedTokens(), context.truncated() ? ", cut to the budget" : "");
            return context.text();
        } catch (RuntimeException e) {
            LOGGER.warn("The editorial frame of the site {} in {} cannot be read, so the call goes without it: {}",
                    siteKey, language, e.getMessage());
            return "";
        }
    }
}
