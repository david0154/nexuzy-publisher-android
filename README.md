# Nexuzy Publisher Android

Android companion for **Nexuzy Publisher Desk** that turns RSS feeds into AI-verified WordPress drafts.

> This app is focused on mobile editorial workflow:
> **Collect RSS → Rank top stories → Run AI pipeline → Save draft → Push to WordPress**.

---

## 🚀 Highlights

- Multi-feed RSS management (add/remove feeds in-app)
- AI pipeline orchestration:
  - Gemini: write/rewrite article
  - OpenAI: fact verification
  - Sarvam: grammar cleanup
- SEO-ready draft metadata generation
  - focus keyphrase
  - meta description
  - tags / keywords
- WordPress draft push with optional ads code injection
- Local draft persistence using Room database
- Optional Google Sign-In / Firebase user profile sync
- Encrypted API key storage (`EncryptedSharedPreferences`)

---

## 🧠 End-to-End Flow

1. Configure API keys + WordPress credentials in **Settings**
2. Add RSS feeds in **RSS** tab
3. Open **AI Writer** and generate an article from a selected topic
4. Verify/clean content via pipeline progress states
5. Save to local draft queue (**Articles** tab)
6. Publish as WordPress draft from editor

---

## 📱 Main Screens

- **Dashboard**: article + feed counters
- **RSS**: feed add/delete + live feed list
- **Articles**: local saved drafts list
- **AI Writer**: shortcuts to editor/settings
- **WordPress**: publishing/settings shortcuts

---

## 🔐 Security Notes

- API keys and WordPress password are stored in encrypted shared preferences.
- Keep Firebase rules strict for production (see `FIREBASE_RULES.md`).
- Prefer WordPress Application Passwords instead of primary account passwords.

---

## 🛠️ Project Setup

See:

- `SETUP.md` — full setup guide
- `FIREBASE_RULES.md` — recommended Firestore rules
- `PRIVACY.md` — privacy policy
- `LICENSE`

Firebase file location:

- `app/google-services.json`

---

## 🧩 Tech Stack

- Kotlin + AndroidX
- Navigation Component + ViewBinding
- Room (local DB)
- WorkManager
- OkHttp + Gson
- Firebase Auth + Firestore
- Jetpack Security Crypto

---

## 👨‍💻 Maintainer

- **David**
- **Nexuzy Lab**
- Support: `nexuzylab@gmail.com`
- Android repo: <https://github.com/david0154/nexuzy-publisher-android>
- Desktop repo: <https://github.com/david0154/nexuzy-publisher-desk>

