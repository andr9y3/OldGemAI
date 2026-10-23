package com.geminiapp.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements PendingState.Listener {

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int REQUEST_HISTORY = 1002;
    private static final int MAX_IMAGE_SIZE = 512;
    private static final int JPEG_QUALITY = 60;

    private Prefs prefs;
    private List<GeminiApi.Message> messages;
    private ChatAdapter adapter;
    private GeminiApi geminiApi;
    private Handler mainHandler;
    private ChatDatabase chatDb;

    private ListView lvChat;
    private EditText etMessage;
    private Button btnSend;
    private Button btnAttach;
    private Button btnTools;
    private LinearLayout topBar;
    private Button btnMenu;

    private TextView tvNoKeyBanner;
    private ImageView ivPreview;
    private LinearLayout attachPreviewLayout;

    private String pendingImageBase64 = null;
    private String pendingImageMime = "image/jpeg";
    private long currentChatId = -1;
    private boolean pendingResponse = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        geminiApi = new GeminiApi();
        mainHandler = new Handler(Looper.getMainLooper());
        chatDb = new ChatDatabase(this);
        messages = new ArrayList<GeminiApi.Message>();

        currentChatId = PendingState.chatId;
        pendingImageBase64 = PendingState.pendingImageBase64;
        pendingImageMime = PendingState.pendingImageMime;
        pendingResponse = PendingState.pending;

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        btnMenu = new Button(this);
        btnMenu.setText("\u2630");
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mainLayout.addView(topBar);
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, ChatHistoryActivity.class), REQUEST_HISTORY);
            }
        });

        tvNoKeyBanner = new TextView(this);
        tvNoKeyBanner.setBackgroundColor(0xFFFFCCCC);
        tvNoKeyBanner.setTextColor(0xFF000000);
        tvNoKeyBanner.setPadding(15, 15, 15, 15);
        mainLayout.addView(tvNoKeyBanner);

        lvChat = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1.0f);
        mainLayout.addView(lvChat, listParams);

        attachPreviewLayout = new LinearLayout(this);
        attachPreviewLayout.setOrientation(LinearLayout.HORIZONTAL);
        attachPreviewLayout.setPadding(10, 4, 10, 4);
        attachPreviewLayout.setBackgroundColor(0xFF222222);
        attachPreviewLayout.setVisibility(View.GONE);

        ivPreview = new ImageView(this);
        ivPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(120), dp(120));
        previewParams.setMargins(0, 0, 10, 0);
        attachPreviewLayout.addView(ivPreview, previewParams);

        Button btnRemoveImage = new Button(this);
        btnRemoveImage.setText("✕");
        attachPreviewLayout.addView(btnRemoveImage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainLayout.addView(attachPreviewLayout);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setPadding(10, 10, 10, 10);

        btnAttach = new Button(this);
        btnAttach.setText("+");
        inputLayout.addView(btnAttach, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        btnTools = new Button(this);
        btnTools.setText("Инструменты");
        inputLayout.addView(btnTools, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        etMessage = new EditText(this);
        etMessage.setHint("...");
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        inputLayout.addView(etMessage, editParams);

        btnSend = new Button(this);
        inputLayout.addView(btnSend, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainLayout.addView(inputLayout);
        setContentView(mainLayout);

        adapter = new ChatAdapter(this, messages);
        lvChat.setAdapter(adapter);
        showLegacyTlsHint();

        updateBanner();

        restoreFromPendingState(savedInstanceState);

        PendingState.listener = this;

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendMessage();
            }
        });

        btnAttach.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("image/*");
                startActivityForResult(intent, REQUEST_PICK_IMAGE);
            }
        });

        btnTools.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pendingResponse) {
                    boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
                    Toast.makeText(MainActivity.this, ru ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
                    return;
                }
                showToolsDialog();
            }
        });

        btnRemoveImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingImageBase64 = null;
                pendingImageMime = "image/jpeg";
                PendingState.clearImage();
                attachPreviewLayout.setVisibility(View.GONE);
            }
        });
    }

    private void restoreFromPendingState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            String input = savedInstanceState.getString("input_text");
            if (input != null) etMessage.setText(input);
        } else if (PendingState.inputText != null) {
            etMessage.setText(PendingState.inputText);
        }

        if (pendingImageBase64 != null && pendingImageBase64.length() > 0) {
            try {
                byte[] bytes = Base64Compat.decode(pendingImageBase64);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ivPreview.setImageBitmap(bmp);
                    attachPreviewLayout.setVisibility(View.VISIBLE);
                }
            } catch (Exception ignored) {
                pendingImageBase64 = null;
                PendingState.clearImage();
            }
        }

        if (PendingState.error != null) {
            final String err = PendingState.error;
            PendingState.clearResponse();
            pendingResponse = false;
            btnSend.setEnabled(true);
            btnAttach.setEnabled(true);
            loadMessagesFromDb();
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showErrorDialog(err);
                }
            });
            return;
        }

        if (PendingState.finalImageBase64 != null) {
            String img = PendingState.finalImageBase64;
            PendingState.clearResponse();
            pendingResponse = false;
            btnSend.setEnabled(true);
            btnAttach.setEnabled(true);
            loadMessagesFromDb();
            messages.add(new GeminiApi.Message("model", "", img, true));
            adapter.notifyDataSetChanged();
            lvChat.setSelection(messages.size() - 1);
            saveNewMessages();
            return;
        }

        if (PendingState.finalResponse != null) {
            String resp = PendingState.finalResponse;
            PendingState.clearResponse();
            pendingResponse = false;
            btnSend.setEnabled(true);
            btnAttach.setEnabled(true);
            loadMessagesFromDb();
            messages.add(new GeminiApi.Message("model", resp));
            adapter.notifyDataSetChanged();
            lvChat.setSelection(messages.size() - 1);
            saveNewMessages();
            return;
        }

        if (PendingState.pending) {
            pendingResponse = true;
            btnSend.setEnabled(false);
            btnAttach.setEnabled(false);
            loadMessagesFromDb();
            String soFar = PendingState.partialText != null ? PendingState.partialText : "";
            if (soFar.length() == 0) soFar = "...";
            messages.add(new GeminiApi.Message("model", soFar));
            adapter.notifyDataSetChanged();
            lvChat.setSelection(messages.size() - 1);
            return;
        }

        loadMessagesFromDb();
    }

    private void loadMessagesFromDb() {
        messages.clear();
        if (currentChatId != -1 && chatDb.chatExists(currentChatId)) {
            messages.addAll(chatDb.getChatMessages(currentChatId));
        }
        adapter.notifyDataSetChanged();
        if (messages.size() > 0) {
            lvChat.setSelection(messages.size() - 1);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        PendingState.chatId = currentChatId;
        PendingState.pendingImageBase64 = pendingImageBase64;
        PendingState.pendingImageMime = pendingImageMime;
        PendingState.inputText = etMessage != null ? etMessage.getText().toString() : "";
        outState.putString("input_text", PendingState.inputText);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (PendingState.listener == this) {
            PendingState.listener = null;
        }
        if (isFinishing()) {
            PendingState.clearAll();
        }
    }

    @Override
    public void onPendingModelUsed(String model) {
    }

    @Override
    public void onPendingPartial(String textSoFar) {
        if (!pendingResponse || messages.size() == 0) return;
        messages.set(messages.size() - 1, new GeminiApi.Message("model", textSoFar));
        adapter.notifyDataSetChanged();
        lvChat.setSelection(messages.size() - 1);
    }

    @Override
    public void onPendingSuccess(String text) {
        if (messages.size() > 0) {
            messages.set(messages.size() - 1, new GeminiApi.Message("model", text));
            adapter.notifyDataSetChanged();
            lvChat.setSelection(messages.size() - 1);
        }
        btnSend.setEnabled(true);
        btnAttach.setEnabled(true);
        pendingResponse = false;
        saveNewMessages();
        PendingState.clearResponse();
    }

    @Override
    public void onPendingImage(String base64png) {
        if (messages.size() > 0) {
            messages.set(messages.size() - 1, new GeminiApi.Message("model", "", base64png, true));
        }
        adapter.notifyDataSetChanged();
        lvChat.setSelection(messages.size() - 1);
        btnSend.setEnabled(true);
        btnAttach.setEnabled(true);
        pendingResponse = false;
        saveNewMessages();
        PendingState.clearResponse();
    }

    @Override
    public void onPendingError(String error) {
        pendingResponse = false;
        if (messages.size() > 0) {
            messages.remove(messages.size() - 1);
            adapter.notifyDataSetChanged();
        }
        btnSend.setEnabled(true);
        btnAttach.setEnabled(true);
        showErrorDialog(error);
        PendingState.clearResponse();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_HISTORY && resultCode == RESULT_OK && data != null) {
            if (data.getBooleanExtra("new_chat", false)) {
                clearChat();
                return;
            }
            long chatId = data.getLongExtra("chat_id", -1);
            if (chatId != -1) {
                if (pendingResponse) {
                    boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
                    Toast.makeText(this, ru ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
                } else {
                    loadChatFromHistory(chatId);
                }
            }
            return;
        }

        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;

                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "image/jpeg";
                pendingImageMime = mime;

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, opts);
                is.close();

                is = getContentResolver().openInputStream(uri);
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = calculateInSampleSize(opts, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE);
                Bitmap original = BitmapFactory.decodeStream(is, null, opts);
                is.close();

                if (original == null) {
                    Toast.makeText(this, (Prefs.LANG_RU.equals(prefs.getLanguage()) ? "Не удалось прочитать изображение" : "Could not read the image"), Toast.LENGTH_SHORT).show();
                    return;
                }

                Bitmap scaled = scaleBitmap(original);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Bitmap.CompressFormat fmt = mime.contains("png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                scaled.compress(fmt, JPEG_QUALITY, baos);
                byte[] bytes = baos.toByteArray();
                pendingImageBase64 = Base64Compat.encode(bytes);
                PendingState.pendingImageBase64 = pendingImageBase64;
                PendingState.pendingImageMime = pendingImageMime;

                ivPreview.setImageBitmap(scaled);
                attachPreviewLayout.setVisibility(View.VISIBLE);

            } catch (Exception e) {
                Toast.makeText(this, (Prefs.LANG_RU.equals(prefs.getLanguage()) ? "Ошибка: " : "Error: ") + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void loadChatFromHistory(long chatId) {
        List<GeminiApi.Message> loaded = chatDb.getChatMessages(chatId);
        messages.clear();
        messages.addAll(loaded);
        adapter.notifyDataSetChanged();
        lvChat.setSelection(Math.max(0, messages.size() - 1));
        currentChatId = chatId;
        pendingResponse = false;
        PendingState.chatId = chatId;
        PendingState.clearResponse();
    }

    private void saveNewMessages() {
        if (messages.isEmpty()) return;

        if (currentChatId != -1 && !chatDb.chatExists(currentChatId)) {
            currentChatId = -1;
        }
        createNewChatIfNeeded();
        if (currentChatId == -1) return;

        int end = messages.size();
        if (pendingResponse) end--;

        chatDb.syncMessages(currentChatId, messages, end);
        PendingState.chatId = currentChatId;
    }

    private void createNewChatIfNeeded() {
        if (currentChatId == -1 && !messages.isEmpty()) {
            String preview = messages.get(0).text != null && messages.get(0).text.length() > 0
                ? messages.get(0).text.substring(0, Math.min(50, messages.get(0).text.length()))
                : (Prefs.LANG_RU.equals(prefs.getLanguage()) ? "Чат" : "Chat");
            currentChatId = chatDb.createChat(preview);
            PendingState.chatId = currentChatId;
        }
    }

    private Bitmap scaleBitmap(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_IMAGE_SIZE && h <= MAX_IMAGE_SIZE) return src;
        float scale = MAX_IMAGE_SIZE / (float) Math.max(w, h);
        int nw = Math.round(w * scale);
        int nh = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBanner();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNewMessages();
        PendingState.chatId = currentChatId;
        PendingState.pendingImageBase64 = pendingImageBase64;
        PendingState.pendingImageMime = pendingImageMime;
        PendingState.inputText = etMessage != null ? etMessage.getText().toString() : "";
    }

    private void updateBanner() {
        boolean hasKey = !TextUtils.isEmpty(prefs.getApiKey());
        tvNoKeyBanner.setVisibility(hasKey ? View.GONE : View.VISIBLE);

        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        tvNoKeyBanner.setText(isRu
            ? "API ключ не задан - ИИ не будет работать. Добавьте ключ в настройках."
            : "No API key set - AI will not work. Add your key in Settings.");

        btnSend.setText(isRu ? "Отправить" : "Send");
        updateToolsLabel();


        etMessage.setHint(isRu ? "Сообщение..." : "Message...");
        setTitle(isRu ? "OldGemAI" : "OldGemAI");
    }

    private void sendMessage() {
        final String text = etMessage.getText().toString().trim();
        final boolean hasImage = pendingImageBase64 != null;

        if (TextUtils.isEmpty(text) && !hasImage) return;

        final String apiKey = prefs.getApiKey();
        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());

        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, isRu ? "Сначала добавьте API ключ в настройках" : "Please add an API key in Settings", Toast.LENGTH_LONG).show();
            return;
        }

        if (text.toLowerCase().startsWith("/image ") || text.toLowerCase().equals("/image")) {
            final String prompt = text.length() > 7 ? text.substring(7).trim() : "";
            if (prompt.length() == 0) {
                Toast.makeText(this, isRu ? "Укажите описание: /image кот в космосе" : "Add a description: /image cat in space", Toast.LENGTH_SHORT).show();
                return;
            }
            etMessage.setText("");
            String savedImageModel = prefs.getImageModel();
            if (prefs.isImageRemember() && savedImageModel.length() > 0) {
                startImageGeneration(prompt, savedImageModel, isRu, apiKey);
                return;
            }
            Toast.makeText(this, isRu ? "Загрузка списка моделей..." : "Loading models list...", Toast.LENGTH_SHORT).show();
            
            // Запрашиваем список моделей и показываем диалог
            geminiApi.listModels(apiKey, new GeminiApi.ModelsCallback() {
                @Override
                public void onSuccess(final List<String> models) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            final List<String> imageModels = new ArrayList<String>();
                            for (String m : models) {
                                if (m.contains("image")) {
                                    imageModels.add(m);
                                }
                            }
                            if (imageModels.isEmpty()) {
                                Toast.makeText(MainActivity.this, isRu ? "Модели для картинок не найдены" : "No image models found", Toast.LENGTH_LONG).show();
                                return;
                            }
                            
                            // Окно выбора: предупреждение, список моделей, галочка запоминания
                            LinearLayout dlgLayout = new LinearLayout(MainActivity.this);
                            dlgLayout.setOrientation(LinearLayout.VERTICAL);
                            dlgLayout.setPadding(dp(12), dp(8), dp(12), dp(8));

                            TextView tvWarn = new TextView(MainActivity.this);
                            tvWarn.setText(isRu
                                ? "Внимание: на бесплатном тарифе (Free) ни одна модель не может генерировать картинки. Нужен платный тариф."
                                : "Warning: on the Free plan no model can generate images. A paid plan is required.");
                            tvWarn.setTextColor(0xFFFF8800);
                            tvWarn.setPadding(0, 0, 0, dp(8));
                            dlgLayout.addView(tvWarn);

                            ListView lvModels = new ListView(MainActivity.this);
                            lvModels.setAdapter(new ArrayAdapter<String>(MainActivity.this,
                                android.R.layout.simple_list_item_1, imageModels));
                            dlgLayout.addView(lvModels, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.FILL_PARENT, dp(220)));

                            final CheckBox cbRemember = new CheckBox(MainActivity.this);
                            cbRemember.setText(isRu ? "Запомнить выбор" : "Remember choice");
                            cbRemember.setChecked(prefs.isImageRemember());
                            dlgLayout.addView(cbRemember);

                            final AlertDialog dlg = new AlertDialog.Builder(MainActivity.this)
                                .setTitle(isRu ? "Выберите модель для картинки" : "Select image model")
                                .setView(dlgLayout)
                                .setNegativeButton(isRu ? "Отмена" : "Cancel", null)
                                .create();

                            lvModels.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                                @Override
                                public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                                    String selectedModel = imageModels.get(position);
                                    boolean remember = cbRemember.isChecked();
                                    prefs.setImageRemember(remember);
                                    if (remember) prefs.setImageModel(selectedModel);
                                    dlg.dismiss();
                                    startImageGeneration(prompt, selectedModel, isRu, apiKey);
                                }
                            });
                            dlg.show();
                        }
                    });
                }

                @Override
                public void onError(final String error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showErrorDialog(error);
                        }
                    });
                }
            });
            return;
        }

        final String imageBase64 = pendingImageBase64;
        final String imageMime = pendingImageMime;

        GeminiApi.Message userMsg = hasImage
            ? new GeminiApi.Message("user", text, imageBase64, imageMime)
            : new GeminiApi.Message("user", text);
        messages.add(userMsg);
        adapter.notifyDataSetChanged();
        lvChat.setSelection(messages.size() - 1);
        etMessage.setText("");

        pendingImageBase64 = null;
        pendingImageMime = "image/jpeg";
        PendingState.clearImage();
        attachPreviewLayout.setVisibility(View.GONE);

        messages.add(new GeminiApi.Message("model", "..."));
        pendingResponse = true;
        adapter.notifyDataSetChanged();

        btnSend.setEnabled(false);
        btnAttach.setEnabled(false);

        final List<GeminiApi.Message> historyCopy = new ArrayList<GeminiApi.Message>();
        for (int i = 0; i < messages.size() - 1; i++) {
            historyCopy.add(messages.get(i));
        }

        PendingState.pending = true;
        PendingState.partialText = "";
        PendingState.finalResponse = null;
        PendingState.finalImageBase64 = null;
        PendingState.error = null;
        PendingState.pendingModel = null;

        geminiApi.sendMessage(
            apiKey,
            prefs.getModel(),
            prefs.getTemperature(),
            prefs.getSystemPrompt(),
            historyCopy,
            prefs.isStream(),
            prefs.isSearch(),
            isRu ? "Источники" : "Sources",
            new TextSendCallback()
        );
    }

    private void startImageGeneration(String prompt, String selectedModel, boolean isRu, String apiKey) {
        messages.add(new GeminiApi.Message("user", "/image " + prompt));
        messages.add(new GeminiApi.Message("model", isRu ? "Генерация картинки..." : "Generating image..."));
        adapter.notifyDataSetChanged();
        lvChat.setSelection(messages.size() - 1);
        btnSend.setEnabled(false);
        btnAttach.setEnabled(false);
        pendingResponse = true;

        PendingState.pending = true;
        PendingState.partialText = "";
        PendingState.finalResponse = null;
        PendingState.finalImageBase64 = null;
        PendingState.error = null;
        PendingState.pendingModel = selectedModel;

        geminiApi.generateImage(apiKey, selectedModel, prompt, new ImageSendCallback());
    }

    private static class TextSendCallback implements GeminiApi.StreamCallback {
        @Override
        public void onPartial(final String textSoFar) {
            PendingState.partialText = textSoFar;
            PendingState.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    PendingState.Listener l = PendingState.listener;
                    if (l != null) l.onPendingPartial(textSoFar);
                }
            });
        }

        @Override
        public void onSuccess(final String text) {
            PendingState.partialText = text;
            PendingState.finalResponse = text;
            PendingState.pending = false;
            PendingState.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    PendingState.Listener l = PendingState.listener;
                    if (l != null) l.onPendingSuccess(text);
                }
            });
        }

        @Override
        public void onError(final String error) {
            PendingState.error = error;
            PendingState.pending = false;
            PendingState.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    PendingState.Listener l = PendingState.listener;
                    if (l != null) l.onPendingError(error);
                }
            });
        }
    }

    private static class ImageSendCallback implements GeminiApi.ImageCallback {
        @Override
        public void onSuccess(final String base64png) {
            PendingState.finalImageBase64 = base64png;
            PendingState.pending = false;
            PendingState.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    PendingState.Listener l = PendingState.listener;
                    if (l != null) l.onPendingImage(base64png);
                }
            });
        }

        @Override
        public void onError(final String error) {
            PendingState.error = error;
            PendingState.pending = false;
            PendingState.MAIN.post(new Runnable() {
                @Override
                public void run() {
                    PendingState.Listener l = PendingState.listener;
                    if (l != null) l.onPendingError(error);
                }
            });
        }
    }

    private void showErrorDialog(String rawError) {
        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());

        String title;
        String hint;
        String serverMsg;

        int code = 0;
        String status = "";
        String message = rawError != null ? rawError : "";

        if (message.startsWith("[")) {
            int close = message.indexOf(']');
            if (close > 1) {
                String head = message.substring(1, close);
                int bar = head.indexOf('|');
                if (bar >= 0) {
                    try { code = Integer.parseInt(head.substring(0, bar).trim()); } catch (Exception ignored) {}
                    status = head.substring(bar + 1).trim();
                } else {
                    try { code = Integer.parseInt(head.trim()); } catch (Exception ignored) {}
                }
                message = message.substring(close + 1).trim();
            }
        }
        serverMsg = message;

        String lowerStatus = status.toUpperCase();
        String lowerMsg = serverMsg.toLowerCase();

        if (code == 429 || lowerStatus.equals("RESOURCE_EXHAUSTED")) {
            if (lowerMsg.contains("quota") || lowerMsg.contains("daily")) {
                title = isRu ? "Кончилась квота" : "Quota exhausted";
                hint = isRu
                    ? "Достигнут лимит запросов/токенов. Подождите или смените API-ключ/тариф."
                    : "Request/token quota reached. Wait or use another API key/tier.";
            } else {
                title = isRu ? "Слишком много запросов" : "Too many requests";
                hint = isRu
                    ? "Превышен лимит запросов в минуту. Подождите немного."
                    : "Rate limit exceeded. Please wait a bit.";
            }
        } else if (code == 401 || lowerStatus.equals("UNAUTHENTICATED")) {
            title = isRu ? "Неверный API-ключ" : "Invalid API key";
            hint = isRu ? "Проверьте ключ в настройках." : "Check the key in Settings.";
        } else if (code == 403 || lowerStatus.equals("PERMISSION_DENIED")) {
            title = isRu ? "Доступ запрещён" : "Permission denied";
            hint = isRu
                ? "У ключа нет прав на эту модель, либо Gemini API не включён в проекте."
                : "Key has no access to this model, or Gemini API is not enabled for the project.";
        } else if (code == 402 || lowerStatus.equals("PAYMENT_REQUIRED")) {
            title = isRu ? "Требуется оплата" : "Payment required";
            hint = isRu ? "Проверьте баланс и привязанную карту." : "Check balance and billing.";
        } else if (code == 404 || lowerStatus.equals("NOT_FOUND") || lowerStatus.equals("MODEL_NOT_FOUND")) {
            title = isRu ? "Модель не найдена" : "Model not found";
            hint = isRu
                ? "Модель не существует или устарела. Выберите другую в настройках."
                : "Model does not exist or is deprecated. Choose another in Settings.";
        } else if (code == 400 && lowerStatus.equals("FAILED_PRECONDITION")) {
            title = isRu ? "Предусловие не выполнено" : "Precondition failed";
            hint = isRu
                ? "Бесплатный тариф недоступен в вашей стране, либо не включён биллинг."
                : "Free tier unavailable in your country, or billing is not enabled.";
        } else if (code == 400 || lowerStatus.equals("INVALID_ARGUMENT")) {
            title = isRu ? "Неверный запрос" : "Invalid request";
            hint = isRu
                ? "Проверьте параметры: возможно, превышен лимит токенов или неверное значение."
                : "Check parameters: token limit may be exceeded or a value is invalid.";
        } else if (code == 409 || lowerStatus.equals("ALREADY_EXISTS") || lowerStatus.equals("ABORTED")) {
            title = isRu ? "Конфликт" : "Conflict";
            hint = isRu ? "Операция отменена или конфликтует с другой." : "Operation aborted or conflicts with another.";
        } else if (code == 416 || lowerStatus.equals("OUT_OF_RANGE")) {
            title = isRu ? "Вне диапазона" : "Out of range";
            hint = isRu ? "Запрошен недопустимый диапазон значений." : "Requested an invalid value range.";
        } else if (code == 499 || lowerStatus.equals("CANCELLED")) {
            title = isRu ? "Запрос отменён" : "Request cancelled";
            hint = isRu ? "Соединение было прервано." : "The connection was interrupted.";
        } else if (code == 501 || lowerStatus.equals("UNIMPLEMENTED")) {
            title = isRu ? "Не поддерживается" : "Unimplemented";
            hint = isRu ? "Эта функция ещё не поддерживается API." : "This feature is not yet supported by the API.";
        } else if (code == 500 || lowerStatus.equals("INTERNAL") || lowerStatus.equals("UNKNOWN")) {
            title = isRu ? "Внутренняя ошибка сервера" : "Server internal error";
            hint = isRu ? "Сбой на стороне Google. Попробуйте ещё раз." : "Google-side failure. Try again.";
        } else if (code == 502) {
            title = isRu ? "Плохой шлюз" : "Bad gateway";
            hint = isRu ? "Проблема на стороне шлюза. Повторите попытку." : "Gateway-side issue. Retry.";
        } else if (code == 503 || lowerStatus.equals("SERVICE_UNAVAILABLE")) {
            title = isRu ? "Сервис недоступен" : "Service unavailable";
            hint = isRu ? "Сервер перегружен или на обслуживании." : "Server overloaded or under maintenance.";
        } else if (code == 504 || lowerStatus.equals("DEADLINE_EXCEEDED")) {
            title = isRu ? "Превышено время ожидания" : "Deadline exceeded";
            hint = isRu ? "Сервер не успел ответить. Попробуйте ещё раз." : "Server didn't respond in time. Try again.";
        } else if (code == 0) {
            if (lowerMsg.contains("timed out") || lowerMsg.contains("timeout")) {
                title = isRu ? "Таймаут" : "Timeout";
                hint = isRu ? "Превышено время ожидания ответа от сервера." : "Server response timed out.";
            } else if (lowerMsg.contains("no image in response")) {
                title = isRu ? "Модель не вернула картинку" : "Model returned no image";
                hint = isRu ? "Модель ответила без изображения." : "The model responded without an image.";
            } else if (lowerMsg.contains("no image models found")) {
                title = isRu ? "Нет моделей для картинок" : "No image models available";
                hint = isRu ? "Среди доступных моделей нет подходящих для генерации." : "No image-capable models available.";
            } else if (lowerMsg.contains("empty response")) {
                title = isRu ? "Пустой ответ" : "Empty response";
                hint = isRu ? "Модель вернула пустой ответ. Попробуйте переформулировать." : "The model returned an empty response. Try rephrasing.";
            } else if (lowerMsg.contains("http")) {
                title = isRu ? "Ошибка сети" : "Network error";
                hint = isRu ? "Проверьте подключение к интернету." : "Check your internet connection.";
            } else {
                title = isRu ? "Ошибка" : "Error";
                hint = isRu ? "Не удалось выполнить запрос." : "The request failed.";
            }
        } else {
            title = isRu ? "Ошибка сервера" : "Server error";
            hint = isRu ? "HTTP " + code : "HTTP " + code;
        }

        StringBuilder body = new StringBuilder();
        body.append(hint);
        body.append("\n\n");
        body.append(isRu ? "Сообщение от сервера:" : "Server message:");
        body.append("\n");
        body.append(serverMsg.length() > 0 ? serverMsg : (isRu ? "(пусто)" : "(empty)"));

        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body.toString())
            .setPositiveButton("OK", null)
            .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "");
        menu.add(0, 2, 1, "");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        menu.findItem(1).setTitle(isRu ? "Настройки" : "Settings");
        menu.findItem(2).setTitle(isRu ? "Очистить чат" : "Clear chat");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == 2) {
            clearChat();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean hasMenuKey() {
        int sdk;
        try { sdk = Integer.parseInt(android.os.Build.VERSION.SDK); } catch (Exception e) { sdk = 1; }
        if (sdk < 14) return true;
        return android.view.ViewConfiguration.get(this).hasPermanentMenuKey();
    }

    private void clearChat() {
        if (pendingResponse) {
            boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
            Toast.makeText(this, ru ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
            return;
        }
        saveNewMessages();
        messages.clear();
        adapter.notifyDataSetChanged();
        currentChatId = -1;
        PendingState.clearAll();
    }

    private void showLegacyTlsHint() {
        int sdk;
        try { sdk = Integer.parseInt(android.os.Build.VERSION.SDK); } catch (Exception e) { sdk = 1; }
        if (sdk > 4 || prefs.isWolfiusHintShown()) return;
        prefs.setWolfiusHintShown(true);
        boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
        new AlertDialog.Builder(this)
            .setTitle("Wolfius")
            .setMessage(ru
                ? "На Android 1.x в системе нет современного TLS, поэтому Gemini не ответит. Установите и включите приложение Wolfius (github.com/gohoski/Wolfius): оно добавляет TLS 1.3 для Android 1.5-4.4, лучше с root. На Android 1.0 и 1.1 Wolfius не поддерживается."
                : "Android 1.x has no modern TLS, so Gemini will not respond. Install and enable the Wolfius app (github.com/gohoski/Wolfius): it adds TLS 1.3 for Android 1.5-4.4, root is recommended. Wolfius does not support Android 1.0 and 1.1.")
            .setPositiveButton("OK", null)
            .show();
    }

    private void updateToolsLabel() {
        boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
        btnTools.setText((ru ? "Инструменты" : "Tools") + (prefs.isSearch() ? " *" : ""));
    }

    // Кнопка "Картинка" работает как /image с текстом из поля ввода
    private void startImageFromInput() {
        boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
        String t = etMessage.getText().toString().trim();
        if (t.length() == 0) {
            Toast.makeText(this, ru ? "Введите описание картинки в поле" : "Type an image description first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!t.toLowerCase().startsWith("/image")) etMessage.setText("/image " + t);
        sendMessage();
    }

    private void showToolsDialog() {
        final boolean ru = Prefs.LANG_RU.equals(prefs.getLanguage());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(8));

        Button btnImage = new Button(this);
        btnImage.setText(ru ? "Картинка" : "Image");
        box.addView(btnImage);

        TextView tvHint = new TextView(this);
        tvHint.setText(ru ? "Введите описание в поле ввода и нажмите \"Картинка\"."
                          : "Type a description in the input field and press \"Image\".");
        tvHint.setTextColor(0xFF888888);
        tvHint.setTextSize(12);
        box.addView(tvHint);

        CheckBox cbSearch = new CheckBox(this);
        cbSearch.setText(ru ? "Поиск" : "Search");
        cbSearch.setChecked(prefs.isSearch());
        box.addView(cbSearch);

        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle(ru ? "Инструменты" : "Tools")
            .setView(box)
            .setPositiveButton("OK", null)
            .create();

        cbSearch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.setSearch(checked);
                updateToolsLabel();
            }
        });
        btnImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                startImageFromInput();
            }
        });
        dlg.show();
    }

    private int dp(int val) {
        float density = getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}