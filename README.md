# Nexuzy Publisher Android

Android app for AI-assisted RSS news workflow: fetch feed → verify facts → rewrite article → grammar clean-up → save local draft → push WordPress draft.

## Maintainer
- Developer: **David**
- Organization: **Nexuzy Lab**
- Support: **nexuzylab@gmail.com**
- Android Repo: https://github.com/david0154/nexuzy-publisher-android
- Desktop Reference: https://github.com/david0154/nexuzy-publisher-desk

## Working flow
1. Add RSS feed links.
2. Tap **Fetch News**.
3. Parse RSS + extract article image.
4. Verify facts + confidence with OpenAI.
5. Rewrite title/article with Gemini.
6. Grammar clean-up with Sarvam.
7. Save in local Room DB as `draft`.
8. Push to WordPress as `draft` by category.

Workflow entrypoint:
- `NewsWorkflowManager.fetchVerifyWriteSaveAndPushDraft(...)`

## API keys supported
- Gemini API 1/2/3
- OpenAI API 1/2/3
- Sarvam API 1
- Perplexity API 1/2/3
- Replit API 1/2/3
- Maps API (optional)
- Weather API (optional)

## Project docs
- `SETUP.md` - setup steps and provider checklist.
- `PRIVACY.md` - privacy policy.
- `LICENSE` - custom usage terms.

## License summary
Free for use, modification requires explicit permission, rights reserved by David and Nexuzy Lab. Contributors are welcome via pull requests.


## WordPress draft payload includes
- Rewritten title
- Full article body
- Featured image upload (`featured_media`)
- Tags + category
- SEO meta: focus keyword, meta description, keywords (Yoast + RankMath compatible fields)


## Merge conflict note
If GitHub still reports conflicts, sync latest target branch into this branch and keep the versions of:
- `README.md`
- `app/src/main/java/com/nexuzy/publisher/data/prefs/ApiKeyManager.kt`
- `app/src/main/java/com/nexuzy/publisher/data/prefs/AppPreferences.kt`
from this branch.
