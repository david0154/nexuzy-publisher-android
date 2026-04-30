# 📰 Nexuzy Publisher — Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/AI-Gemini%20%7C%20OpenAI%20%7C%20Sarvam-FF6B35?style=for-the-badge" />
  <img src="https://img.shields.io/badge/CMS-WordPress-21759B?style=for-the-badge&logo=wordpress&logoColor=white" />
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" />
</p>

> **Nexuzy Publisher** is an AI-powered Android news publishing app that transforms RSS feeds into fully verified, SEO-optimised WordPress draft articles — automatically. It combines **Gemini AI** for writing, **OpenAI** for fact verification, **Sarvam AI** for grammar correction, and a **WordPress REST API** push pipeline into one seamless editorial workflow.

---

## ✨ Features

### 🔐 Authentication & Onboarding
- **Google Sign-In** via Firebase Authentication (`GoogleSignInManager`)
- Auto-login if a session already exists — skips directly to `MainActivity`
- Optional **Skip Login** for offline/local-only use
- Firebase Firestore **user profile sync** on first login (`upsertUserProfile`)
- Encrypted credential storage using **Jetpack Security `EncryptedSharedPreferences`**

### ⚙️ Settings & API Configuration
- **Gemini API Key** — primary AI writing engine (Google Generative AI)
- **OpenAI API Key** — fact verification & authenticity check
- **Sarvam AI API Key** — grammar and spelling correction (Indian language support)
- **WordPress Site URL, Username, Application Password** — for REST API draft push
- **Ads Code** *(optional)* — injected into article HTML before WordPress push
- **Support Email** and **Privacy Policy URL** — in-app display
- All keys stored in `EncryptedSharedPreferences` — never in plaintext

### 📡 RSS Feed Management
- Add unlimited RSS feed URLs with **name** and **category** tags
- Feeds stored in **Room Database** (`RssFeedEntity`)
- Delete individual feeds via long-press
- **Fetch News** button triggers full RSS scrape across all active feeds
- Supports RSS 2.0 and Atom feed formats
- Automatic **image scraping** from `<media:content>`, `<enclosure>`, Open Graph `og:image`, and first `<img>` tag in article body
- Per-feed `limitPerFeed` control (default: 20 items)

### 📊 News Scoring & Discovery (Dashboard)
- **Today's News filter** — strips items older than 24 hours using multi-format date parsing (IST, GMT, RFC-1123, ISO-8601)
- **Scoring Engine** — scores each article by:
  - Presence of image (+10)
  - Rich description length (+10)
  - Hot keywords: `breaking`, `live`, `urgent`, `election`, `war`, `market`, `ai`, `launch` (+8 each)
  - Emotional/viral words: `shocking`, `viral`, `exclusive`, `revealed`, `first` (+6 each)
  - Title length sweet spot (+5)
  - Recently published (+15)
- **Three news sections displayed:**
  - 🕐 **Latest News** — all today's items, newest first (up to 20)
  - 🔥 **Hot News** — high-scoring `isHot` articles (top 10)
  - 🚀 **Potential Viral** — `isPotentialViral` candidates (top 10)
- **Related News Clustering** — groups articles by shared keywords into topic clusters
- Tap any news item → opens **AI Article Editor**

### 🤖 AI Pipeline (5-Step)

The full pipeline is orchestrated by `AiPipeline.kt` and `NewsWorkflowManager.kt`:

```
Step 1  ─  OpenAI Fact Verification
           └─ Checks article authenticity, returns confidence score (0–100)
           └─ Suspicious articles are flagged with reason text

Step 2  ─  Gemini Article Rewrite
           └─ Rewrites full article body in professional news style
           └─ Expands thin RSS description into 400–800 word article
           └─ Returns rewritten title + body

Step 3  ─  Sarvam AI Grammar & Spelling Check
           └─ Corrects grammar, spelling, punctuation
           └─ Supports English + Indian language content

Step 4  ─  Gemini SEO Generation
           └─ Generates: focus keyphrase, meta description, tags, meta keywords
           └─ All saved to Article entity for WordPress Yoast/RankMath integration

Step 5  ─  Article Image Download
           └─ Downloads RSS image URL to local storage (/files/article_images/)
           └─ Used as WordPress featured image on push
           └─ Falls back to remote RSS imageUrl if download fails
```

