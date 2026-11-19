package org.jahia.se.modules.contenttags.frame;

/**
 * The editorial frame of a site, as a text for the instructions of a model call: the voice of the brand, its
 * writing rules and its glossary terms in the language of the text.
 *
 * <p>{@link EditorialFrameBridge} provides it when the module content-governance runs. Without that module, no service
 * of this type runs, and every call goes without a frame, as before.</p>
 */
public interface BrandFrame {

    /**
     * @param siteKey  the key of the site of the call, such as {@code digitall}
     * @param language the language of the text that the model writes, such as {@code fr}
     * @param text     the text that the model works on, whose rules and terms come first when the frame is long, or
     *                 {@code null}
     * @return the frame text, or an empty text when the site has no frame or the frame cannot be read
     */
    String instructions(String siteKey, String language, String text);
}
