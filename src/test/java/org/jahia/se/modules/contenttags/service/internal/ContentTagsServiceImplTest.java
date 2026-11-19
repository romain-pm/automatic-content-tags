package org.jahia.se.modules.contenttags.service.internal;

import org.jahia.modules.genai.api.CompletionRequest;
import org.jahia.modules.genai.api.CompletionResult;
import org.jahia.modules.genai.api.GenAiService;
import org.jahia.modules.genai.api.ModelTier;
import org.jahia.modules.genai.api.ResponseFormat;
import org.jahia.modules.genai.api.StopReason;
import org.jahia.modules.genai.api.Usage;
import org.jahia.se.modules.contenttags.frame.BrandFrame;
import org.jahia.se.modules.contenttags.service.TagTarget;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.content.nodetypes.ExtendedNodeType;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.Value;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContentTagsServiceImplTest {

    private ContentTagsServiceImpl service;
    /** What the node type registry answers for the configured property; {@code null} when none is declared. */
    private ExtendedPropertyDefinition definition;
    private TargetProperty resolvedTarget;

    @BeforeEach
    void setUp() {
        service = new ContentTagsServiceImpl() {
            @Override
            ExtendedPropertyDefinition definitionOf(JCRNodeWrapper node, TargetProperty target) {
                resolvedTarget = target;
                return definition;
            }
        };
        service.genAi = mock(GenAiService.class);
        service.configure(Map.of());
    }

    @Test
    void theDefaultTargetIsTheTagListOfJmixTagged() throws Exception {
        definition = definition("jmix:tagged", PropertyType.STRING, true);

        Optional<TagTarget> target = service.tagTarget(mock(JCRNodeWrapper.class));

        assertEquals(new TargetProperty("j:tagList", "jmix:tagged"), resolvedTarget);
        assertEquals(Optional.of(new TagTarget("j:tagList", "jmix:tagged", "jmix:tagged_j:tagList", true)), target);
    }

    @Test
    void aConfiguredTargetGivesTheFieldOfItsDeclaringType() throws Exception {
        service.configure(Map.of("llm.tag.property", "j:keywords", "llm.tag.mixin", "jmix:keywords"));
        definition = definition("jmix:keywords", PropertyType.STRING, true);

        assertEquals(Optional.of(new TagTarget("j:keywords", "jmix:keywords", "jmix:keywords_j:keywords", true)),
                service.tagTarget(mock(JCRNodeWrapper.class)));
    }

    @Test
    void aSingleValuedTargetWithoutMixinIsAccepted() throws Exception {
        service.configure(Map.of("llm.tag.property", "seoKeywords", "llm.tag.mixin", ""));
        definition = definition("jnt:article", PropertyType.STRING, false);

        assertEquals(Optional.of(new TagTarget("seoKeywords", null, "jnt:article_seoKeywords", false)),
                service.tagTarget(mock(JCRNodeWrapper.class)));
    }

    @Test
    void aMissingOrNonTextPropertyIsNoTarget() throws Exception {
        definition = null;
        assertTrue(service.tagTarget(mock(JCRNodeWrapper.class)).isEmpty());

        definition = definition("jmix:tagged", PropertyType.WEAKREFERENCE, true);
        assertTrue(service.tagTarget(mock(JCRNodeWrapper.class)).isEmpty());
    }

    @Test
    void anImageIsTaggedFromItsPictureOnTheVisionTier() throws Exception {
        JCRNodeWrapper image = node("cat.png", "A grey cat");
        when(image.isNodeType("jmix:image")).thenReturn(true);
        ImagePayloadTest.resource(image, "jcr:content", "image/png", new byte[] {1, 2, 3});
        answer("[\"cat\", \"sofa\"]");

        assertEquals(List.of("cat", "sofa"), service.generateTags(image, "French", "fr", 2));

        CompletionRequest request = sent();
        assertEquals(ModelTier.VISION, request.tier());
        assertEquals(1, request.images().size());
        assertEquals("image/png", request.images().get(0).mediaType());
        assertTrue(request.instructions().orElseThrow().startsWith("Generate exactly 2 relevant tags, in French."),
                request.instructions().orElseThrow());
        assertTrue(request.input().startsWith("Tag this image."), request.input());
        assertTrue(request.input().contains("cat.png"), request.input());
        assertTrue(request.input().contains("A grey cat"), "the text of the image goes with it");
    }

    @Test
    void anImageWithNoTextIsStillTaggedFromItsPicture() throws Exception {
        JCRNodeWrapper image = node("cat.png", null);
        when(image.isNodeType("jmix:image")).thenReturn(true);
        ImagePayloadTest.resource(image, "jcr:content", "image/jpeg", new byte[] {1, 2, 3});
        answer("[\"cat\"]");

        assertEquals(List.of("cat"), service.generateTags(image, "English", "en", 1));
        assertEquals(ModelTier.VISION, sent().tier());
    }

    @Test
    void aContentIsTaggedFromItsTextOnTheTierOfLlmTier() throws Exception {
        service.configure(Map.of("llm.tier", "balanced"));
        JCRNodeWrapper article = node("news", "Jahia ships a vision tier");
        answer("[\"jahia\"]");

        assertEquals(List.of("jahia"), service.generateTags(article, "English", "en", 1));

        CompletionRequest request = sent();
        assertEquals(ModelTier.BALANCED, request.tier());
        assertTrue(request.images().isEmpty());
        assertTrue(request.input().contains("Content: Jahia ships a vision tier"), request.input());
    }

    @Test
    void anImageWithNoRenditionTheModelAcceptsIsTaggedFromItsText() throws Exception {
        JCRNodeWrapper image = node("scan.tiff", "An invoice");
        when(image.isNodeType("jmix:image")).thenReturn(true);
        ImagePayloadTest.resource(image, "jcr:content", "image/tiff", new byte[] {1, 2, 3});
        answer("[\"invoice\"]");

        assertEquals(List.of("invoice"), service.generateTags(image, "English", "en", 1));

        assertEquals(ModelTier.LITE, sent().tier());
        assertTrue(sent().images().isEmpty());
    }

    @Test
    void aNodeWithNoTextAndNoPictureCostsNoModelCall() throws Exception {
        assertTrue(service.generateTags(node("empty", null), "English", "en", 5).isEmpty());
        verify(service.genAi, never()).complete(any());
    }

    @Test
    void theAlternativeTextAndItsProvenanceNeverFeedTheTagsOfAnImage() throws Exception {
        service.configure(Map.of("alt.property", "jcr:description"));
        answer("[\"sunset\"]");

        service.generateTags(withAltText(true), "English", "en", 1);

        String input = sent().input();
        assertTrue(input.contains("Sunset over the bay"));
        assertFalse(input.contains("AI wrote"));
        assertFalse(input.contains("mistral"));
    }

    @Test
    void theAlternativeTextPropertyStillFeedsTheTagsOfOtherContent() throws Exception {
        service.configure(Map.of("alt.property", "jcr:description"));
        answer("[\"sunset\"]");

        service.generateTags(withAltText(false), "English", "en", 1);

        String input = sent().input();
        assertTrue(input.contains("AI wrote that"));
        assertFalse(input.contains("AI wrote this"));
    }

    /** A node with a title, an AI alternative text, its provenance, and a description. */
    private static JCRNodeWrapper withAltText(boolean image) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn("sunset.jpg");
        when(node.getPath()).thenReturn("/sites/s/files/sunset.jpg");
        when(node.isNodeType("jmix:image")).thenReturn(image);
        List<Property> properties = List.of(
                i18n("jcr:title", "jnt:file", "Sunset over the bay"),
                i18n("jahia_ai_alt", "jmix:jahiaAlt", "AI wrote this"),
                i18n("jahia_ai_alt_model", "jmix:jahiaAltProvenance", "mistral-medium"),
                i18n("jcr:description", "jmix:description", "AI wrote that"));
        PropertyIterator iterator = mock(PropertyIterator.class);
        when(iterator.hasNext()).thenReturn(true, true, true, true, false);
        when(iterator.nextProperty()).thenReturn(properties.get(0), properties.subList(1, 4).toArray(new Property[0]));
        when(node.getProperties()).thenReturn(iterator);
        return node;
    }

    @Test
    void aCallNamesTheSiteOfTheNodeAndAsksForAJsonArray() throws Exception {
        JCRNodeWrapper article = inSite(node("news", "Jahia ships a vision tier"), "digitall");
        answer("[\"jahia\"]");

        service.generateTags(article, "English", "en", 1);

        CompletionRequest request = sent();
        assertEquals(Optional.of("digitall"), request.site());
        assertEquals(ResponseFormat.JSON, request.responseFormat());
        assertEquals(Optional.of(ContentTagsServiceImpl.TAGS_SCHEMA), request.jsonSchema());
        String instructions = request.instructions().orElseThrow();
        assertTrue(instructions.startsWith("Generate exactly 1 relevant tags, in English."), instructions);
        assertFalse(instructions.contains(ContentTagsServiceImpl.FRAME_FORM), "no frame without content-governance");
    }

    @Test
    void theFrameOfTheSiteInTheTagLanguageIsTheInstructions() throws Exception {
        JCRNodeWrapper article = inSite(node("news", "Jahia ships a vision tier"), "digitall");
        service.frame = mock(BrandFrame.class);
        when(service.frame.instructions("digitall", "fr", "Jahia ships a vision tier")).thenReturn("# Brand frame");
        answer("[\"jahia\"]");

        service.generateTags(article, "French", "fr", 1);

        // The rules of the tags come first, then the frame, then the line that keeps their number and form.
        String instructions = sent().instructions().orElseThrow();
        assertTrue(instructions.startsWith("Generate exactly 1 relevant tags, in French."), instructions);
        assertTrue(instructions.endsWith("\n\n# Brand frame\n\n" + ContentTagsServiceImpl.FRAME_FORM), instructions);
    }

    @Test
    void withNoTagLocaleTheFrameIsInTheLanguageOfTheSession() throws Exception {
        JCRNodeWrapper article = inSite(node("news", "Jahia ships a vision tier"), "digitall");
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getLocale()).thenReturn(Locale.GERMAN);
        when(article.getSession()).thenReturn(session);
        service.frame = mock(BrandFrame.class);
        when(service.frame.instructions(eq("digitall"), eq("de"), any())).thenReturn("");
        answer("[\"jahia\"]");

        service.generateTags(article, "German", null, 1);

        verify(service.frame).instructions(eq("digitall"), eq("de"), any());
        assertFalse(sent().instructions().orElseThrow().contains(ContentTagsServiceImpl.FRAME_FORM),
                "an empty frame adds nothing to the instructions");
    }

    @Test
    void anAnswerThatIsNotAJsonArrayGivesNoTags() {
        assertTrue(TagResponseParser.parse("jahia, cms", 5).isEmpty());
        assertEquals(List.of("jahia", "cms"), TagResponseParser.parse("[\"jahia\", \"cms\", \"jahia\"]", 5));
    }

    /** The node, in the site of key {@code siteKey}. */
    static JCRNodeWrapper inSite(JCRNodeWrapper node, String siteKey) throws Exception {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getPath()).thenReturn("/sites/" + siteKey);
        when(site.getSiteKey()).thenReturn(siteKey);
        when(node.getResolveSite()).thenReturn(site);
        return node;
    }

    @Test
    void theKeysOfTheRemovedProvidersAreDetected() {
        Map<String, Object> properties = Map.of(
                "llm.provider", "anthropic",
                "anthropic.api.key", "secret",
                "openai.model", "gpt",
                "deepseek.base.url", "https://example.com",
                "llm.tier", "lite",
                "llm.tag.count.max", "20");

        assertEquals(List.of("anthropic.api.key", "deepseek.base.url", "llm.provider", "openai.model"),
                ContentTagsServiceImpl.legacyKeys(properties));
    }

    @Test
    void aCurrentConfigurationHasNoLegacyKey() {
        assertTrue(ContentTagsServiceImpl.legacyKeys(Map.of("llm.tier", "lite", "alt.tier", "lite")).isEmpty());
        assertTrue(ContentTagsServiceImpl.legacyKeys(null).isEmpty());
    }

    /** A node named {@code name} whose only internationalized string property holds {@code text}, if any. */
    private static JCRNodeWrapper node(String name, String text) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.getPath()).thenReturn("/sites/s/files/" + name);
        PropertyIterator properties = mock(PropertyIterator.class);
        when(node.getProperties()).thenReturn(properties);
        if (text == null) {
            return node;
        }
        ExtendedPropertyDefinition i18n = mock(ExtendedPropertyDefinition.class);
        when(i18n.isInternationalized()).thenReturn(true);
        when(i18n.getRequiredType()).thenReturn(PropertyType.STRING);
        Value value = mock(Value.class);
        when(value.getString()).thenReturn(text);
        Property property = mock(Property.class);
        when(property.getDefinition()).thenReturn(i18n);
        when(property.getValue()).thenReturn(value);
        when(properties.hasNext()).thenReturn(true, false);
        when(properties.nextProperty()).thenReturn(property);
        return node;
    }

    private static Property i18n(String name, String declaringType, String text) throws Exception {
        ExtendedPropertyDefinition definition = definition(declaringType, PropertyType.STRING, false);
        when(definition.getName()).thenReturn(name);
        when(definition.isInternationalized()).thenReturn(true);
        Value value = mock(Value.class);
        when(value.getString()).thenReturn(text);
        Property property = mock(Property.class);
        when(property.getDefinition()).thenReturn(definition);
        when(property.getValue()).thenReturn(value);
        return property;
    }

    private void answer(String text) {
        when(service.genAi.complete(any())).thenReturn(
                new CompletionResult(text, StopReason.COMPLETE, Usage.UNKNOWN, ModelTier.LITE, "fake", "m"));
    }

    private CompletionRequest sent() {
        ArgumentCaptor<CompletionRequest> request = ArgumentCaptor.forClass(CompletionRequest.class);
        verify(service.genAi).complete(request.capture());
        return request.getValue();
    }

    private static ExtendedPropertyDefinition definition(String declaringType, int type, boolean multiple) {
        ExtendedNodeType declaring = mock(ExtendedNodeType.class);
        when(declaring.getName()).thenReturn(declaringType);
        ExtendedPropertyDefinition result = mock(ExtendedPropertyDefinition.class);
        when(result.getDeclaringNodeType()).thenReturn(declaring);
        when(result.getRequiredType()).thenReturn(type);
        when(result.isMultiple()).thenReturn(multiple);
        return result;
    }
}
