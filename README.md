# OldGemAI

A lightweight Android chat app for talking with Google Gemini AI. Built in pure Java with no third-party libraries - just the Android SDK and the Gemini API.

## Features

- **Text chat** with full conversation history sent on every request
- **Image input** - attach a photo from your gallery and ask questions about it
- **Image generation** - type `/image <prompt>` to generate an image with Gemini; the image model is picked automatically (see below)
- **Streaming responses** - the answer is typed out gradually as Gemini generates it; can be turned off in Settings to get the full answer at once (the old behavior)
- **Chat history** - chats are saved to a local SQLite database; open, rename or delete them from the History screen
- **Copy by long press** - hold any message bubble to copy its text to the clipboard
- **Adaptive menu** - on phones without a hardware menu button, Settings and Clear chat appear as buttons at the top of the screen
- **Model selection** - loads the list of available Gemini models directly from the API so you always get the latest options
- **Configurable temperature** - adjust creativity from 0.0 to 2.0 via a slider
- **System prompt** - set a custom system instruction that applies to every message
- **Bilingual UI** - Russian and English, chosen on first launch
- **No dependencies** - no Retrofit, no OkHttp, no Gson, no AndroidX; pure `HttpURLConnection` and `org.json`
- **TLS 1.2 support** - works on Android 1.0+ via a custom `SSLSocketFactory`

## Requirements

- Android 1.0 (API 1) or higher
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
5. Sign apk, To sign the APK, you need to navigate to OldGemAI\app\build\outputs\apk, copy uber-apk-signer.jar here and run:

	```
    keytool -genkeypair -v -keystore my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
	```
	
	Then enter the password of your choice. After that, run:
	
	```
    java -jar uber-apk-signer.jar -a app-debug.apk --ks my.keystore --ksAlias mykey --ksKeyPass password --ksPass password --v2SigningEnabled true
	```
	
	APK in the releases are already signed.

### 3. First launch

On first launch the app asks you to choose a language, then walks you through entering your API key. You can skip this step and add the key later in Settings.

## Usage

**Sending a message** - type in the input field and tap Send.

**Attaching an image** - tap the + button, pick a photo from your gallery. The image is scaled to a maximum of 800 px on the long side and compressed before sending.

**Generating an image** - type `/image <description>` and tap Send. The app picks an image model automatically and displays the result inline. The Gemini API does not report whether a model is free, so the app loads the model list, keeps the models with `image` in the name and tries them one by one (the last working model first, then `lite` variants, then the rest; up to 5 attempts) until one succeeds. The working model is remembered for next time. If none of them works on your key (for example, no free quota), the last error is shown.

**Settings** - open the menu (three-dot or hardware menu button) and tap Settings. From there you can:
- Change your API key
- Refresh and pick a Gemini model
- Set the language
- Adjust temperature
- Write a system prompt
- Turn streaming (gradual typing of the answer) on or off

If your phone has no hardware menu button (most modern devices), the Settings and Clear chat buttons are shown at the top of the chat screen instead of the menu.

**Streaming** - by default the answer appears gradually while Gemini is writing it. To get the full answer at once, as in earlier versions, turn off the streaming checkbox in Settings and tap Save.

**Chat history** - every chat is saved automatically. Tap History to open a saved chat. Long-press a chat in the list to open, rename or delete it; the menu in the History screen has Clear all.

**Copy text** - long-press a message bubble to copy its text.

**Clear chat** - starts a new empty chat. The previous chat stays saved in History.

## Project Structure

```
app/src/main/java/com/geminiapp/chat/
├── MainActivity.java          - chat screen, image picker, message list
├── GeminiApi.java             - all API calls (chat, image gen, model list)
├── ChatAdapter.java           - ListView adapter for chat bubbles, copy on long press
├── ChatDatabase.java          - SQLite storage for chats and messages
├── ChatHistoryActivity.java   - saved chats list (open, rename, delete)
├── SettingsActivity.java      - settings screen
├── SetupApiKeyActivity.java   - first-launch API key setup
├── SetupLanguageActivity.java - first-launch language picker
├── Prefs.java                 - SharedPreferences wrapper
└── Tls12SocketFactory.java    - TLS 1.2 patch for old Android versions
```

## Technical Notes

The project uses no external libraries. All HTTP communication is done with `HttpURLConnection`, JSON is parsed with `org.json`, and the UI is built entirely in code (no XML layouts used in the main activity). This keeps the APK small and the dependency tree empty.

TLS 1.2 is not enabled by default on Android 1.0-4.4. `Tls12SocketFactory` wraps the system `SSLSocketFactory` and forces TLS 1.2 negotiation so the app can reach the Gemini API on old devices.

Streaming uses the `streamGenerateContent?alt=sse` endpoint (Server-Sent Events); the response is read line by line and the message bubble is updated on every chunk.

Default model: `gemini-2.5-flash`. The image model is not fixed: it is discovered at runtime from the model list.

The model list request uses `pageSize=1000` so that image models are not cut off by the default page size.

## Screenshots

<img width="320" height="480" alt="screenshot-20260619-001330" src="https://github.com/user-attachments/assets/25b9e920-b721-4b26-be03-535c02e1bc2b" />
<img width="320" height="480" alt="screenshot-20260619-002222" src="https://github.com/user-attachments/assets/82b132f7-82d0-4e28-818e-85b0aeea7d47" />
<img width="320" height="480" alt="screenshot-20260619-001302" src="https://github.com/user-attachments/assets/72a0fe94-5254-4332-8e3d-b12136791f18" />
<img width="320" height="480" alt="screenshot-20260619-001316" src="https://github.com/user-attachments/assets/1fdf838e-1400-4105-b695-ae178755711c" />



## Reporting Bugs

Telegram: [@ialwaysloveyou0](https://t.me/ialwaysloveyou0)

## License

MIT

