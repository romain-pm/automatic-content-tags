package org.jahia.se.modules.contenttags.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.se.modules.contenttags.service.AltTextRun;

import java.util.List;

/** The GraphQL view of an {@link AltTextRun}. */
@GraphQLName("AltTextRun")
@GraphQLDescription("A run of alternative text in Jahia background jobs")
public class GqlAltTextRun {

    private final AltTextRun run;

    public GqlAltTextRun(AltTextRun run) {
        this.run = run;
    }

    @GraphQLField
    @GraphQLDescription("The name of the first background job of the run; null when the run found no image")
    public String getJobName() {
        return run.jobName();
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("How many images the run visits")
    public int getImages() {
        return run.images();
    }

    @GraphQLField
    @GraphQLNonNull
    @GraphQLDescription("The language codes of the texts, the default language of the site first")
    public List<String> getLanguages() {
        return run.languages();
    }
}
