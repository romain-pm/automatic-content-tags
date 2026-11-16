package org.jahia.se.modules.contenttags.service;

import java.util.List;

/**
 * A run of alternative text that a background job carries out.
 *
 * @param jobName   the name of the first Jahia background job of the run, or {@code null} when the run has no image
 * @param images    how many images the run visits
 * @param languages the language codes of the texts, the default language of the site first
 */
public record AltTextRun(String jobName, int images, List<String> languages) {

    public AltTextRun {
        languages = List.copyOf(languages);
    }
}
