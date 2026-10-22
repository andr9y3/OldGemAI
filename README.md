# OldGemAI

A lightweight Android chat app for talking with Google Gemini AI. Built in pure Java with no third-party libraries - just the Android SDK and the Gemini API.

## Features

- **Text chat** with full conversation history sent on every request
- **Image input** - attach a photo from your gallery and ask questions about it
- **Image generation** - type `/image <prompt>` to generate an image with Gemini; the app loads the list of image models and lets you pick one in a dialog (see below)
- **Streaming responses** - the answer is typed out gradually as Gemini generates it; can be turned off in Settings to get the full answer at once (the old behavior)
- **Chat history** - chats are saved to a local SQLite database; open, rename or delete them from the History screen
- **Copy by long press** - hold any message bubble to copy its text to the clipboard
- **Compact main screen** - one menu button (three bars) opens a screen with Settings, New chat, Back to chat and the list of saved chats; the Tools button next to the input field holds the Image button and the Search checkbox
- **Markdown in answers** - headings, bullet lists, **bold**, *italic* and ~~strikethrough~~ are rendered in the model's replies
- **Clear error messages** - API errors (quota, invalid key, overloaded server, timeout and so on) are shown in a dialog with a short explanation and the original server message
- **State survives screen rotation** - the chat, the typed text, the attached image and a response that is still being generated are kept when the activity is recreated
- **Google Search grounding** - optional Search checkbox in Tools: the model can search the web and the answer ends with a numbered list of sources
- **Model selection** - loads the list of available Gemini models directly from the API so you always get the latest options
- **Configurable temperature** - adjust creativity from 0.0 to 2.0 via a slider
- **System prompt** - set a custom system instruction that applies to every message
- **Bilingual UI** - Russian and English, chosen on first launch
- **No dependencies** - no Retrofit, no OkHttp, no Gson, no AndroidX; pure `HttpURLConnection` and `org.json`
- **TLS 1.2 support** - a custom `SSLSocketFactory` enables TLS 1.2 on Android 2.3-4.4; on Android 1.x the app shows a one-time hint about Wolfius (see below)

## Requirements

- Android 1.0 (API 1) or higher (on Android 1.x a TLS add-on such as Wolfius is required to reach the Gemini API, see Usage)
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

**Attaching an image** - tap the + button, pick a photo from your gallery. The image is scaled to a maximum of 512 px on the long side and compressed (quality 60) before sending. A preview appears above the input field; tap the cross to remove it.

**Generating an image** - type `/image <description>` and tap Send. The app loads the model list from the API, keeps the models with `image` in the name and shows them in a selection dialog. The dialog warns that on the Free plan no model can generate images, and has a "Remember choice" checkbox at the bottom: if it is ticked, the picked model is saved and the next `/image` runs without asking. After you pick a model, the generated image is displayed inline in the chat. The Gemini API does not report whether a model is free, so if the chosen model does not work on your key (for example, no free quota), the error is shown and you can try another model.

**Settings** - tap the menu button (three bars) and then Settings. From there you can:
- Change your API key
- Refresh and pick a Gemini model
- Pick the model for image generation and tick "Remember choice" to skip the model dialog on `/image` (nothing is selected by default; while the box is ticked the model list is locked, untick the box to change the model)
- Set the language
- Adjust temperature
- Write a system prompt
- Turn streaming (gradual typing of the answer) on or off

**Tools** - the Tools button next to the input field opens a dialog with the Image button (generates an image from the text typed in the input field, same as `/image`) and the Search checkbox (Google Search). A star on the button means that Search is on.

**Streaming** - by default the answer appears gradually while Gemini is writing it. To get the full answer at once, as in earlier versions, turn off the streaming checkbox in Settings and tap Save.

**Chat history** - every chat is saved automatically. Tap the menu button (three bars) to see saved chats and tap one to open it. Long-press a chat in the list to open, rename or delete it; the menu in the History screen has Clear all.

**Copy text** - long-press a message bubble to copy its text.

**New chat** - the New chat button in the menu starts an empty chat. The previous chat stays saved. Starting a new chat or opening another one is unavailable while a response is being generated.

**Android 1.x and Wolfius** - Android 1.x has no modern TLS, so Gemini cannot be reached without help. On the first launch on such a system the app shows a hint to install and enable [Wolfius](https://github.com/gohoski/Wolfius), which adds TLS 1.3 for Android 1.5-4.4 (root is recommended). Wolfius does not support Android 1.0 and 1.1.

## Project Structure

```
app/src/main/java/com/geminiapp/chat/
├── MainActivity.java          - chat screen, image picker, message list, error dialogs
├── GeminiApi.java             - all API calls (chat, image gen, model list)
├── ChatAdapter.java           - ListView adapter for chat bubbles, Markdown rendering, copy on long press
├── ChatDatabase.java          - SQLite storage for chats and messages
├── ChatHistoryActivity.java   - saved chats list (open, rename, delete)
├── SettingsActivity.java      - settings screen
├── SetupApiKeyActivity.java   - first-launch API key setup
├── SetupLanguageActivity.java - first-launch language picker
├── Prefs.java                 - SharedPreferences wrapper
├── PendingState.java          - static state that keeps a running request, input text and attached image across activity recreation
├── Base64Compat.java          - Base64 encoder/decoder that works on any Android version (android.util.Base64 appeared only in API 8)
└── Tls12SocketFactory.java    - TLS 1.2 patch for old Android versions
```

## Technical Notes

The project uses no external libraries. All HTTP communication is done with `HttpURLConnection`, JSON is parsed with `org.json`, and the UI is built entirely in code (no XML layouts used in the main activity). This keeps the APK small and the dependency tree empty.

TLS 1.2 is present but not enabled by default on Android 2.3-4.4. `Tls12SocketFactory` wraps the system `SSLSocketFactory` and forces TLS 1.2 negotiation so the app can reach the Gemini API on old devices. Android 1.x has no suitable TLS at all, so the app only shows a one-time hint about Wolfius there.

The request runs in a background thread and reports its result through `PendingState`. If the screen is rotated or the activity is recreated while the answer is being generated, the new `MainActivity` picks up the partial text or the final result from `PendingState` and continues from there.

Messages are written to SQLite by `ChatDatabase.syncMessages`, which counts the messages already stored for the chat and adds only the missing ones, so repeated saves never create duplicates.

Model replies are rendered by a small built-in Markdown parser in `ChatAdapter` (no external libraries). `Base64Compat` replaces `android.util.Base64`, which is not available before API 8.

Streaming uses the `streamGenerateContent?alt=sse` endpoint (Server-Sent Events); the response is read line by line and the message bubble is updated on every chunk.

Error handling: the HTTP code and status returned by the API are parsed in `GeminiApi.formatHttpError` and turned into a readable dialog by `MainActivity.showErrorDialog` (quota exhausted, rate limit, invalid key, permission denied, model not found, server overloaded, timeout, empty response and others). The request always goes to the model chosen in Settings; there is no automatic switching to another model.

Default model: `gemini-2.5-flash`. The image model is not fixed: the list of image models is discovered at runtime and you choose one each time you use `/image`.

Google Search: when the Search checkbox in Tools is ticked, the request gets `"tools": [{"google_search": {}}]`. The model decides by itself whether to search. Sources are taken from `groundingMetadata.groundingChunks` of the response (in streaming mode from every chunk, without duplicates) and appended to the end of the answer; the links are Google redirect links with the site name as the title. Search has a monthly free quota and is billed beyond it; a model without search support returns an error. Text is now collected from all response parts, not only the first one.

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

