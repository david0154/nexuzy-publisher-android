# 📰 Nexuzy Publisher

![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen)
![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue)
![AI](https://img.shields.io/badge/AI-Gemini%20%7C%20Devil%20AI%202B%20(Gemma)%20%7C%20OpenAI-purple)
![Offline](https://img.shields.io/badge/On--Device-Gemma%202B%20Auto--Download-orange)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

Nexuzy Publisher is an Android app that turns your phone into a full AI-powered news publishing studio. Fetch RSS feeds, auto-write full articles with on-device or cloud AI, find images, and publish directly to WordPress — all from your pocket.

---

## ✨ Key Features

- 📰 **RSS Feed Reader** — aggregate news from any RSS/Atom source
- 🤖 **David AI Chat** — conversational assistant for writing, publishing, and research
- 😈 **Devil AI 2B** — on-device Gemma 2B article writing (auto-downloads, no API key)
- ☁️ **Gemini Cloud AI** — Gemini 2.0 Flash, 1.5 Flash, Flash-Lite fallback chain
- 📝 **Article Rewriter** — rewrites RSS articles into original, publish-ready content
- 🖼️ **Image Search** — DuckDuckGo image search integration
- 🚀 **WordPress Publisher** — publish to any self-hosted WordPress via REST API
- 🌦️ **Weather Context** — local weather auto-injected into article context
- 🔑 **Multi-key Support** — rotate multiple Gemini/OpenAI keys automatically

---

## 🧠 AI Pipeline (v3)

Every article goes through a smart fallback chain. No single point of failure.

```
┌──────────────────────────────────────────────────┐
│  ARTICLE GENERATION PIPELINE                    │
├──────────────────────────────────────────────────┤
│                                                │
│  Step 1: Gemini Cloud AI                       │
│    gemini-2.0-flash (primary)                  │
│    gemini-1.5-flash                            │
│    gemini-2.0-flash-lite                       │
│    gemini-1.5-flash-8b                         │
│         ↓ all keys exhausted                   │
│                                                │
│  Step 2: 😈 Devil AI 2B (on-device Gemma 2B)    │
│    ✓ 100% offline, zero API cost               │
│    ✓ Auto-downloads on first launch (~500 MB)  │
│    ✓ No token, no login, no internet needed    │
│         ↓ model not yet downloaded              │
│                                                │
│  Step 3: OpenAI fallback                       │
│         ↓                                      │
│                                                │
│  Step 4: Gemini flash-lite grammar polish       │
│  Step 5: OpenAI final humanise pass            │
│                                                │
│  ✅ Publish-ready article                       │
└──────────────────────────────────────────────────┘
```

---

## 😈 Devil AI 2B — On-Device AI Writer

Devil AI 2B is powered by **Google Gemma 2B IT** running 100% on your Android device via the **LiteRT-LM runtime** (`com.google.ai.edge.litert:litert-lm:1.0.0`).

### Auto-Download (No Setup Required)

The model downloads **automatically** on first app launch. No HuggingFace account, no token, no manual steps.

| Detail | Value |
|---|---|
| **Model** | Gemma 2B IT INT4 (GPU-optimised) |
| **Size** | ~500 MB (one-time download) |
| **Source** | Google public CDN (no auth required) |
| **Runtime** | LiteRT-LM (Google AI Edge) |
| **Storage** | `Android/data/com.nexuzy.publisher/files/models/` |
| **Min RAM** | 3 GB |
| **Min Android** | 8.0 (API 26) |

### How It Works

1. App launches → `ModelDownloadManager.autoDownloadIfNeeded()` called
2. Android `DownloadManager` fetches model from Google CDN in background
3. System notification shows download progress
4. Once complete, Devil AI 2B is ready instantly — no restart needed
5. All article generation works fully offline from this point

### Article Output Format

Every Devil AI 2B article follows a proper news structure:

```
HEADLINE (strong, factual, max 12 words)

By Nexuzy Desk | Category

LEAD — Who/What/When/Where/Why (2-3 sentences)

KEY FACTS — specific details, numbers, names

BACKGROUND — context and history

IMPACT — what it means for readers

CLOSING — what happens next
```

---

## 📱 Screenshots

> Add screenshots to `screenshots/` folder and they will appear here.

---

## 🛠️ Tech Stack

| Component | Technology |
|---|---|
| Language | Kotlin |
| Min SDK | Android 8.0 (API 26) |
| Architecture | MVVM + Repository |
| Database | Room |
| Networking | OkHttp + Retrofit |
| Image Loading | Glide |
| On-Device AI | Google LiteRT-LM (Gemma 2B IT) |
| Cloud AI | Gemini 2.0 Flash, 1.5 Flash, OpenAI GPT-4o |
| RSS Parsing | Rome + Jsoup |
| Auth | Firebase Auth + Google Sign-In |
| Publishing | WordPress REST API |
| Navigation | Navigation Component |
| Background Work | WorkManager + Android DownloadManager |

---

## 🚀 Setup

### 1. Clone the repo

```bash
git clone https://github.com/david0154/nexuzy-publisher-android.git
cd nexuzy-publisher-android
```

### 2. Add `google-services.json`

Download from your Firebase console and place in `app/google-services.json`.

### 3. Add API keys in the app

Open the app → Settings and add:

| Key | Where to get |
|---|---|
| Gemini API Key | [aistudio.google.com](https://aistudio.google.com) (free) |
| OpenAI API Key | [platform.openai.com](https://platform.openai.com) (optional) |
| WordPress URL + credentials | Your WordPress site → Users → Application Passwords |

> **No HuggingFace token needed.** Devil AI 2B downloads automatically.

### 4. Build and run

```bash
./gradlew assembleDebug
```

Or open in Android Studio and press ▶ Run.

### 5. Devil AI 2B auto-downloads

On first launch the Gemma 2B model (~500 MB) will start downloading automatically in the background. You’ll see a system notification. Gemini cloud AI works immediately while the download completes.

---

## 📁 Project Structure

```
app/src/main/java/com/nexuzy/publisher/
├── ai/
│   ├── AiPipeline.kt              # Main orchestrator for RSS → article flow
│   ├── ModelDownloadManager.kt    # Auto-downloads Gemma 2B from Google CDN
│   ├── OfflineGemmaClient.kt      # LiteRT-LM inference wrapper
│   └── OfflineArticleWriter.kt    # Structured article prompt + post-processing
├── network/
│   ├── ArticleGeneratorClient.kt  # Gemini → Devil AI 2B → OpenAI pipeline
│   ├── GeminiApiClient.kt         # Gemini REST client
│   ├── OpenAiApiClient.kt         # OpenAI REST client
│   ├── DuckDuckGoSearchClient.kt  # Image search
│   ├── RssFeedParser.kt           # RSS/Atom parser
│   ├── WeatherClient.kt           # Local weather context
│   └── WordPressApiClient.kt      # WordPress REST publisher
├── data/
│   ├── db/                        # Room database
│   └── prefs/                     # ApiKeyManager, SharedPreferences
├── ui/                            # Fragments, Activities, ViewModels
└── worker/                        # WorkManager background tasks
```

---

## 🔄 AI Fallback Chain Summary

| Priority | Provider | Requires | Works Offline? |
|---|---|---|---|
| 1st | Gemini 2.0 Flash | Gemini API key | No |
| 2nd | Gemini 1.5 Flash | Gemini API key | No |
| 3rd | Gemini Flash-Lite | Gemini API key | No |
| 4th | 😈 Devil AI 2B | Nothing (auto-download) | **Yes** |
| 5th | OpenAI GPT-4o | OpenAI API key | No |

---

## 📝 License

MIT License — see [LICENSE](LICENSE) for details.

---

## 👋 Credits

Built with ❤️ using Kotlin · Gemini AI · Google LiteRT (Gemma 2B) · Firebase · WordPress REST API
