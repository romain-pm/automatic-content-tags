package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.CompletionResult;
import org.jahia.services.content.JCRNodeWrapper;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.HexFormat;

/**
 * Reads and writes the provenance of an AI alternative text, held by the mixin {@value #MIXIN}.
 *
 * <p>Each property is i18n, so each language has its own provenance: the model and the tier that wrote the text,
 * the date, whether an editor reviewed it, the SHA-256 digest of the text, and the SHA-256 digest of the image file
 * it describes. The digest of the text tells the AI's own text from a text an editor changed since. The digest of
 * the file tells whether the image was replaced since.</p>
 */
final class AltTextProvenance {

    static final String MIXIN = "jmix:jahiaAltProvenance";
    static final String REVIEWED = "jahia_ai_alt_reviewed";
    static final String MODEL = "jahia_ai_alt_model";
    static final String TIER = "jahia_ai_alt_tier";
    static final String DATE = "jahia_ai_alt_date";
    static final String DIGEST = "jahia_ai_alt_digest";
    static final String SOURCE = "jahia_ai_alt_source";

    private AltTextProvenance() {
    }

    /**
     * @param image the image, read in the language of the text
     * @param text  the current text of the image in that language
     * @return whether {@code text} is the text the AI wrote, and no editor changed it or marked it as reviewed since
     */
    static boolean isUnreviewedAiText(JCRNodeWrapper image, String text) throws RepositoryException {
        if (!image.isNodeType(MIXIN) || !image.hasProperty(DIGEST)) {
            return false;
        }
        if (image.hasProperty(REVIEWED) && image.getProperty(REVIEWED).getBoolean()) {
            return false;
        }
        return digest(text).equals(image.getProperty(DIGEST).getString());
    }

    /** @return the digest of the image file that the AI text describes, in the session language; {@code null} if none */
    static String source(JCRNodeWrapper image) throws RepositoryException {
        return image.isNodeType(MIXIN) && image.hasProperty(SOURCE) ? image.getProperty(SOURCE).getString() : null;
    }

    /**
     * Marks {@code text} as written by the AI, in the session language, as not reviewed. The caller saves.
     *
     * @param source the digest of the image file the text describes, or {@code null}
     */
    static void write(JCRNodeWrapper image, String text, CompletionResult result, String source)
            throws RepositoryException {
        if (!image.isNodeType(MIXIN)) {
            image.addMixin(MIXIN);
        }
        image.setProperty(MODEL, result.model());
        image.setProperty(TIER, result.tier() == null ? null : result.tier().id());
        image.setProperty(DATE, Calendar.getInstance());
        image.setProperty(REVIEWED, false);
        image.setProperty(DIGEST, digest(text));
        image.setProperty(SOURCE, source);
    }

    /** @return the SHA-256 digest of {@code text}, in hexadecimal */
    static String digest(String text) {
        return HexFormat.of().formatHex(sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** @return the SHA-256 digest of the original file of the image, in hexadecimal; {@code null} when it has none */
    static String fileDigest(Node image) throws RepositoryException, IOException {
        if (!image.hasNode("jcr:content")) {
            return null;
        }
        Node content = image.getNode("jcr:content");
        if (!content.hasProperty("jcr:data")) {
            return null;
        }
        MessageDigest sha256 = sha256();
        Binary binary = content.getProperty("jcr:data").getBinary();
        try (InputStream stream = binary.getStream()) {
            byte[] buffer = new byte[8192];
            for (int read = stream.read(buffer); read >= 0; read = stream.read(buffer)) {
                sha256.update(buffer, 0, read);
            }
        } finally {
            binary.dispose();
        }
        return HexFormat.of().formatHex(sha256.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}