### ✏️ Article Editor (`ArticleEditorActivity`)
- Pre-filled with RSS title + description on open
- **Run AI Pipeline** button — runs all 5 steps with live progress status
- Gemini **rewritten title** auto-applied to title field after pipeline
- Full article body editable after generation
- **AI Chips** show pipeline completion: `Gemini ✅ | OpenAI ✅ | Sarvam ✅ | SEO ✅`
- **Fact feedback** panel shows OpenAI confidence score and reasoning
- **Image status bar** shows RSS image found → downloaded path
- **Save as Draft** — persists fully populated `Article` to Room DB including:
  - SEO: tags, metaKeywords, focusKeyphrase, metaDescription
  - Image: imageUrl (remote) + imagePath (local)
  - Source: sourceUrl, sourceName, category
  - AI flags: geminiChecked, openaiChecked, sarvamChecked, factCheckPassed, confidenceScore
- **Publish Draft to WordPress** — pushes immediately as WP draft with ads injection
- Back-press confirmation: _"Save draft before leaving?"_

### 🗂️ Articles Tab (Draft Management)
- Lists all locally saved articles from Room DB
- Shows status badge: `draft` / `published`
- Open any article to re-edit or re-push
- Article count shown in Dashboard widget

### 🌐 WordPress Integration
- REST API push via `WordPressApiClient.kt`
- **Application Password** authentication (secure, no OAuth needed)
- Pushes article as **draft** (never auto-publishes)
- Sets: title, content, excerpt (meta description), tags, categories, slug
- Injects **ads code** into article HTML before push (optional)
- Uploads downloaded image as **featured image** (media upload + post attach)
- Full SEO meta fields sent for Yoast/RankMath compatibility
- `pushNewsDraftWithSeo()` — full SEO push
- `pushDraft()` — quick push from editor
- Returns `postId` — saved locally to Room for tracking
- **Test Connection** — validates credentials before any push
- Saves `wordpressPostId` back to local Article record after success

### 🔒 Duplicate Detection
- Before any article is processed, `articleDao().countBySourceUrl(link)` checks Room DB
- Duplicate RSS items are skipped silently with a log entry
- Prevents the same story being processed and pushed multiple times

### 📦 Local Database (Room)
- `AppDatabase` with 4 DAOs:
  | DAO | Entity | Purpose |
  |-----|--------|---------|
  | `ArticleDao` | `Article` | All generated/saved articles |
  | `RssFeedDao` | `RssFeedEntity` | Saved RSS feed URLs |
  | `WordPressSiteDao` | `WordPressSite` | WP site credentials |
  | `UserDao` | `UserProfile` | Firebase user profile cache |

### ⚙️ Background Processing
- **WorkManager** (`NewsPublisherWorker`) for scheduled background batch runs
- Runs `fetchVerifyWriteSaveAndPushDraft()` — fetches top-15 articles, full pipeline, pushes all
- Configurable scheduling from Settings

---

