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
