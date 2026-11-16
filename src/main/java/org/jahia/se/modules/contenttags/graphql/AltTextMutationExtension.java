package org.jahia.se.modules.contenttags.graphql;

import graphql.annotations.annotationTypes.GraphQLDefaultValue;
import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.api.Constants;
import org.jahia.modules.genai.api.GenAiNotConfiguredException;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.modules.graphql.provider.dxm.node.GqlJcrWrongInputException;
import org.jahia.modules.graphql.provider.dxm.util.GqlUtils;
import org.jahia.osgi.BundleUtils;
import org.jahia.se.modules.contenttags.service.AltTextJobService;
import org.jahia.se.modules.contenttags.service.AltTextService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.jahia.utils.LanguageCodeConverters;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Locale;

/**
 * The GraphQL entry points of the alternative-text generation. {@code scheduleImageAltText} schedules Jahia
 * background jobs and returns at once. {@code generateImageAltText} writes the texts within the request, with a
 * limit of images and time per call, and stays for its existing callers.
 *
 * <p>Both work in the edit workspace, under the calling user, so they list only the images that user can read and
 * write only where that user can edit. A guest is refused.</p>
 */
@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
public final class AltTextMutationExtension {

    private AltTextMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("scheduleImageAltText")
    @GraphQLDescription("Schedules the alternative text of an image, or of the images below a node, in Jahia "
            + "background jobs, and returns at once. The jobs run as the current user, about 20 seconds each, survive "
            + "a restart, and wait and go on when the AI provider is busy or the AI rate limit is reached. Each text is "
            + "saved in the configured property (by default the i18n property jahia_ai_alt of the mixin jmix:jahiaAlt) "
            + "in the edit workspace, with its provenance in the mixin jmix:jahiaAltProvenance; nothing is published. "
            + "The server log reports each batch")
    public static GqlAltTextRun scheduleImageAltText(
            @GraphQLName("path") @GraphQLNonNull
            @GraphQLDescription("The path of an image (jmix:image) or of a node above images") String path,
            @GraphQLName("languages")
            @GraphQLDescription("The language codes of the texts, such as en and fr. Default: every language of the "
                    + "site") List<String> languages,
            @GraphQLName("includeSubNodes") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Also process the images below the node. Default false") Boolean includeSubNodes,
            @GraphQLName("overwrite") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Replace any existing alternative text. Default false: an image keeps its text, unless "
                    + "that text is an AI text that no editor changed or reviewed, of a former version of the image")
            Boolean overwrite) {
        checkUser();
        boolean subNodes = Boolean.TRUE.equals(includeSubNodes);
        List<String> codes = languages == null ? List.of() : languages;
        try {
            JCRNodeWrapper node = JCRSessionFactory.getInstance().getCurrentUserSession(Constants.EDIT_WORKSPACE)
                    .getNode(path);
            for (String language : codes) {
                checkLanguage(node, language);
            }
            checkImage(node, path, subNodes);
            AltTextJobService jobs = BundleUtils.getOsgiService(AltTextJobService.class, null);
            if (jobs == null) {
                throw new DataFetchingException("The alternative text service is not started");
            }
            return new GqlAltTextRun(jobs.schedule(node, subNodes, codes, Boolean.TRUE.equals(overwrite)));
        } catch (PathNotFoundException e) {
            throw new GqlJcrWrongInputException("No node at " + path + " that the current user can read", e);
        } catch (GenAiNotConfiguredException e) {
            throw new DataFetchingException("Alternative text is not available: genai-connector has no model "
                    + "configured for it");
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
    }

    @GraphQLField
    @GraphQLName("generateImageAltText")
    @GraphQLDescription("Generates the alternative text of an image, or of the images below a folder, with the "
            + "vision model of genai-connector, within the request, and saves it in the configured property (by "
            + "default the i18n property jahia_ai_alt of the mixin jmix:jahiaAlt) in the edit workspace, with its "
            + "provenance. Each image is saved on its own; nothing is published. A call stops at alt.max.images or "
            + "alt.max.seconds and returns resumeFrom: for a folder, scheduleImageAltText needs no resume.")
    public static GqlAltTextReport generateImageAltText(
            @GraphQLName("path") @GraphQLNonNull
            @GraphQLDescription("The path of an image (jmix:image) or of a node above images") String path,
            @GraphQLName("language") @GraphQLNonNull
            @GraphQLDescription("The language code of the text to write, such as en or fr") String language,
            @GraphQLName("includeSubNodes") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Also process the images below the node. Default false") Boolean includeSubNodes,
            @GraphQLName("overwrite") @GraphQLDefaultValue(GqlUtils.SupplierFalse.class)
            @GraphQLDescription("Replace any existing alternative text. Default false: an image keeps its text, unless "
                    + "that text is an AI text that no editor changed or reviewed, of a former version of the image")
            Boolean overwrite,
            @GraphQLName("resumeFrom")
            @GraphQLDescription("The resumeFrom of an earlier report that stopped early, to continue from that image. "
                    + "The images are visited in path order")
            String resumeFrom) {
        checkUser();
        Locale locale = LanguageCodeConverters.languageCodeToLocale(language);
        if (locale == null || language.isBlank()) {
            throw new GqlJcrWrongInputException("Unknown language: " + language);
        }
        boolean subNodes = Boolean.TRUE.equals(includeSubNodes);
        try {
            JCRSessionWrapper session = JCRSessionFactory.getInstance()
                    .getCurrentUserSession(Constants.EDIT_WORKSPACE, locale);
            JCRNodeWrapper node = session.getNode(path);
            checkLanguage(node, language);
            checkImage(node, path, subNodes);
            return new GqlAltTextReport(service().fillAltText(node, subNodes, Boolean.TRUE.equals(overwrite), resumeFrom));
        } catch (PathNotFoundException e) {
            throw new GqlJcrWrongInputException("No node at " + path + " that the current user can read", e);
        } catch (GenAiNotConfiguredException e) {
            throw new DataFetchingException("Alternative text is not available: genai-connector has no model "
                    + "configured for it");
        } catch (RepositoryException e) {
            throw new DataFetchingException(e);
        }
    }

    private static void checkUser() {
        JahiaUser user = JCRSessionFactory.getInstance().getCurrentUser();
        if (user == null || JahiaUserManagerService.isGuest(user)) {
            throw new DataFetchingException("Generating alternative text requires an authenticated user");
        }
    }

    private static void checkImage(JCRNodeWrapper node, String path, boolean subNodes) throws RepositoryException {
        if (!subNodes && !node.isNodeType("jmix:image")) {
            throw new GqlJcrWrongInputException(path + " is not an image (jmix:image); set includeSubNodes "
                    + "to true to process the images below it");
        }
    }

    /** A language must be known, and a site only gets text in one of its own languages. */
    private static void checkLanguage(JCRNodeWrapper node, String language) throws RepositoryException {
        if (language == null || language.isBlank() || LanguageCodeConverters.languageCodeToLocale(language) == null) {
            throw new GqlJcrWrongInputException("Unknown language: " + language);
        }
        JCRSiteNode site = node.getResolveSite();
        if (site != null && site.getLanguages() != null && !site.getLanguages().isEmpty()
                && !site.getLanguages().contains(language)) {
            throw new GqlJcrWrongInputException("The site " + site.getSiteKey() + " has no language " + language);
        }
    }

    private static AltTextService service() {
        AltTextService service = BundleUtils.getOsgiService(AltTextService.class, null);
        if (service == null) {
            throw new DataFetchingException("The alternative text service is not started");
        }
        return service;
    }
}