## 🗺️ Complete App Workflow

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. LAUNCH                                                          │
│     └─ Auto-login check → Google Sign-In OR Skip                   │
├─────────────────────────────────────────────────────────────────────┤
│  2. SETTINGS (first-time setup)                                     │
│     └─ Enter: Gemini API Key                                        │
│     └─ Enter: OpenAI API Key                                        │
│     └─ Enter: Sarvam AI API Key                                     │
│     └─ Enter: WordPress URL + Username + App Password               │
│     └─ Enter: Ads Code (optional)                                   │
│     └─ Save → all encrypted in EncryptedSharedPreferences           │
├─────────────────────────────────────────────────────────────────────┤
│  3. RSS TAB                                                         │
│     └─ Add RSS feed URLs (name + category)                          │
│     └─ Tap "Fetch News" → RssFeedParser scrapes all feeds           │
│     └─ Sarvam duplicate check → dedup by source URL                │
│     └─ Results posted to NewsViewModel (shared state)              │
│     └─ Auto-navigate to Dashboard                                   │
├─────────────────────────────────────────────────────────────────────┤
│  4. DASHBOARD                                                       │
│     └─ Latest News list (all today's items)                         │
│     └─ 🔥 Hot News list (high score)                                │
│     └─ 🚀 Potential Viral list (viral score)                        │
│     └─ Tap any item → ArticleEditorActivity                        │
├─────────────────────────────────────────────────────────────────────┤
│  5. AI ARTICLE EDITOR                                               │
│     └─ Pre-filled title + description from RSS                      │
│     └─ Tap "Run AI Pipeline":                                       │
│         ├─ Step 1: OpenAI → fact verify + confidence score          │
│         ├─ Step 2: Gemini → full article rewrite + SEO title        │
│         ├─ Step 3: Sarvam → grammar + spelling correction           │
│         ├─ Step 4: Gemini → SEO meta (tags, keyphrase, description) │
│         └─ Step 5: Download RSS image to local storage              │
│     └─ Rewritten title auto-applied to title field                  │
│     └─ Edit content if needed                                       │
│     └─ "Save Draft" → Room DB (full Article with all SEO fields)    │
│     └─ "Publish Draft" → WordPress REST API push                    │
├─────────────────────────────────────────────────────────────────────┤
│  6. WORDPRESS TAB                                                   │
│     └─ Test Connection → validates WP credentials                   │
│     └─ View local drafts awaiting push                              │
│     └─ Manual push any saved draft                                  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 🏗️ Architecture & Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Kotlin |
| UI | AndroidX + ViewBinding + Navigation Component |
| Architecture | MVVM — ViewModel + LiveData + Repository |
| Local DB | Room (SQLite) with 4 entities |
| Networking | OkHttp 4 + Gson |
| AI — Writing | Google Gemini API (`generativelanguage.googleapis.com`) |
| AI — Fact Check | OpenAI Chat Completions API (`gpt-4o-mini`) |
| AI — Grammar | Sarvam AI API |
| Auth | Firebase Authentication (Google Sign-In) |
| Cloud DB | Firebase Firestore (user profile) |
| Security | Jetpack Security `EncryptedSharedPreferences` |
| Background Jobs | WorkManager |
| Image Loading | Android `ImageDownloader` (HTTP download to local storage) |
| RSS Parsing | Custom `RssFeedParser` (OkHttp + XmlPullParser) |
| WordPress | WordPress REST API v2 (Application Passwords) |

---

## 📁 Project Structure

```
app/src/main/java/com/nexuzy/publisher/
│
├── ui/
│   ├── auth/
│   │   └── LoginActivity.kt          # Google Sign-In + Firebase auth
│   ├── main/
│   │   ├── MainActivity.kt           # Bottom nav host (5 tabs)
│   │   ├── NewsViewModel.kt          # Shared ViewModel — DailyNewsSnapshot
│   │   ├── DashboardFragment.kt      # Latest / Hot / Viral news lists
│   │   ├── RssFragment.kt            # RSS feed management + fetch trigger
│   │   ├── ArticlesFragment.kt       # Local draft list
│   │   ├── AiWriterFragment.kt       # Shortcut hub to editor
│   │   └── WordPressFragment.kt      # WP connection + drafts list
│   ├── editor/
│   │   ├── ArticleEditorActivity.kt  # Full AI pipeline editor UI
│   │   └── ArticleEditorViewModel.kt # Pipeline state management
│   └── settings/
│       └── SettingsActivity.kt       # API keys + WP credentials
│
├── ai/
│   └── AiPipeline.kt                 # 5-step AI orchestrator
│
├── workflow/
│   └── NewsWorkflowManager.kt        # Batch + single-item workflow logic
│
├── network/
│   ├── RssFeedParser.kt              # RSS 2.0 / Atom feed scraper
│   └── WordPressApiClient.kt         # WP REST API v2 client
│
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt
│   │   ├── ArticleDao.kt
│   │   ├── RssFeedDao.kt
│   │   ├── WordPressSiteDao.kt
│   │   └── UserDao.kt
│   ├── model/
│   │   ├── Article.kt
│   │   ├── RssItem.kt
│   │   ├── RssFeedEntity.kt
│   │   ├── WordPressSite.kt
│   │   └── UserProfile.kt
│   └── prefs/
│       └── ApiKeyManager.kt          # EncryptedSharedPreferences wrapper
│
└── worker/
    └── NewsPublisherWorker.kt        # WorkManager background batch job
```

---

## 🚀 Setup Guide

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 26+ (minSdk 26)
- A Google Firebase project
- API keys for Gemini, OpenAI, Sarvam AI
- A WordPress site with Application Passwords enabled

### 1. Clone the repo
```bash
git clone https://github.com/david0154/nexuzy-publisher-android.git
cd nexuzy-publisher-android
```

### 2. Firebase Setup
1. Go to [Firebase Console](https://console.firebase.google.com)
2. Create a new project → Add Android app
3. Package name: `com.nexuzy.publisher`
4. Download `google-services.json` → place in `app/`
5. Enable **Authentication → Google Sign-In**
6. Enable **Firestore Database**

### 3. Build & Run
```bash
./gradlew assembleDebug
```
Or open in Android Studio and click **Run ▶**

### 4. First Launch Configuration
1. **Login** with Google (or tap Skip)
2. Go to **Settings** tab
3. Enter your API keys:
   - Gemini: [Get key](https://aistudio.google.com/app/apikey)
   - OpenAI: [Get key](https://platform.openai.com/api-keys)
   - Sarvam: [Get key](https://www.sarvam.ai)
4. Enter WordPress credentials:
   - Site URL: `https://yoursite.com`
   - Username: your WP username
   - App Password: WP Admin → Users → Application Passwords → Create new
5. Tap **Save**

### 5. Add RSS Feeds & Publish
1. Go to **RSS** tab → Add feed URLs
2. Tap **Fetch News**
3. Tap any story on the Dashboard
4. Tap **Run AI Pipeline** in the editor
5. Review → **Publish Draft to WordPress**

---

## 🔑 API Key Security

- All API keys are stored using **AndroidX Security `EncryptedSharedPreferences`** (AES-256-GCM)
- Keys are **never hardcoded** in source code or `BuildConfig`
- WordPress password uses **Application Passwords** — never your main account password
- Firebase rules should restrict Firestore read/write to authenticated users only

```json
// Recommended Firestore rules
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;
    }
  }
}
```

---

## 🤝 Contributing

1. Fork the repository
2. Create your feature branch: `git checkout -b feature/my-feature`
3. Commit your changes: `git commit -m 'feat: add my feature'`
4. Push to the branch: `git push origin feature/my-feature`
5. Open a Pull Request

Please follow [Conventional Commits](https://www.conventionalcommits.org/) for commit messages.

---

## 📄 License

```
MIT License

Copyright (c) 2024–2026 David / Nexuzy Lab

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 👨‍💻 Maintainer

| | |
|---|---|
| **Developer** | David |
| **Organisation** | Nexuzy Lab |
| **Support Email** | nexuzylab@gmail.com |
| **Android Repo** | [nexuzy-publisher-android](https://github.com/david0154/nexuzy-publisher-android) |
| **Desktop Repo** | [nexuzy-publisher-desk](https://github.com/david0154/nexuzy-publisher-desk) |

---

<p align="center">
  Made with ❤️ by <strong>Nexuzy Lab</strong> · Powered by Gemini AI, OpenAI & Sarvam AI
</p>
