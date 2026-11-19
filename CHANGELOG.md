# Changelog

All notable changes to this module are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - Unreleased

### Added
- Alternative text of images. The mixin `jmix:jahiaAlt` extends `jmix:image` with the i18n
  property `jahia_ai_alt`, and the GraphQL mutation `generateImageAltText(path, language,
  includeSubNodes, overwrite)` fills it with a vision model, for one image or for every image below
  a path. It runs under the caller's session, saves each image on its own, skips an image that
  already has a text unless `overwrite` is set, and reports one result per image.
- A rule adds `jmix:jahiaAlt` to each uploaded image, like Jahia adds `jmix:image`, so the Content
  Editor shows the alternative text field on every new image.
- Alternative text at upload: with `alt.generate.on.upload=true` (the default), the upload rule
  schedules a Jahia background job. The job starts a few seconds after the upload and writes the text
  in every site language, as the uploader.
- Alternative text in Jahia background jobs for the images already in a site: the GraphQL mutation
  `scheduleImageAltText(path, languages, includeSubNodes, overwrite)` schedules a run and returns at
  once. A run works through its images in jobs of about 20 seconds each, as the user who scheduled
  it, so that publications keep their turn on the scheduler thread. Its jobs are stored in the
  database, so a run survives a restart.
- A run waits and goes on when the AI provider is busy or the rate limit of ai-assistant is
  reached: configuration key `alt.retry.delay.seconds` (default 60), doubled on each attempt that
  writes nothing, up to one hour. An image stopped by the rate limit at upload now gets its text later.
- Provenance of the AI alternative text: the mixin `jmix:jahiaAltProvenance` records, in each
  language, the model, the tier, the date, a reviewed flag, and the SHA-256 digests of the text and of
  the picture that the model saw. The Content Editor shows the reviewed flag.
- A replaced image gets a new alternative text when its current text is the AI's own text, which no
  editor changed or marked as reviewed. A text that an editor wrote, changed or reviewed is kept.
- Configuration keys `alt.max.length`, `alt.max.images`, `alt.max.seconds`, `alt.max.tokens`, `alt.temperature` and `alt.prompt`. `llm.temperature` now applies to the tags only.
- The alternative text and the tags of an image use the ai-assistant tier `vision`, whose model
  reads images. The operator chooses that model in ai-assistant, so this module has no tier key
  for images.
- Tags of an image: the Auto Tagging action now shows on images (`jmix:image`), and the model sees
  the picture with the file name and the text of the image. An image with no rendition the model
  accepts is tagged from its text, on `llm.tier`.
- Configuration key `image.max.bytes`, the largest image sent to the model, for the alternative text
  and the tags (default 5 MB).
- Configurable target properties: `alt.property` and `alt.mixin` (default `jahia_ai_alt` on
  `jmix:jahiaAlt`) for the alternative text, `llm.tag.property` and `llm.tag.mixin` (default
  `j:tagList` on `jmix:tagged`) for the tags. Each pair is checked on the node against that exact
  property name; a single-valued tag property receives the tags joined with ", ".
- Configuration key `llm.tier`, the ai-assistant tier of the tags of a content from its text
  (default `lite`).
- Each call names the site of its node. ai-assistant refuses a user without the permission
  `genaiAccess` on that site: the tagging action answers HTTP 403, and the dialog says so. A
  background run of alternative text stops for that site, with one log line, and goes on for the
  images of another site. `generateImageAltText` fails the images of that site, with one call.
- Brand frame: the tags and the alternative text follow the editorial frame of their site, which
  content-governance holds. The module reads it as an optional dependency. The frame is in the language of the text. It holds the
  voice of the brand, the default persona, the writing rules and the glossary terms of that language.
- Unit tests (JUnit 5, Mockito).

