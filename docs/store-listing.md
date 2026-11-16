# Jahia Store listing — Automatic Content Tags

Source copy for the store.jahia.com listing. Keep in sync with the module version.

- **Module name:** Automatic Content Tags
- **Module id:** automatic-content-tags
- **Version:** 2.0.0
- **Compatibility:** Jahia 8.2
- **License:** MIT
- **Author:** Jahia Solutions Group
- **Source:** https://github.com/Jahia/automatic-content-tags
- **Suggested category:** Editor tools / Productivity / AI

---

## Short description (one line)

Generate content tags and image alternative text with AI, through Jahia's genai-connector.

## Description

Automatic Content Tags adds an **Auto Tagging** action to the Jahia Content Editor. In one click it reads the text of the content you are editing, sends it to a large language model, and fills the content's tag field with relevant, on-topic tags — in the language you choose. On an image, a vision model looks at the picture, so the tags describe what it shows.

It also writes the **alternative text of images**. After each upload, a Jahia background job sends the image to a vision model. The job saves the description in the image's `jahia_ai_alt` property, in every language of the site. A GraphQL mutation schedules the same job for the images already in a site.

The module reaches its models through **genai-connector**, Jahia's shared AI connector. The provider, the model and the API key are configured once in genai-connector, for every AI feature of the platform: the Jahia AI gateway, Anthropic, OpenAI, Mistral, Azure OpenAI, Amazon Bedrock or any OpenAI-compatible endpoint.

It is built to Jahia's security and integration standards: the action is POST-only, runs with the editor's own permissions (no privilege escalation), is protected by the CSRF guard, and never ships or logs your API key.

**Key features**

- One-click **Auto Tagging** action in the Content Editor 3-dots menu.
- **Alternative text of images** at upload, in background jobs that survive a restart and wait when the AI provider is busy.
- **Alternative text for existing images** through the GraphQL mutation `scheduleImageAltText`, for one image or every image below a path.
- **Provenance of each AI text**: the model, the date and a reviewed flag. A replaced image gets a new text, unless an editor wrote, changed or reviewed the current one.
- **One AI configuration** in genai-connector: provider, model, key, access check per site, rate limit and audit log.
- **Brand frame**: with the module editorial-frame, the tags and the alternative text follow the voice, the writing rules and the glossary of their site.
- **Pick the tag language** at generation time, independently of the content language.
- **Choose how many tags** (up to the configured maximum) and whether to **replace** the existing tags or **add to** them, right in the dialog.
- Tags are written to Jahia's standard tagging field (`j:tagList`), so they work with existing facets, queries and tag-based navigation.
- The action appears only on taggable content and only when the module is enabled on the site.
- **English and French** user interface.
- Secure by design: POST-only endpoint, caller-session reads, CSRF-protected, no key in the bundle, content logged at debug level only.

**Requirements**

- Jahia 8.2.2 or later.
- genai-connector, with a model for the `lite` tier, and a model that accepts images for the `vision` tier. The alternative text and the tags of an image use `vision`. Model usage is billed by the provider configured in genai-connector.
- The permission `genaiAccess` on the site, for each editor who generates tags or alternative text. genai-connector grants it to its editorial roles.
- Optional: the module editorial-frame, for the brand frame.

## Installation

1. **Install the module.** In Jahia Administration → Modules, upload the module JAR (or install it directly from the Jahia Store), then start it.
2. **Enable it on your site.** In Administration → Modules, add *Automatic Content Tags* to the site(s) where you want the action available. (The Auto Tagging action only appears on sites where the module is enabled.)
3. **Configure AI in genai-connector.** Set the provider, the model and the key of the `lite` and `vision` tiers in `org.jahia.modules.genai.cfg`. The genai-connector documentation describes these keys.
4. **Tune the module if you need to.** Edit `org.jahia.se.modules.contenttags.cfg` in `digital-factory-data/karaf/etc/`. Jahia installs this file when the module starts for the first time, and the file lists each key with its default. For example, set `alt.generate.on.upload=false` to stop the alternative text at upload. A change applies without a restart.
5. **Use it.** Open a content item in the Content Editor, open the 3-dots menu, click **Auto Tagging**, choose the tag language, the number of tags and whether to replace or add to existing tags, then click **Apply**. Save the content to persist the generated tags.

