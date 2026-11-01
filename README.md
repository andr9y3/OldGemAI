# OldGemAI

A lightweight Android chat app for talking with Google Gemini AI. Built in pure Java, runs on Android 1.0 and newer. The only dependency is SpongyCastle (used for TLS 1.2 on old Android versions); everything else is the plain Android SDK and the Gemini API.

## Features

- **Text chat** with full conversation history sent on every request
- **Image input** - attach a photo from your gallery and ask questions about it
- **Image generation** - type `/image <prompt>` to generate an image with Gemini; the app loads the list of image models and lets you pick one in a dialog (see below)
- **Streaming responses** - the answer is typed out gradually as Gemini generates it; can be turned off in Settings to get the full answer at once (the old behavior)
- **Chat history** - chats are saved to a local SQLite database; open, rename or delete them from the History screen
- **Copy and edit model answers** - under every text answer there are Copy and Edit icons next to the Regenerate icon (all icons are white); Edit opens a window where you can change the answer text, the corrected answer is saved to the chat history
- **Your messages** - hold one of your own messages to get a menu with Edit and Copy
- **Compact main screen** - one menu button (three bars) opens a screen with Settings, New chat, Back to chat and the list of saved chats; the single + button next to the input field opens a bottom panel with Photo, Image and the Search checkbox
- **Markdown in answers** - headings, bullet lists, quotes, tables, horizontal rules, **bold**, *italic*, ~~strikethrough~~, `inline code` and code blocks (black background) are rendered in the model's replies; `[text](url)` links and bare http(s) addresses are blue and clickable; `![alt](https://...)` images are downloaded and shown inline (tap an image to open it in the browser; if the download fails, a link is shown instead)
- **Clear error messages** - API errors (quota, invalid key, overloaded server, timeout and so on) are shown in a dialog with a short explanation and the original server message
- **State survives screen rotation** - the chat, the typed text, the attached image and a response that is still being generated are kept when the activity is recreated
- **Google Search grounding** - optional Search checkbox in Tools: the model can search the web and the answer ends with a numbered list of sources
- **Model selection** - loads the list of available Gemini models directly from the API so you always get the latest options
- **Configurable temperature** - adjust creativity from 0.0 to 2.0 via a slider
- **System prompt** - set a custom system instruction that applies to every message
- **Auto-update** - once a day the app checks the latest release on GitHub; if it is newer than the installed version, a window shows the current and the new version with Install (downloads the APK with a progress bar and the downloaded size), Cancel and a "Don't ask again" checkbox that turns auto-update off. Works on every supported Android version (the check and the download go through the built-in TLS 1.2 client on old devices)
- **Bilingual UI** - Russian and English, chosen on first launch
- **Minimal dependencies** - no Retrofit, no OkHttp, no Gson, no AndroidX; `HttpURLConnection` and `org.json`. The only library is SpongyCastle `core` (TLS 1.2 for Android 1.0-4.0)
- **Built-in TLS 1.2, no Wolfius or root needed** - on Android 1.0-4.0 (API 1-15) the app uses its own TLS 1.2 client written in pure Java (SpongyCastle) with a bundled Google root certificate; on Android 4.1-4.4 it turns on the system TLS 1.2 (`Tls12SocketFactory`); on Android 4.4W+ it works out of the box

## Requirements

- Android 1.0 (API 1) or higher; no add-ons, no root (see the Compatibility table at the bottom)
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

**Or do everything with one script.** In the project root there is `build.bat`. Run it (double-click or `build.bat` in a terminal) and it will:

1. ask for the keystore password and the key password (input is hidden; press Enter on the second question if it is the same as the first),
2. build the APK with the gradlew command above,
3. copy `my.keystore` and `uber-apk-signer.jar` into `app\build\outputs\apk`,
4. sign the APK.

The signed file is `app\build\outputs\apk\app-debug-aligned-signed.apk`. The script expects `my.keystore` in the project root and uses the alias `mykey` (change `ALIAS` at the top of the script if yours is different). If you do not have a keystore yet, create it once with the same command as above:

```
keytool -genkeypair -v -keystore my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
```

### 3. First launch

On first launch the app asks you to choose a language, then shows a page about auto-update (with a checkbox to turn it off), then walks you through entering your API key. You can skip the key step and add the key later in Settings.

## Usage

**Sending a message** - type in the input field and tap Send.

