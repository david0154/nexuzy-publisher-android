# Setup Guide

## 1) Required tools
- Android Studio
- JDK 17
- Android SDK 34

## 2) Required API keys
- Gemini API key 1/2/3
- OpenAI API key 1/2/3
- Sarvam API key

## 3) WordPress setup
- WordPress site URL
- Username
- Application password
- Optional Ads code snippet

## 4) Firebase setup
- Create Firebase project
- Enable Google Authentication
- Enable Firestore
- Put `google-services.json` in `app/`

## 5) Workflow
1. Add RSS links.
2. Tap Fetch News.
3. App fetches RSS + image.
4. App verifies facts and confidence score via OpenAI.
5. App writes/rewrites with Gemini.
6. App cleans text via Sarvam.
7. App saves local draft and pushes draft to WordPress.


## 6) Firebase JSON location
- Place downloaded file at: `app/google-services.json`
- Do not place it in project root.

## 7) App logo/icon location
- Replace launcher icons in:
  - `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
  - `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
  - PNG assets under `app/src/main/res/mipmap-*`
- Manifest already points to `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`.


## 8) Critical runtime checklist
- If `google-services.json` is missing, Firebase auth/login will fail.
- If Gemini keys are missing, app redirects to Settings at launch and writing pipeline cannot start.
- OpenAI/Sarvam keys are optional for best quality but recommended.
- WordPress credentials are required for draft push.