> This module holds no API key: the key lives only in the genai-connector configuration on the server.

## FAQ

**Which AI providers are supported?**
Every provider of genai-connector: the Jahia AI gateway, Anthropic, OpenAI, Mistral, Azure OpenAI, Amazon Bedrock and any OpenAI-compatible endpoint. The operator chooses it in genai-connector.

**How is the alternative text of an image generated?**
A background job sends the image to the vision model of the `vision` tier of genai-connector. When the original file is too large or in another format, the job sends the thumbnail that Jahia made. The job saves the answer in the i18n property `jahia_ai_alt` of the image, in the edit workspace, and publishes nothing. The job runs a few seconds after each upload, as the uploader. The GraphQL mutation `scheduleImageAltText` starts the same job for the images already in a site.

**Does the module replace a text that an editor wrote?**
No. Without the `overwrite` option, the module keeps a text that an editor wrote, changed or marked as reviewed. It replaces only its own unreviewed text, and only when the picture changed since. For example, a new photo uploaded over `team.jpg` gets a new text, unless an editor reviewed the old one.

**Do I need an API key, and does it cost anything?**
genai-connector needs one for its provider, and model usage is billed by that provider. The module itself is free (MIT).

**What content is sent to the AI provider?**
Two features send content, at two different times:

- Tags: when you click Apply, the module sends the internationalized text of the content item, with HTML stripped and truncated to `llm.max.source.chars`. For an image, it also sends the picture and the file name.
- Alternative text: after each upload, the module sends the picture, its file name and its title, unless `alt.generate.on.upload` is false. A GraphQL mutation sends the same data for the images it names.
- With the module editorial-frame, each call also sends the published editorial frame of the site. The frame holds the voice of the brand, its writing rules and its glossary terms.

The module stores the AI answers and their provenance on the content. It stores no other copy of your content.

**Which content types can be tagged?**
Standard content, pages, main-resource content and images (`jnt:content`, `jnt:page`, `jmix:mainResource`, `jmix:image`). The action is hidden on other node types and on sites where the module is not enabled.

**Who may generate tags or alternative text?**
An editor who holds the permission `genaiAccess` on the site. genai-connector checks the permission on each call, before it sends anything to the provider. An editor without the permission sees "You may not use AI on this site". A background job of that editor makes no call for that site.

**Does it tag unsaved changes?**
No. Tags are generated from the last saved version of the content. Save your edits first, then run Auto Tagging.

**Where are the tags stored?**
In Jahia's standard tag list (`j:tagList` via the `jmix:tagged` mixin), so they integrate with existing tag facets, queries and navigation.

**Does it replace existing tags?**
Your choice. The dialog has a **"Replace existing tags"** checkbox — checked replaces the current tag list, unchecked adds the generated tags to it (duplicates are removed).

**Can I control how many tags are generated?**
Yes. Set the **number of tags** in the dialog on each run. An administrator caps the maximum with `llm.tag.count.max` (default 20, up to 50) and sets the fallback with `llm.tag.count.default`; requests are clamped to the configured maximum. To tune the *style* of the tags, edit `llm.user.prompt` — but leave the count and language to the dialog, which the module injects authoritatively.

**In which language are the tags generated?**
You choose the tag language in the dialog at generation time; it is independent of the content's edit language.

**Is my API key safe?**
This module never sees it. The key lives only in the genai-connector configuration on the server.

**Does it work in production / clustered environments?**
Yes. It is a standard OSGi module that needs only genai-connector and its access to the provider. The alternative text jobs are stored in the Jahia database, and the processing node runs them.

## Tags / keywords

`ai`, `llm`, `tagging`, `tags`, `taxonomy`, `metadata`, `content-editor`, `jcontent`, `automation`, `productivity`, `genai`, `alt-text`, `accessibility`, `images`, `nlp`, `seo`, `content-management`, `ui-extension`
