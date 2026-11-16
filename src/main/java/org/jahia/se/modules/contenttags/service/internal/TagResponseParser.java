package org.jahia.se.modules.contenttags.service.internal;

import org.json.JSONArray;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the tags out of the answer of a tag call.
 *
 * <p>The call sends {@link ContentTagsServiceImpl#TAGS_SCHEMA}, so genai-connector returns one JSON array, with no
 * Markdown fence and no prose around it, or throws. Only the array is read.</p>
 */
final class TagResponseParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(TagResponseParser.class);
    private static final int MAX_TAG_LENGTH = 64;

    private TagResponseParser() {
    }

    /**
     * @param json    the text of the answer, a JSON array
     * @param maxTags the maximum number of tags to keep (the model may over-generate)
     * @return the tags, deduplicated, length-capped and limited to {@code maxTags}; empty when the text is not a
     *         JSON array
     */
    static List<String> parse(String json, int maxTags) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JSONArray array;
        try {
            array = new JSONArray(json.trim());
        } catch (JSONException e) {
            LOGGER.warn("The answer of a tag call is not a JSON array, so it gives no tags");
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            addTag(tags, array.optString(i, ""), maxTags);
        }
        return tags;
    }

    private static void addTag(List<String> tags, String candidate, int maxTags) {
        String tag = candidate == null ? "" : candidate.trim();
        if (tag.isEmpty() || tags.contains(tag) || tags.size() >= maxTags) {
            return;
        }
        if (tag.length() > MAX_TAG_LENGTH) {
            tag = tag.substring(0, MAX_TAG_LENGTH).trim();
        }
        tags.add(tag);
    }
}