### Changed
- The alternative text and its provenance no longer feed the tags of an image.
- A tag call asks ai-assistant for a JSON array of strings, which ai-assistant checks. The
  module no longer reads a comma-separated answer, and the default `llm.user.prompt` holds style
  guidance only.
- The tag dialog also sends the code of the tag language, `tagLocale`, which selects the frame.
- The number, the language and the style of the tags are now the instructions of a tag call, and the
  content is its input. An answer with no JSON array, after the retries of ai-assistant, answers
  HTTP 502.
- `generateImageAltText` stays for its callers and still writes within the request. Without
  `overwrite`, it now replaces the AI's own unreviewed text of a replaced image, and it writes the
  provenance. For a folder, `scheduleImageAltText` needs no `resumeFrom`.
- The module compiles against ai-assistant `0.1.0-SNAPSHOT` and content-governance
  `1.12.0-SNAPSHOT`. The standard Jahia workflows build each pull request and publish `main` to the
  Jahia Nexus.
- **Breaking**: the module calls its models through ai-assistant, which is now a required
  dependency. ai-assistant holds the former genai-connector, and needs Jahia 8.2.3.2. The provider,
  the model and the API key are configured in ai-assistant. The keys `llm.provider` and
  `anthropic.*`, `openai.*`, `deepseek.*` are no longer read.
- The tagging action answers HTTP 503 when ai-assistant has no model for the tier, and HTTP 429
  when the user reached its rate limit.

### Removed
- **Breaking**: the built-in Anthropic, OpenAI and DeepSeek providers and the exported
  `org.jahia.se.modules.contenttags.service.spi` package (`LlmProvider`, `LlmSettings`).

## [1.0.1] - 2026-10-07

### Added
- Dialog control to choose the **number of tags** to generate (default 5).
- Dialog **"Replace existing tags"** checkbox: when checked the generated tags replace the
  current tag list (previous behaviour); when unchecked they are merged into it (deduplicated).
- Configuration key `llm.tag.count.default` (default 5) for the fallback tag count.
- Configuration key `llm.tag.count.max` (default 20, hard-capped at 50) — the enforced ceiling
  on how many tags a request may generate; the requested count is clamped to it server-side.
- Read-only `contentTagsConfig` action so the dialog reads the configured default and maximum
  on open; the "number of tags" input and its label now reflect the configured maximum.

### Changed
- The tag **count and language are now injected authoritatively** by the module on every
  request, so `llm.user.prompt` is style guidance only and must no longer hardcode a number.
  The shipped default prompt was updated accordingly.
- `ContentTagsService.generateTags` now takes a `numberOfTags` argument; the action accepts an
  optional `numberOfTags` request parameter.

## [1.0.0] - 2026-07-10

Initial release. Successor of the `anthropic-tags` module, rebuilt LLM-agnostic
with clean history.

### Added
- Content Editor "Auto Tagging" action (3-dots menu) that generates semantic tags
  for the selected content and fills `jmix:tagged` / `j:tagList`.
- LLM-agnostic provider layer: `LlmProvider` OSGi SPI with Anthropic (Messages API),
  OpenAI and DeepSeek (Chat Completions) implementations, selected via the
  `llm.provider` configuration key. Providers are called over the JDK HTTP client
  with no vendor SDK jars embedded.
- Configurable model, base URL, max tokens, temperature, source-text cap and prompt
  per provider via `org.jahia.se.modules.contenttags.cfg`.
- Action visibility gated with `useNodeChecks` (shown on `jnt:content` / `jnt:page` /
  `jmix:mainResource` and only when the module is installed on the site).
- English and French translations.

### Security
- POST-only render action requiring an authenticated user with `jcr:write_default`;
  node content is read through the caller's JCR session (no system-session escalation).
- CSRF guard whitelist scoped to `*.generateContentTagsAction.do`.
- No API key shipped in the bundle; content text truncated and logged at DEBUG only.
- `jsoup` upgraded to 1.18.3. Security review and scanner output under `findings/`.