**Attaching an image** - tap the + button, choose Photo in the bottom panel and pick a photo from your gallery. The image is scaled to a maximum of 512 px on the long side and compressed (quality 60) before sending. A preview appears above the input field; tap the cross to remove it.

**Generating an image** - type `/image <description>` and tap Send. The app loads the model list from the API, keeps the models with `image` in the name and shows them in a selection dialog. The dialog warns that on the Free plan no model can generate images, and has a "Remember choice" checkbox at the bottom: if it is ticked, the picked model is saved and the next `/image` runs without asking. After you pick a model, the generated image is displayed inline in the chat. The Gemini API does not report whether a model is free, so if the chosen model does not work on your key (for example, no free quota), the error is shown and you can try another model.

**Settings** - tap the menu button (three bars) and then Settings. From there you can:
- Change your API key
- Refresh and pick a Gemini model
- Pick the model for image generation and tick "Remember choice" to skip the model dialog on `/image` (nothing is selected by default; while the box is ticked the model list is locked, untick the box to change the model)
- Set the language
- Adjust temperature
- Write a system prompt
- Turn streaming (gradual typing of the answer) on or off
- Turn smooth text appearance on or off (the streamed text is typed out smoothly instead of appearing in chunks; works only while streaming is on)
- Turn the waiting animation (three animated dots shown before the answer starts) on or off
- Turn auto-update on or off, or check for updates right now with the "Check for updates" button

**Tools** - the + button next to the input field opens a bottom panel with the Photo button (pick a picture from the gallery), the Image button (generates an image from the text typed in the input field, same as `/image`) and the Search checkbox (Google Search). A star on the + button (+*) means that Search is on.

**Streaming** - by default the answer appears gradually while Gemini is writing it. To get the full answer at once, as in earlier versions, turn off the streaming checkbox in Settings and tap Save.

**Chat history** - every chat is saved automatically. Tap the menu button (three bars) to see saved chats and tap one to open it. Long-press a chat in the list to open, rename or delete it; the menu in the History screen has Clear all.

**Copy text** - tap the Copy icon under a model answer to copy it. To copy one of your own messages, long-press it and choose Copy.

**Edit a model answer** - tap the pencil icon under the answer, change the text in the window and tap Save. The messages after it stay as they are; the model sees the changed answer in the history on the next request. Unavailable while a response is being generated.

**New chat** - the New chat button in the menu starts an empty chat. The previous chat stays saved. Starting a new chat or opening another one is unavailable while a response is being generated.

**Old Android (1.0-4.0)** - the app talks to Gemini through its own TLS 1.2 client, so nothing has to be installed. The first request can take a few seconds on slow phones (the TLS handshake is done in Java). Pictures from `![](https://...)` links inside answers are not downloaded on Android older than 4.1 (the link is shown instead).

## Project Structure

```
app/src/main/java/com/geminiapp/chat/
├── MainActivity.java          - chat screen, image picker, message list, error dialogs
├── GeminiApi.java             - all API calls (chat, image gen, model list)
├── ChatAdapter.java           - ListView adapter for chat bubbles, Markdown rendering (text, code, links, tables, inline images with a small in-memory cache), icons under answers (regenerate, copy, edit), menu on long press for your own messages
├── ChatDatabase.java          - SQLite storage for chats and messages
├── ChatHistoryActivity.java   - saved chats list (open, rename, delete)
├── SettingsActivity.java      - settings screen
├── SetupApiKeyActivity.java   - first-launch API key setup
├── SetupLanguageActivity.java - first-launch language picker
├── SetupUpdateActivity.java   - first-launch page about auto-update with a checkbox to turn it off
├── Updater.java               - auto-update: daily check of the latest GitHub release, update window, APK download with progress, install
├── UpdateFileProvider.java    - hands the downloaded APK to the system installer on Android 7+ (no androidx FileProvider)
├── Prefs.java                 - SharedPreferences wrapper
├── PendingState.java          - static state that keeps a running request, input text and attached image across activity recreation
├── Base64Compat.java          - Base64 encoder/decoder that works on any Android version (android.util.Base64 appeared only in API 8)
├── TypingDotsView.java        - animated three dots shown while the answer has not started yet (drawn by hand, works on any Android version)
├── Tls12SocketFactory.java    - turns on system TLS 1.2 on Android 4.1-4.4
├── Http.java                  - one entry point for HTTPS requests: HttpURLConnection on API 16+, own HTTP/1.1 over LegacyTls on API 1-15
├── LegacyTls.java             - TLS 1.2 client in pure Java (SpongyCastle) with certificate chain and hostname checks
└── Roots.java                 - bundled root certificate (GTS Root R1) used by LegacyTls
```

