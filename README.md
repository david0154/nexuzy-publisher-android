# Nexuzy Publisher Android

Android version of **Nexuzy Publisher** (inspired by `david0154/nexuzy-publisher-desk`) for RSS-based AI news drafting and WordPress publishing.

## What this app now supports

- RSS feed parsing and article source collection.
- AI content flow:
  - Gemini (up to 3 API keys) for writing/rewrite + SEO metadata.
  - OpenAI (up to 3 API keys) for fact-checking.
  - Sarvam (1 API key) for grammar cleanup.
- Save generated articles in **local Room DB as draft**.
- Push articles to WordPress as **draft**.
- Optional WordPress ad code injection into post content.
- Firebase user data model for:
  - Google login identity.
  - Per-user RSS link list in Firestore (add/delete/list).
  - User profile metadata in Firestore.

---

## Project structure (high level)

- `app/src/main/java/com/nexuzy/publisher/ai/` → AI pipeline.
- `app/src/main/java/com/nexuzy/publisher/network/` → RSS + AI provider + WordPress clients.
- `app/src/main/java/com/nexuzy/publisher/data/db/` → Room database.
- `app/src/main/java/com/nexuzy/publisher/data/firebase/` → Firestore repository for user profile/RSS links.
- `app/src/main/java/com/nexuzy/publisher/auth/` → Google sign-in helper.

---

## API keys configuration

### Gemini API (3 keys rotation)
- API 1
- API 2
- API 3

### OpenAI API (3 keys rotation)
- API 1
- API 2
- API 3

### Sarvam API (single key)
- API 1 (fixed single key)

### WordPress credentials
- Site URL
- Username
- Application Password
- Optional Ads Code snippet

---

## Firebase setup (Google login + user RSS links)

1. Create Firebase project.
2. Add Android app package: `com.nexuzy.publisher`.
3. Enable **Authentication > Google**.
4. Enable **Cloud Firestore**.
5. Download `google-services.json` and place it in `app/google-services.json`.
6. Use your Firebase Web client ID in app settings for Google sign-in flow.

Recommended Firestore collections used by code:
- `users/{uid}` (profile)
- `users/{uid}/rss_links/{rssId}` (RSS sources only)

> News/article bodies are intentionally not stored in Firestore by this integration layer.

---

## WordPress setup

1. Open WordPress admin.
2. Create Application Password for your account.
3. Use site URL + username + app password in app settings.
4. The app pushes content to `/wp-json/wp/v2/posts` as `draft`.

---

## Build setup

- Android Studio Iguana+ (or compatible AGP 8.2+)
- JDK 17 recommended
- Kotlin 1.9+
- Min SDK 26

### Gradle plugins/dependencies included
- Android + Kotlin + KSP
- Firebase Google Services plugin
- Firebase Auth + Firestore
- Play Services Auth
- Room, OkHttp, Gson, Jsoup, Coroutines

---

## Notes

- This repository currently has code-first implementation in several areas and may require matching XML/resources if missing in your branch.
- If you want, I can next add complete UI screens for:
  - Google login button flow
  - RSS add/delete list synced with Firestore
  - WordPress ads code field in Settings UI
  - One-tap “Generate + Save Draft + Push Draft to WP” pipeline action
