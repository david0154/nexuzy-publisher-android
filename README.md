# Nexuzy Publisher Android

Android application for offline-first AI-assisted RSS news drafting and WordPress draft publishing.

## Developer / Organization
- Developer: **David**
- Organization: **Nexuzy Lab**
- Support: **nexuzylab@gmail.com**
- Android Repo: https://github.com/david0154/nexuzy-publisher-android
- Desktop Reference: https://github.com/david0154/nexuzy-publisher-desk

## Working flow (similar to desktop flow)
1. User adds RSS feed links.
2. Tap **Fetch News**.
3. App fetches feed items + tries to resolve article image.
4. App verifies article claims and confidence score with OpenAI.
5. Gemini writes/re-writes title + article body.
6. Sarvam cleans grammar/spelling.
7. App saves article as local **draft**.
8. App pushes to WordPress as **draft** in category.

Workflow implementation entrypoint:
- `NewsWorkflowManager.fetchVerifyWriteSaveAndPushDraft(...)`

## Feature modules
- RSS parser + image fallback scraping
- AI pipeline orchestration (Gemini + OpenAI + Sarvam)
- Local Room database for drafts
- WordPress REST draft publishing
- Firebase user profile + per-user RSS links
- Google sign-in helper

## API keys supported in app settings backend
- Gemini API 1 / 2 / 3
- OpenAI API 1 / 2 / 3
- Sarvam API 1
- Perplexity API 1 / 2 / 3 (new)
- Replit API 1 / 2 / 3 (new)
- Maps API (new)
- Weather API (new)

## Documentation files
- `SETUP.md` - full setup checklist
- `PRIVACY.md` - privacy policy
- `LICENSE` - custom license/rights statement

## License and contributions
This project is free for usage under the included custom license. Modification is not allowed without permission. Rights reserved by David and Nexuzy Lab. Contributions are welcome through pull requests.
