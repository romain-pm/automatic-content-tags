# Automatic Content Tags - Jahia Content Editor UI Extension

This module uses AI to write two kinds of metadata in Jahia:

- **Tags**: an "Auto Tagging" action in the Content Editor fills `j:tagList` with tags generated from
  the text of the content. An image is tagged from its picture, with a vision model.
- **Alternative text of images**: a Jahia background job writes the `jahia_ai_alt` property of each
  uploaded image, with a vision model that looks at the picture. A GraphQL mutation schedules the same
  job for one image or for every image below a folder.

The module reaches its models through [genai-connector](https://github.com/Jahia/genai-connector).
It holds no provider, model or API key of its own: the operator configures them once, in
genai-connector, for the whole platform. When the module
[editorial-frame](https://github.com/Jahia/ai-editorial-frame) runs, each call also follows the
editorial frame of its site. The frame holds the voice of the brand, its rules and its glossary.

Compatible with Jahia 8.2 (genai-connector requires 8.2.2). Requires Java 17 to build.

## Requirements

- [genai-connector](https://github.com/Jahia/genai-connector) installed and started, with the tier
  `vision` (genai-connector ADR 0010). Each call that sends an image uses `vision`: the alternative
  text, and the tags of an image. The tags of other content use `llm.tier` (`lite` by default).
- The model of `vision` must accept images. With no `vision.*` key, genai-connector uses its shared
  model for `vision`; the shipped `mistral/mistral-small-latest` accepts images.
- The permission `genaiAccess` on the site, for each user who generates tags or alternative text.
  Each call names the site of its node, and genai-connector refuses a user without that permission
  on that site. genai-connector grants `genaiAccess` to its editorial roles.
- `graphql-dxm-provider`, which every Jahia 8.2 ships.
- Optional: [editorial-frame](https://github.com/Jahia/ai-editorial-frame), for the
  [brand frame](#brand-frame). Without it, the module works as before.

## Features

- **Content Editor integration**: an "Auto Tagging" action in the Content Editor 3-dots menu.
- **AI-powered tagging**: extracts the internationalized text of the selected JCR node (through the
  calling user's session) and asks the model for relevant tags in the language chosen by the editor.
  For an image (`jmix:image`), the model of `vision` also sees the picture, so the tags describe what
  the image shows. For example, a photo named `IMG_2041.jpg` with no title still gets `cat, sofa`.
- **Editor controls**: choose the **number of tags** (default 5, up to the admin-configured
  `llm.tag.count.max`) and whether to **replace** the existing tags or **add to** them.
- **Alternative text of images**: the mixin `jmix:jahiaAlt` extends `jmix:image` with the i18n
  property `jahia_ai_alt`. Background jobs fill it after each upload, and the GraphQL mutation
  `scheduleImageAltText` schedules them for the images already in a site.
- **Provenance of the AI text**: the mixin `jmix:jahiaAltProvenance` records the model, the tier, the
  date and a reviewed flag of each AI text. A replaced image gets a new text, unless an editor wrote,
  changed or reviewed the current one.
- **Configurable targets**: the property and mixin that receive the alternative text and the tags come
  from the configuration (`alt.property`, `alt.mixin`, `llm.tag.property`, `llm.tag.mixin`).
- **One AI configuration**: provider, model and key come from genai-connector. genai-connector also
  applies the access check on the site, the per-user rate limit, retries and the audit log.
- **Brand frame**: with the module editorial-frame, the tags and the alternative text follow the
  voice, the writing rules and the glossary of their site.

## Architecture

```
src/main/java/org/jahia/se/modules/contenttags/
├── actions/                       # POST-only render actions of the tagging dialog (CSRF-whitelisted)
├── frame/                         # The optional bridge to the module editorial-frame
├── graphql/                       # Mutations scheduleImageAltText and generateImageAltText, result types
├── service/ContentTagsService     # Tag generation
├── service/AltTextService         # Alternative text generation
├── service/AltTextJobService      # Runs of alternative text in Jahia background jobs
└── service/internal/              # Implementations, the Quartz job, provenance, response cleaning

src/main/resources/META-INF/definitions.cnd   # jmix:jahiaAlt (extends jmix:image), jmix:jahiaAltProvenance
src/javascript/                                # React 18 UI extension (Module Federation)
```

## Installation

1. Maven resolves genai-connector `0.1.0-SNAPSHOT` and editorial-frame `0.1.0-SNAPSHOT` from the
   Jahia public Nexus, where the `main` branch of each module publishes it.
2. Build the module:
   ```bash
   mvn clean install
   ```
3. Deploy genai-connector, then `target/automatic-content-tags-<version>.jar`, to your Jahia
   instance (module management UI or `digital-factory-data/modules`). Deploy editorial-frame before
   this module to use the brand frame. A module that starts after this one needs a restart of this
   module, because Jahia wires an optional dependency when the module starts.
4. Configure genai-connector (`org.jahia.modules.genai.cfg`): a model for the `lite` tier, and a
   model that reads images for the `vision` tier. For example, `vision.model=mistral/mistral-medium-latest`
   sends the image calls to a larger gateway model. See the genai-connector README.

## Configuration

`org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`. Every key is optional.
The [shipped file](src/main/resources/META-INF/configurations/org.jahia.se.modules.contenttags.cfg)
lists each key with its default and its range, and Jahia installs it when the module starts for the
first time. A change applies without a restart.

The keys fall into two groups:

- `llm.*` keys set the tags: the tier, the target property, the tag count, the prompt and the limits.
- `alt.*` keys set the alternative text: generation at upload, the target property, the length and
  the prompt. They also set the limits of `generateImageAltText` and the retry delay of the background
  jobs.

`image.max.bytes` sets the largest image sent to the model, for both features.

> The number of tags and the tag language come from the dialog on every request and are injected
> authoritatively by the module. Keep `llm.user.prompt` to style guidance only.

**Choosing the target properties.** Each feature writes one property, given by a name and the mixin that
declares it. The module checks the pair on each node, against that exact property name, so a misspelled
name is refused rather than written as a stray property.

- Alternative text: `alt.property` must be a single-valued string. For example, `alt.property=jcr:description`
  with `alt.mixin=jmix:description` writes the text into the image description. An image where the pair is
  not valid is reported `FAILED`, without a model call.
- Tags: the dialog fills the Content Editor field of `llm.tag.property` and activates `llm.tag.mixin`. For
  example, `llm.tag.property=j:keywords` with `llm.tag.mixin=jmix:keywords` fills the keywords instead of the
  tags. A single-valued string receives the tags joined with ", ". When the pair is not valid on the content,
  the dialog says so and does not apply.
- A property name must not contain a hyphen: Jahia stores definitions without quotes and reads `a-b` as two
  properties.

**Upgrading from 1.x**: the keys `llm.provider` and `<provider>.api.key`, `.model` and `.base.url`
are no longer read. Configure the provider and the key in genai-connector instead, and remove them
from this file.

**Choosing the model of the images.** Set it in genai-connector, with `vision.model` or a whole
`vision.*` connection. This module has no tier key for images. The keys `alt.tier` and
`alt.max.image.bytes` of earlier 2.0.0 snapshots are no longer read; `image.max.bytes` replaces the
second.

## Usage

### Tags

1. Open a content item, or an image, in the Content Editor.
2. Open the 3-dots menu and click **Auto Tagging**.
3. Select the tag language, set how many tags you want (default 5), and choose whether to
   **replace existing tags** (checked) or **add to them** (unchecked).
4. Click **Apply**. The tag field is filled with the generated tags; save to persist them.

> Tags are generated from the **saved** content of the node. Save first, then generate tags.

The call asks genai-connector for a JSON array of strings, and genai-connector checks the answer. The
number, the language and the style of the tags are the instructions of the call, and the content is
its input. A user without `genaiAccess` on the site sees "You may not use AI on this site".

For an image, the model receives the original file or a Jahia thumbnail, as for the alternative text
below, with the file name and the saved text of the image. An image with no JPEG, PNG, GIF or WebP
rendition is tagged from its text only, on `llm.tier`.

### Alternative text of images

An uploaded image gets its text in a background job, as [At upload](#at-upload) states. For the images
already in a site, schedule a run on `/modules/graphql` as an authenticated editor:

```graphql
mutation {
  scheduleImageAltText(path: "/sites/digitall/files/images", includeSubNodes: true) {
    jobName images languages
  }
}
```

| Argument | Default | Meaning |
|---|---|---|
| `path` | required | An image (`jmix:image`), or a node above images when `includeSubNodes` is true |
| `languages` | every site language | The language codes of the texts, such as `en` and `fr` |
| `includeSubNodes` | `false` | Also process every image below `path` |
| `overwrite` | `false` | Replace any existing text; otherwise an image keeps its text, as [Which text is kept](#which-text-is-kept) states |

- The mutation lists the images, schedules the run and returns at once. It answers an error when
  genai-connector has no model for the tier `vision`.
- genai-connector refuses each call of a user without `genaiAccess` on the site of the image. The
  run then stops for that site, with one line in the server log, and makes no call for the other
  images of that site. The images of another site go on.
- Jahia background jobs write the texts as the calling user. Jahia runs its persistent jobs,
  publications included, on one thread. So each job makes no new model call after 20 seconds, and a
  new job takes the images left. A publication requested meanwhile runs between two jobs.
- The server log reports each job. For example, a folder of 500 images in English and French takes
  1,000 model calls.
- The jobs are stored in the database, so a run survives a restart. A job that a crash stopped runs
  again. Without `overwrite`, it skips the texts it already wrote. With `overwrite`, it writes them
  again.
- A run can meet the rate limit of genai-connector, or an AI provider that is still busy after the
  retries of genai-connector. The run then waits `alt.retry.delay.seconds` and goes on with the images
  left, from the image and the language that failed. The delay doubles on each attempt that writes
  nothing, up to one hour. The run stops after 6 such attempts in a row.
- A run spends the AI rate limit of its user on the node that runs the jobs: 60 calls in 10 minutes
  by default. For example, a run of 1,000 calls keeps that user at the limit for almost 3 hours.
- An image that a job cannot find gets one more try a minute later. In a cluster, the processing
  node that runs the jobs can see an upload of another node a few seconds late.
- A job that fires while genai-connector restarts waits and tries again. A job that fires while this
  module is stopped is lost, because Jahia runs a job that does nothing in its place.

The mutation `generateImageAltText(path, language, includeSubNodes, overwrite, resumeFrom)` stays for
its existing callers. It writes the texts within the request, in one language, and returns one result
per image:

```graphql
mutation {
  generateImageAltText(path: "/sites/digitall/files/images/cat.png", language: "en") {
    generated skipped failed truncated resumeFrom
    images { path status altText message }
  }
}
```

- A call stops at `alt.max.images` model calls or at `alt.max.seconds`. It also stops at the rate limit
  of genai-connector, or when the AI provider is busy. It then returns `truncated: true` and a
  `resumeFrom` path. Call it again with that `resumeFrom` to continue. The images are visited in path
  order.
- genai-connector can refuse a call for the site of an image. The image then fails with the message
  "You may not use AI on the site", followed by the site key. The other images of that site fail with
  the same message and no call, and the images of another site go on.
- `images` lists the generated and failed images. Ask `images(includeSkipped: true)` to also list
  the skipped ones; `skipped` counts them in both cases.

#### Which text is kept

Each text that the module writes records its provenance in the mixin `jmix:jahiaAltProvenance`, in
the language of the text. The provenance holds the model, the tier, the date and a reviewed flag. It
also holds the SHA-256 digests of the text and of the picture that the model saw. Without
`overwrite`, the module applies these rules:

- An image with no text gets one.
- A text that an editor wrote is kept.
- An AI text that an editor changed is kept, because its digest no longer matches.
- An AI text that an editor marked as reviewed is kept. The Content Editor shows the "Reviewed by an
  editor" checkbox of an image once the AI wrote its text.
- Any other AI text is replaced when the picture changed since the AI wrote it. For example, an
  editor uploads a new photo as `team.jpg` over the old one, and the job writes a text for the new
  photo. A change of `image.max.bytes` can change the picture sent too.
- A text that an earlier 2.0.0 snapshot wrote has no provenance, so it is kept.

#### At upload

- A rule (`META-INF/rules.drl`) adds `jmix:jahiaAlt` to each image when it is uploaded or replaced,
  on the same trigger as Jahia's own `jmix:image`, so the Content Editor shows the field on every
  image. The Content Editor ignores `extends = jmix:image`, because an image's primary type is
  `jnt:file`. An image uploaded before the module gets the mixin when it is replaced, or when the
  module writes its text. The rule always adds `jmix:jahiaAlt`, even when `alt.mixin` names another
  mixin.
- With `alt.generate.on.upload=true` (the default), the same rule schedules a background job. The job
  starts a few seconds after the upload. It writes the text in every language of the image's site, as
  the uploader, and keeps a text as [Which text is kept](#which-text-is-kept) states. The upload
  itself does not wait for the model.

#### The text

- The text is written in the edit workspace, in the configured property: by default the i18n property
  `jahia_ai_alt` of the mixin `jmix:jahiaAlt`. Nothing is published.
- The name `jahia_ai_alt` marks the text as written by AI, and the help of the field in the Content
  Editor says that AI must fill it. A template or a script can therefore treat its value as AI-written.
- The model of the tier `vision` receives the original file when it is a JPEG, PNG, GIF or WebP of
  at most `image.max.bytes`, otherwise the thumbnail Jahia generated for the image. An image with neither
  is reported `FAILED`.
- The prompt also holds the file name and the `jcr:title` of the image, which help the model name the
  subject. An editor writes both, so they can hold instructions to the model. The answer is cleaned to
  one line of plain text and cut at `alt.max.length`, so it cannot add markup to a page.
- The instructions of the call are those of the module, or of `alt.prompt`, then the
  [brand frame](#brand-frame) of the site in the language of the text.
- Read the text back with `property(name: "jahia_ai_alt", language: "en")`, or edit it in the Content
  Editor, where it shows as the "AI alternative text" field of an image.
- The alternative text and its provenance never feed the tags. The tags of an image come from its
  picture, its file name and its other text.

### Brand frame

When the module editorial-frame runs, each call carries the editorial frame of its site, after the
instructions of the module. The frame is in the language of the text that the model writes. For an
alternative text, it is the language of the text, and for tags, the tag language of the dialog.
editorial-frame builds the frame text, within its default budget of 1,500 tokens:

- the voice of the brand, for the group and for the site;
- the default persona of the site, and the names of the others;
- the writing rules of that language;
- the glossary terms translated into that language.

For example, a glossary of `digitall` says to write "jeton d'accès" for "access token" in French. A
French alternative text of a screenshot of the login page then writes "jeton d'accès".

- editorial-frame reads the live workspace, so a change of the frame applies once it is published.
- The frame guides the words and the terms, never the form. A line after the frame keeps the
  alternative text alone, with no note. For the tags, the same line keeps their number, their language
  and their form, and genai-connector checks that the answer is a JSON array.
- A site with no frame, or a frame that cannot be read, gives a call with no frame, as without
  editorial-frame.
- The frame makes each call longer. For example, a long brand voice can add up to 1,500 tokens to
  each image of a run, in each language.

## Security notes

- The tagging actions accept **POST only**, require an authenticated user with `jcr:write_default`
  permission on the node, and read content strictly through the caller's JCR session.
- The mutations refuse the guest user and list only the images that the caller can read. A background
  job runs as the user who scheduled it or uploaded the image. It makes no model call for an image
  that user cannot edit (`jcr:write_default`).
- Each call names the site of its node. genai-connector checks that the user of the call, or of the
  background job, holds `genaiAccess` on that site, before any provider call. A node outside
  `/sites`, such as a file of a user or of a module, names no site, and genai-connector logs a warning
  for it. Jahia resolves such a node to `systemsite`, which the module does not send.
- The CSRF guard whitelist covers only `*.generateContentTagsAction.do` and `*.contentTagsConfig.do`;
  `/modules/graphql` keeps its own protections.
- Content text and image bytes go to the model of the configured genai-connector tier. Content text is
  truncated to `llm.max.source.chars`; the model answer is stored as plain text, without markup.
- The brand frame of the site goes to the same model, in the instructions of the call.

## Development

- Frontend: React 18 (see `src/javascript/AutoTags/`), built with Webpack/Module Federation.
- Backend: Java 17 OSGi Declarative Services; unit tests with JUnit 5 and Mockito.

Build with Java 17 (`JAVA_HOME` must point at a JDK 17). The `frontend-maven-plugin` runs the
Webpack build during `mvn install`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

Licensed under the [MIT License](LICENSE).