## Technical Notes

The project uses one library, SpongyCastle `core`, only for TLS on old Android. HTTP communication on Android 4.1+ is done with `HttpURLConnection`, JSON is parsed with `org.json`, and the UI is built entirely in code (no XML layouts used in the main activity).

TLS: Android 1.0-4.0 cannot do TLS 1.2 (and has no Google root certificates), so `Http` sends requests through `LegacyTls`: a TLS 1.2 client from the SpongyCastle lightweight API running over a plain `Socket`, with a small built-in HTTP/1.1 client (chunked responses and streaming included). It offers only RSA-based cipher suites (ECDHE-RSA and RSA with AES-GCM, RSA with AES-CBC-SHA256), checks the certificate chain up to the bundled GTS Root R1, the host name (SAN) and the validity dates (skipped if the device clock is before 2020), and requires CA flags on intermediate certificates. On Android 4.1-4.4 `Tls12SocketFactory` forces the system TLS 1.2 instead. If Google changes its root certificate, add the new one to `Roots.java`.

The request runs in a background thread and reports its result through `PendingState`. If the screen is rotated or the activity is recreated while the answer is being generated, the new `MainActivity` picks up the partial text or the final result from `PendingState` and continues from there.

Messages are written to SQLite by `ChatDatabase.syncMessages`, which counts the messages already stored for the chat and adds only the missing ones, so repeated saves never create duplicates.

Model replies are rendered by a small built-in Markdown parser in `ChatAdapter` (no external libraries). `Base64Compat` replaces `android.util.Base64`, which is not available before API 8.

Streaming uses the `streamGenerateContent?alt=sse` endpoint (Server-Sent Events); the response is read line by line and the message bubble is updated on every chunk.

Error handling: the HTTP code and status returned by the API are parsed in `GeminiApi.formatHttpError` and turned into a readable dialog by `MainActivity.showErrorDialog` (quota exhausted, rate limit, invalid key, permission denied, model not found, server overloaded, timeout, empty response and others). The request always goes to the model chosen in Settings; there is no automatic switching to another model.

Default model: `gemini-flash-lite-latest`. The image model is not fixed: the list of image models is discovered at runtime and you choose one each time you use `/image`.

Google Search: when the Search checkbox in Tools is ticked, the request gets `"tools": [{"google_search": {}}]`. The model decides by itself whether to search. Sources are taken from `groundingMetadata.groundingChunks` of the response (in streaming mode from every chunk, without duplicates) and appended to the end of the answer; the links are Google redirect links with the site name as the title. Search has a monthly free quota and is billed beyond it; a model without search support returns an error. Text is now collected from all response parts, not only the first one.

The model list request uses `pageSize=1000` so that image models are not cut off by the default page size.

The API key is sent in the `x-goog-api-key` request header, as Google's REST reference recommends, and never as a `?key=` URL parameter, so it does not end up in URLs, proxy logs or exception messages.

### Auto-update

`Updater` finds the latest version without the GitHub API: `https://github.com/andr9y3/OldGemAI/releases/latest` redirects to `.../releases/tag/<tag>`, and the tag is compared with the installed `versionName` number by number (`2.10` is newer than `2.9`; a leading `v` and a suffix like `-beta` are ignored). The check runs at launch no more than once per 24 hours, only if auto-update is on, and the time is saved only after a successful check. The APK is downloaded from `https://github.com/andr9y3/OldGemAI/releases/download/<tag>/app-debug-aligned-signed.apk`, so every release must contain a file with exactly this name, and its tag must be higher than the `versionName` of the build you want to update from.

The state of the update window (versions, phase, downloaded bytes) is kept in static fields of `Updater`, not in an Activity. When the screen is rotated, the window is removed in `onDestroy` (`Updater.detach`) and put back in `onCreate` of the new Activity (`Updater.attach`) with the current progress, while the download thread keeps running. If the user closes the screen (back button), the download is cancelled.

The file is saved in the app's private storage as `update.apk`. Before Android 7 the installer reads it directly (the file is world-readable); from Android 7 `UpdateFileProvider` serves it through a `content://` address. From Android 8 the user has to allow installs from this app once (the app opens the system screen for that). The update is signed with the same key as the installed app, otherwise Android refuses to install it over the old version.

