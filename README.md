# Gemini Chat

A lightweight Android chat app for talking with Google Gemini AI. Built in pure Java with no third-party libraries - just the Android SDK and the Gemini API.

## Features

- **Text chat** with full conversation history sent on every request
- **Image input** - attach a photo from your gallery and ask questions about it
- **Image generation** - type `/image <prompt>` to generate an image with Gemini
- **Model selection** - loads the list of available Gemini models directly from the API so you always get the latest options
- **Configurable temperature** - adjust creativity from 0.0 to 2.0 via a slider
- **System prompt** - set a custom system instruction that applies to every message
- **Bilingual UI** - Russian and English, chosen on first launch
- **No dependencies** - no Retrofit, no OkHttp, no Gson, no AndroidX; pure `HttpURLConnection` and `org.json`
- **TLS 1.2 support** - works on Android 1.0+ via a custom `SSLSocketFactory`

## Requirements

- Android 1.0 (API 1) or higher (⚠️ Android 1.0 support is unverified — only tested on Android 2.3+.)
- A Google Gemini API key (free tier available)

## Getting Started

### 1. Get an API key

1. Go to [aistudio.google.com/api-keys](https://aistudio.google.com/api-keys)
2. Sign in with your Google account
3. Click **Create API key**
4. Select or create a project
5. Copy the key - it starts with `AIza...`

### 2. Build and install

1. Clone the repository:
   ```
   git clone https://github.com/andr9y3/OldGemAI.git
   ```
2. Open the project in Android Studio.
3. Let Gradle sync.
4. Click **Run 'app'** or build an APK via **Build -> Build APK**.

	Or build with gradlew...
	
	```
    gradlew clean assembledebug -Pandroid.buildToolsVersion=30.0.3
	```

### 3. First launch

On first launch the app asks you to choose a language, then walks you through entering your API key. You can skip this step and add the key later in Settings.

## Usage

**Sending a message** - type in the input field and tap Send.

**Attaching an image** - tap the + button, pick a photo from your gallery. The image is scaled to a maximum of 800 px on the long side and compressed before sending.

**Generating an image** - type `/image <description>` and tap Send. The app calls `gemini-3.1-flash-image` and displays the result inline.

**Settings** - open the menu (three-dot or hardware menu button) and tap Settings. From there you can:
- Change your API key
- Refresh and pick a Gemini model
- Set the language
- Adjust temperature
- Write a system prompt

**Clear chat** - open the menu and tap Clear chat. This wipes the in-memory history; the app has no persistent chat storage.

## Project Structure

```
app/src/main/java/com/geminiapp/chat/
├── MainActivity.java          - chat screen, image picker, message list
├── GeminiApi.java             - all API calls (chat, image gen, model list)
├── ChatAdapter.java           - ListView adapter for chat bubbles
├── SettingsActivity.java      - settings screen
├── SetupApiKeyActivity.java   - first-launch API key setup
├── SetupLanguageActivity.java - first-launch language picker
├── Prefs.java                 - SharedPreferences wrapper
└── Tls12SocketFactory.java    - TLS 1.2 patch for old Android versions
```

## Technical Notes

The project uses no external libraries. All HTTP communication is done with `HttpURLConnection`, JSON is parsed with `org.json`, and the UI is built entirely in code (no XML layouts used in the main activity). This keeps the APK small and the dependency tree empty.

TLS 1.2 is not enabled by default on Android 1.0-4.4. `Tls12SocketFactory` wraps the system `SSLSocketFactory` and forces TLS 1.2 negotiation so the app can reach the Gemini API on old devices.

Default model: `gemini-2.5-flash`.

## Screenshots

<img width="320" height="480" alt="screenshot-20260619-001330" src="https://github.com/user-attachments/assets/25b9e920-b721-4b26-be03-535c02e1bc2b" />
<img width="320" height="480" alt="screenshot-20260619-002222" src="https://github.com/user-attachments/assets/82b132f7-82d0-4e28-818e-85b0aeea7d47" />
<img width="320" height="480" alt="screenshot-20260619-001302" src="https://github.com/user-attachments/assets/72a0fe94-5254-4332-8e3d-b12136791f18" />
<img width="320" height="480" alt="screenshot-20260619-001316" src="https://github.com/user-attachments/assets/1fdf838e-1400-4105-b695-ae178755711c" />



## Reporting Bugs

Telegram: [@ialwaysloveyou0](https://t.me/ialwaysloveyou0)

## License

MIT

