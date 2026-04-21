# Nexuzy Publisher Android

Nexuzy Publisher Android is an AI-powered news drafting and WordPress publishing app inspired by the desktop workflow from `david0154/nexuzy-publisher-desk`.

It is designed for this flow:
1. Add RSS sources.
2. Fetch latest feed items + extract article image.
3. Verify and score factual confidence.
4. Rewrite to a clean publishable article with AI.
5. Apply grammar/spelling clean-up.
6. Save locally as draft.
7. Push to WordPress as draft with SEO metadata.

---

## Developer Profile

- **Name:** David
- **Organization:** Nexuzy Lab
- **Role:** Founder & Maintainer
- **Open Source Repo:** https://github.com/david0154/nexuzy-publisher-android
- **Desktop Reference:** https://github.com/david0154/nexuzy-publisher-desk
- **Support Email:** nexuzylab@gmail.com

---

## Detailed Workflow

### 1) RSS Source Management
- Users add RSS links in app.
- Active feeds are stored in Room DB.
- Per-user RSS links can be synced through Firebase when enabled.

### 2) News Fetch + Media Extraction
- RSS XML is parsed for title/description/link/date.
- Image is resolved in order: enclosure/media tags → article og:image/twitter:image fallback scraping.

### 3) Verification and Confidence
- OpenAI is used for fact validation and confidence score.
- Problematic claims can be corrected before final draft output.

### 4) AI Writing Pipeline
- Gemini generates/rewrites long-form article output.
- Sarvam runs grammar and spelling cleanup.
- SEO fields are generated (tags, keyphrase, description, keywords).

### 5) Draft Save + WordPress Push
- Article is saved into local DB as `draft`.
- WordPress push uses REST API and keeps status as `draft`.
- Payload includes title, content, category, tags, image, SEO meta fields.

---

## APIs currently required
- Gemini API key 1/2/3
- OpenAI API key 1/2/3
- Sarvam API key
- WordPress site URL + username + app password
- Optional WordPress ads code

> Perplexity/Replit/Maps/Weather optional keys were removed as requested.

---

## Project structure
- `app/src/main/java/com/nexuzy/publisher/network/` → RSS + AI + WordPress API clients
- `app/src/main/java/com/nexuzy/publisher/ai/` → AI pipeline orchestration
- `app/src/main/java/com/nexuzy/publisher/workflow/` → end-to-end fetch→verify→draft workflow
- `app/src/main/java/com/nexuzy/publisher/data/` → Room/Firebase models + DAO + prefs
- `app/src/main/java/com/nexuzy/publisher/ui/` → activities/screens

---

## Documentation
- `SETUP.md` → setup checklist
- `PRIVACY.md` → privacy policy
- `LICENSE` → custom license terms

---

## License (summary)
This project is free to use under the included custom license. Modification/redistribution requires permission. Rights are reserved by David and Nexuzy Lab. Contributions are welcome through pull requests.