## Privacy and Security

- The API key is stored in the app's private SharedPreferences in plain text. Android Keystore is not available on the old Android versions this app targets, so encryption is not used; the app sandbox is the only protection.
- Chats (including attached and generated images) are stored unencrypted in a local SQLite database inside the app's private storage.
- Android backup is disabled (`allowBackup="false"`), so the key and chats are not copied to cloud or adb backups.
- The whole conversation history, including images, is sent to Google's servers with every request.
- If you use a rooted device, other apps with root access can read the key and chats.

## Screenshots

<img width="480" height="800" alt="изображение" src="https://github.com/user-attachments/assets/b389c7e8-835a-40e1-8244-34ba7019b166" />
<img width="480" height="800" alt="изображение" src="https://github.com/user-attachments/assets/1e380a39-3e53-448c-8b16-172d61b64cc2" />
<img width="480" height="800" alt="изображение" src="https://github.com/user-attachments/assets/d977ee37-8522-46f5-a79b-612312d7f925" />
<img width="480" height="800" alt="изображение" src="https://github.com/user-attachments/assets/91b3516d-662f-4f5a-83b4-6a5c7afc32c1" />
<img width="1078" height="6104" alt="Screenshot_20261030_210721_OldGemAI" src="https://github.com/user-attachments/assets/82c83b3f-0a3d-4e7b-a553-7efa4393f5e5" />

## Reporting Bugs

Telegram: [@ialwaysloveyou0](https://t.me/ialwaysloveyou0)

## License

MIT

## Compatibility

What works on which Android version. The table is based on the app code and the official Android SSL documentation (TLS 1.2 is supported from API 16 and enabled by default from API 20); not every combination was tested on real devices.

Legend: ✅ works out of the box, 🟡 works through the built-in TLS 1.2 client (slower first request), ⚠️ code is written for it but not tested on a real device.

| Section | Android 1.0-2.2 (API 1-8) | Android 2.3-4.0.4 (API 9-15) | Android 4.1-4.4.4 (API 16-19) | Android 5.0+ (API 20+) |
|---|---|---|---|---|
| App install and launch | ✅ | ✅ | ✅ | ✅ |
| First-launch setup (language, API key) | ✅ | ✅ | ✅ | ✅ |
| Chat screen, menu, settings | ✅ | ✅ | ✅ | ✅ |
| Saving chats (history, rename, delete) | ✅ | ✅ | ✅ | ✅ |
| Saving settings and API key | ✅ | ✅ | ✅ | ✅ |
| Markdown rendering in answers | ✅ | ✅ | ✅ | ✅ |
| Copy and edit answers and your own messages | ✅ | ✅ | ✅ | ✅ |
| Waiting animation, smooth text, icons | ✅ | ✅ | ✅ | ✅ |
| Attaching a photo from the gallery | ✅ | ✅ | ✅ | ✅ |
| Text chat with Gemini (streaming, model list, Google Search) | 🟡⚠️ | 🟡 | ✅ | ✅ |
| Image generation (`/image`, paid plan needed) | 🟡⚠️ | 🟡 | ✅ | ✅ |
| Images inside answers (Markdown `![](https://...)`) | link only | link only | ✅ | ✅ |

Notes:

- Everything that does not touch the network (launch, UI, history, settings, Markdown, copy/edit) is drawn and stored by the app itself, so it works the same on all supported versions, even offline.
- Android 2.3 and newer used to reach Gemini through the system TLS; since version 2.0 all versions below 4.1 use the built-in TLS 1.2 client, which has not been tested on a real device yet.
- Android 1.0-1.5 devices have only one screen density, so the app icon there may be the default one.
- On Android 1.5 (API 3) the icons under answers have no accessibility labels, because the system supports them only from API 4. The icons themselves work.

### Tested devices

The app was tested on real devices:

- Android 2.3 - Samsung Galaxy Ace (GT-S5830i)
- Android 4.4.2 - Samsung Galaxy S3 mini (GT-I8190)

If the app works on another Android version older than 5.0, write to Telegram [@ialwaysloveyou0](https://t.me/ialwaysloveyou0) with the Android version and the device model, and it will be added to this list.


I'm only 13 years old and I used AI to make this. Go easy on me pls
