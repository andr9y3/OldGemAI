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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
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
    private android.widget.ImageButton btnSend;
    private Button btnAttach;
    private LinearLayout topBar;
    private android.widget.ImageButton btnMenu;

    private TextView tvNoKeyBanner;
    private ImageView ivPreview;
    private LinearLayout attachPreviewLayout;

    private String pendingImageBase64 = null;
    private String pendingImageMime = "image/jpeg";
    private long currentChatId = -1;
    private boolean pendingResponse = false;

    // Автопрокрутка: список тянется вниз только если пользователь уже внизу и не трогает экран
    private boolean userTouchingChat = false;
    private boolean alignScheduled = false;

    // Режим изменения сообщения: индекс изменяемого сообщения (-1 - обычный режим)
    private int editingIndex = -1;
    private String editDraftText = "";
    private String editDraftImage = null;
    private String editDraftImageMime = "image/jpeg";
    private LinearLayout editBar;
    private TextView tvEditing;

    // Контекст (экспериментальные функции): краткое содержание заменяет первые ctxSummaryCount сообщений чата
    private String ctxSummary = "";
    private int ctxSummaryCount = 0;
    private LinearLayout compressBar;
    private TextView tvCompress;
    private android.widget.ProgressBar compressProgress;

    // Плавное появление текста: показанная часть постепенно догоняет полученную
    private static final int REVEAL_TICK_MS = 50;
    private String revealTarget = "";
    private int revealShown = 0;
    private boolean revealRunning = false;
    private boolean revealFinishing = false;
    private String revealFinalText = null;
    private final Runnable revealTick = new Runnable() {
        @Override
        public void run() {
            onRevealTick();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        AppLog.d("UI", "MainActivity onCreate (restored=" + (savedInstanceState != null) + ")");
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
        btnMenu = new android.widget.ImageButton(this);
        btnMenu.setImageDrawable(new MenuIcon(dp(24), 0xFF000000));
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

        editBar = new LinearLayout(this);
        editBar.setOrientation(LinearLayout.HORIZONTAL);
        editBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        editBar.setPadding(dp(10), dp(2), dp(4), dp(2));
        editBar.setBackgroundColor(0xFFE3EBF6);
        editBar.setVisibility(View.GONE);

        tvEditing = new TextView(this);
        tvEditing.setTextColor(0xFF000000);
        tvEditing.setTextSize(13);
        editBar.addView(tvEditing, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnCancelEdit = new Button(this);
        btnCancelEdit.setText("✕");
        editBar.addView(btnCancelEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        btnCancelEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exitEditMode(true);
            }
        });
        mainLayout.addView(editBar);

        // Полоска с прогрессбаром: показывается, пока диалог сжимается
        compressBar = new LinearLayout(this);
        compressBar.setOrientation(LinearLayout.VERTICAL);
        compressBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        compressBar.setBackgroundColor(0xFFFFF4D6);
        compressBar.setVisibility(View.GONE);

        tvCompress = new TextView(this);
        tvCompress.setTextColor(0xFF000000);
        tvCompress.setTextSize(13);
        compressBar.addView(tvCompress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        compressProgress = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        compressProgress.setIndeterminate(true);
        compressProgress.setMax(100);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        progressParams.setMargins(0, dp(4), 0, 0);
        compressBar.addView(compressProgress, progressParams);
        mainLayout.addView(compressBar);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setPadding(10, 10, 10, 10);

        btnAttach = new Button(this);
        btnAttach.setText("+");
        inputLayout.addView(btnAttach, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        etMessage = new EditText(this);
        etMessage.setHint("...");
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        inputLayout.addView(etMessage, editParams);

        // Кнопка отправки: иконка-самолётик из res/raw/ic_send.svg
        btnSend = new android.widget.ImageButton(this) {
            @Override
            public void setEnabled(boolean enabled) {
                super.setEnabled(enabled);
                // У ImageButton иконка при выключении сама не тускнеет, делаем это вручную
                android.graphics.drawable.Drawable d = getDrawable();
                if (d != null) d.setAlpha(enabled ? 255 : 90);
                invalidate();
            }
        };
        btnSend.setImageDrawable(ChatAdapter.SvgIcon.fromRaw(this, R.raw.ic_send, dp(24), 0xFF000000));
        inputLayout.addView(btnSend, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainLayout.addView(inputLayout);
        setContentView(mainLayout);

        adapter = new ChatAdapter(this, messages);
        adapter.setRegenerateListener(new ChatAdapter.RegenerateListener() {
            @Override
            public boolean canRegenerate() {
                return !pendingResponse;
            }

            @Override
            public void onRegenerate() {
                regenerateLast();
            }
        });
        adapter.setEditListener(new ChatAdapter.EditListener() {
            @Override
            public void onEdit(int position) {
                startEdit(position);
            }
        });
        adapter.setResponseActionListener(new ChatAdapter.ResponseActionListener() {
            @Override
            public boolean isBusy() {
                return pendingResponse;
            }

            @Override
            public void onEditResponse(int position) {
                startEditResponse(position);
            }
        });
        lvChat.setAdapter(adapter);
        lvChat.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int a = event.getAction() & 0xFF;
                if (a == MotionEvent.ACTION_DOWN) userTouchingChat = true;
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) userTouchingChat = false;
                return false;
            }
        });
        // Wolfius больше не нужен: TLS 1.2 встроен (LegacyTls)

        updateBanner();

        restoreFromPendingState(savedInstanceState);

        // Автообновление: после поворота экрана возвращает окно обновления с текущим прогрессом,
        // проверка не чаще раза в сутки запускается только при новом запуске, а не при повороте
        Updater.attach(this);
        if (savedInstanceState == null) Updater.checkDaily(this);

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
            revealTarget = soFar;
            revealShown = soFar.length();
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
            loadSummaryState();
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
        Updater.detach(this);
        mainHandler.removeCallbacks(revealTick);
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
        if (useSmoothText()) {
            revealTarget = textSoFar;
            startReveal();
            return;
        }
        boolean pinned = shouldFollowChat();
        messages.set(messages.size() - 1, new GeminiApi.Message("model", textSoFar));
        adapter.notifyDataSetChanged();
        if (pinned) scrollChatToBottom();
    }

    @Override
    public void onPendingSuccess(String text) {
        // Если текст ещё допечатывается, дожидаемся конца плавного вывода и только потом завершаем
        if (useSmoothText() && pendingResponse && messages.size() > 0 && revealShown < text.length()) {
            revealTarget = text;
            revealFinalText = text;
            revealFinishing = true;
            startReveal();
            return;
        }
        finishSuccess(text);
    }

    private void finishSuccess(String text) {
        AppLog.d("UI", "Response received, chars=" + (text != null ? text.length() : 0));
        stopReveal();
        if (messages.size() > 0) {
            boolean pinned = shouldFollowChat();
            messages.set(messages.size() - 1, new GeminiApi.Message("model", text));
            adapter.notifyDataSetChanged();
            if (pinned) scrollChatToBottom();
        }
        btnSend.setEnabled(true);
        btnAttach.setEnabled(true);
        pendingResponse = false;
        saveNewMessages();
        PendingState.clearResponse();
    }

    @Override
    public void onPendingImage(String base64png) {
        stopReveal();
        boolean pinned = shouldFollowChat();
        if (messages.size() > 0) {
            messages.set(messages.size() - 1, new GeminiApi.Message("model", "", base64png, true));
        }
        adapter.notifyDataSetChanged();
        if (pinned) scrollChatToBottom();
        btnSend.setEnabled(true);
        btnAttach.setEnabled(true);
        pendingResponse = false;
        saveNewMessages();
        PendingState.clearResponse();
    }

    @Override
    public void onPendingError(String error) {
        stopReveal();
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
        exitEditMode(false);
        List<GeminiApi.Message> loaded = chatDb.getChatMessages(chatId);
        messages.clear();
        messages.addAll(loaded);
        adapter.notifyDataSetChanged();
        lvChat.setSelection(Math.max(0, messages.size() - 1));
        currentChatId = chatId;
        loadSummaryState();
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

        try {
            btnSend.setContentDescription(isRu ? "Отправить" : "Send");
        } catch (Throwable ignored) {}
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

        applyPendingEdit();
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

        startModelRequest(apiKey, isRu);
    }

    // Запускает запрос к модели по текущей истории (последнее сообщение - от пользователя)
    private void startModelRequest(String apiKey, boolean isRu) {
        messages.add(new GeminiApi.Message("model", "..."));
        pendingResponse = true;
        adapter.notifyDataSetChanged();
        // Отправка сообщения: всегда прокручиваем вниз
        scrollChatToBottom();

        btnSend.setEnabled(false);
        btnAttach.setEnabled(false);

        PendingState.pending = true;
        PendingState.partialText = "";
        PendingState.finalResponse = null;
        PendingState.finalImageBase64 = null;
        PendingState.error = null;
        PendingState.pendingModel = null;
        stopReveal();

        // Контекст (обрезка / сжатие) собирается в buildContextAndSend, запрос уходит в dispatchRequest
        buildContextAndSend(apiKey, isRu);
    }

    private void dispatchRequest(String apiKey, boolean isRu, String systemPrompt,
                                 List<GeminiApi.Message> historyCopy) {
        AppLog.d("UI", "Sending text message, history=" + historyCopy.size()
            + " of " + (messages.size() - 1) + ", summary=" + (ctxSummaryCount > 0 && prefs.isCompressContext()));
        geminiApi.sendMessage(
            apiKey,
            prefs.getModel(),
            prefs.getTemperature(),
            systemPrompt,
            historyCopy,
            prefs.isStream(),
            prefs.isSearch(),
            isRu ? "Источники" : "Sources",
            new TextSendCallback()
        );
    }

    // ---- Контекст: обрезка и сжатие диалога (экспериментальные функции) ----

    // Собирает то, что уйдёт модели: вся история, последние сообщения или пересказ старой части + последние
    // сообщения. В чате сообщения не удаляются, меняется только запрос к модели.
    private void buildContextAndSend(final String apiKey, final boolean isRu) {
        final int h = messages.size() - 1;   // история без заглушки "..."; последнее в ней - сообщение пользователя
        final String baseSystem = prefs.getSystemPrompt();
        if (ctxSummaryCount > h) clearSummaryState();

        if (!prefs.isTrimContext()) {
            dispatchRequest(apiKey, isRu, baseSystem, copyRange(0, h));
            return;
        }

        int limit = prefs.getTrimLimit();

        if (prefs.isCompressContext()) {
            // Сжатие: когда несжатых сообщений стало больше лимита, всё, кроме последних limit/2,
            // пересказывается и заменяется кратким содержанием
            if (h - ctxSummaryCount > limit) {
                int keep = Math.max(2, limit / 2);
                int cut = firstUserIndexFrom(Math.max(ctxSummaryCount, h - keep), h);
                if (cut > ctxSummaryCount) {
                    startCompression(apiKey, isRu, cut, h);
                    return;
                }
            }
            dispatchRequest(apiKey, isRu, withSummary(baseSystem, isRu), copyRange(ctxSummaryCount, h));
            return;
        }

        // Только обрезка: отправляем последние limit сообщений, начиная с сообщения пользователя
        int start = firstUserIndexFrom(Math.max(0, h - limit), h);
        dispatchRequest(apiKey, isRu, baseSystem, copyRange(start, h));
    }

    // Запрос к Gemini с просьбой кратко пересказать сообщения [ctxSummaryCount, cut). Предыдущее содержание
    // (если было) передаётся вместе с новыми сообщениями, поэтому содержание накапливается.
    private void startCompression(final String apiKey, final boolean isRu, final int cut, final int h) {
        AppLog.d("UI", "Compressing context: messages " + ctxSummaryCount + ".." + cut + " of " + h);
        showCompressing(true);

        StringBuilder sb = new StringBuilder();
        if (ctxSummary.length() > 0) {
            sb.append(isRu ? "Предыдущее краткое содержание:\n" : "Previous summary:\n");
            sb.append(ctxSummary).append("\n\n");
        }
        sb.append(isRu ? "Новые сообщения диалога:\n" : "New dialog messages:\n");
        for (int i = ctxSummaryCount; i < cut && i < messages.size(); i++) {
            GeminiApi.Message m = messages.get(i);
            boolean fromUser = "user".equals(m.role);
            sb.append(fromUser ? (isRu ? "Пользователь: " : "User: ") : (isRu ? "Ассистент: " : "Assistant: "));
            sb.append(describeForSummary(m, isRu)).append("\n\n");
        }

        List<GeminiApi.Message> request = new ArrayList<GeminiApi.Message>();
        request.add(new GeminiApi.Message("user", sb.toString()));

        // Потоковый запрос: прогрессбар движется по мере написания пересказа. Промпт сжатия - в системной инструкции, без поиска
        geminiApi.sendMessage(apiKey, prefs.getModel(), 0.3f, prefs.getCompressPrompt(), request,
            true, false, "", new GeminiApi.StreamCallback() {
                @Override
                public void onPartial(final String textSoFar) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateCompressProgress(textSoFar.length());
                        }
                    });
                }

                @Override
                public void onSuccess(final String text) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onCompressionDone(apiKey, isRu, cut, h, text);
                        }
                    });
                }

                @Override
                public void onError(final String error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            onCompressionFailed(apiKey, isRu, h, error);
                        }
                    });
                }
            });
    }

    private void onCompressionDone(String apiKey, boolean isRu, int cut, int h, String summary) {
        showCompressing(false);
        if (prefs.isLogCompress()) {
            AppLog.i("Compress", "Model reply (" + (summary != null ? summary.length() : 0) + " chars):\n"
                + (summary != null ? summary : "(null)"));
        }
        String s = summary != null ? summary.trim() : "";
        if (s.length() == 0) {
            onCompressionFailed(apiKey, isRu, h, "empty summary");
            return;
        }
        ctxSummary = s;
        ctxSummaryCount = cut;
        saveNewMessages();   // чтобы у чата был id, к которому можно привязать пересказ
        if (currentChatId != -1) prefs.setChatSummary(currentChatId, s, cut);
        AppLog.d("UI", "Context compressed: " + cut + " messages -> summary of " + s.length() + " chars");
        dispatchRequest(apiKey, isRu, withSummary(prefs.getSystemPrompt(), isRu), copyRange(cut, h));
    }

    // Ошибка сжатия не блокирует ответ: отправляем несжатые сообщения (следующая отправка попробует снова)
    private void onCompressionFailed(String apiKey, boolean isRu, int h, String error) {
        showCompressing(false);
        AppLog.w("UI", "Context compression failed: " + error);
        Toast.makeText(this, isRu
            ? "Не удалось сжать диалог, сообщения отправлены как есть"
            : "Could not compress the dialog, sending messages as is", Toast.LENGTH_LONG).show();
        dispatchRequest(apiKey, isRu, withSummary(prefs.getSystemPrompt(), isRu), copyRange(ctxSummaryCount, h));
    }

    // Обновляет прогрессбар по длине уже написанного пересказа. Итоговая длина заранее неизвестна,
    // поэтому доля растёт быстро в начале, замедляется к концу и не доходит до 100%, пока ответ не закончился.
    private void updateCompressProgress(int summaryChars) {
        if (compressBar.getVisibility() != View.VISIBLE) return;
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        int pct = (int) Math.round(100.0 * (1.0 - Math.exp(-summaryChars / 500.0)));
        if (pct > 97) pct = 97;
        if (compressProgress.isIndeterminate()) compressProgress.setIndeterminate(false);
        compressProgress.setProgress(pct);
        tvCompress.setText((isRu ? "Сжатие диалога... " : "Compressing dialog... ") + pct + "%");
    }

    private void showCompressing(boolean on) {
        if (on) {
            boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
            tvCompress.setText(isRu ? "Сжатие диалога..." : "Compressing dialog...");
            // Пока модель не начала писать пересказ, доля готовности неизвестна: бесконечный прогрессбар
            compressProgress.setIndeterminate(true);
        }
        compressBar.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // Пересказ добавляется к системному промпту: так не нарушается чередование ролей user/model
    private String withSummary(String baseSystem, boolean isRu) {
        if (ctxSummaryCount == 0 || ctxSummary.length() == 0) return baseSystem;
        String head = isRu
            ? "Краткое содержание предыдущей части диалога (сами сообщения пропущены для экономии контекста):\n"
            : "Summary of the earlier part of the conversation (the messages themselves were omitted to save context):\n";
        return (baseSystem != null && baseSystem.length() > 0 ? baseSystem + "\n\n" : "") + head + ctxSummary;
    }

    // Текст сообщения для пересказа: картинки заменяются меткой, очень длинные тексты обрезаются
    private String describeForSummary(GeminiApi.Message m, boolean isRu) {
        String t = m.text != null ? m.text.trim() : "";
        if (t.length() > 3000) t = t.substring(0, 3000) + "...";
        if (m.imageBase64 != null && m.imageBase64.length() > 0) {
            String tag = m.isGeneratedImage
                ? (isRu ? "[сгенерированная картинка]" : "[generated image]")
                : (isRu ? "[изображение]" : "[image]");
            t = t.length() > 0 ? t + " " + tag : tag;
        }
        return t;
    }

    private List<GeminiApi.Message> copyRange(int from, int to) {
        List<GeminiApi.Message> out = new ArrayList<GeminiApi.Message>();
        int end = Math.min(to, messages.size());
        for (int i = Math.max(0, from); i < end; i++) {
            out.add(messages.get(i));
        }
        return out;
    }

    // Первое сообщение пользователя в [from, end): история, отправляемая модели, должна начинаться с user
    private int firstUserIndexFrom(int from, int end) {
        for (int i = Math.max(0, from); i < end && i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role)) return i;
        }
        return Math.max(0, from);
    }

    // Загружает сохранённый пересказ текущего чата (вызывать после загрузки сообщений)
    private void loadSummaryState() {
        resetSummaryState();
        if (currentChatId == -1) return;
        String s = prefs.getChatSummary(currentChatId);
        int c = prefs.getChatSummaryCount(currentChatId);
        if (s.length() > 0 && c > 0 && c <= messages.size()) {
            ctxSummary = s;
            ctxSummaryCount = c;
        }
    }

    // Сбрасывает только состояние в памяти (при переключении на другой чат)
    private void resetSummaryState() {
        ctxSummary = "";
        ctxSummaryCount = 0;
    }

    // Пересказ стал неактуальным (сообщения, которые он заменяет, изменены или удалены): удаляем и из памяти, и из настроек
    private void clearSummaryState() {
        if (currentChatId != -1) prefs.clearChatSummary(currentChatId);
        resetSummaryState();
    }

    // Перегенерация последнего ответа модели (кнопка под ответом)
    private void regenerateLast() {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        if (pendingResponse) {
            Toast.makeText(this, isRu ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
            return;
        }
        String apiKey = prefs.getApiKey();
        if (TextUtils.isEmpty(apiKey)) {
            Toast.makeText(this, isRu ? "Сначала добавьте API ключ в настройках" : "Please add an API key in Settings", Toast.LENGTH_LONG).show();
            return;
        }

        if (editingIndex >= 0) exitEditMode(true);
        int n = messages.size();
        if (n < 2) return;
        GeminiApi.Message last = messages.get(n - 1);
        GeminiApi.Message prev = messages.get(n - 2);
        if (!"model".equals(last.role) || !"user".equals(prev.role)) return;

        String prevText = prev.text != null ? prev.text : "";
        if (prevText.toLowerCase().startsWith("/image ")) {
            // Перегенерация картинки
            String prompt = prevText.substring(7).trim();
            if (prompt.length() == 0) return;
            String imageModel = prefs.getImageModel();
            if (prefs.isImageRemember() && imageModel.length() > 0) {
                AppLog.d("UI", "Regenerate image, model=" + imageModel);
                messages.remove(n - 1);
                messages.remove(n - 2);
                trimDbTo(n - 2);
                adapter.notifyDataSetChanged();
                startImageGeneration(prompt, imageModel, isRu, apiKey);
            } else {
                // Модель не запомнена: показываем обычный выбор модели,
                // старая картинка остаётся в чате (чтобы не потерять её при отмене)
                AppLog.d("UI", "Regenerate image: model not remembered, asking");
                String savedInput = etMessage.getText().toString();
                etMessage.setText("/image " + prompt);
                sendMessage();
                etMessage.setText(savedInput);
            }
            return;
        }

        // Перегенерация текстового ответа: убираем последний ответ и заново спрашиваем модель
        AppLog.d("UI", "Regenerate text response");
        messages.remove(n - 1);
        trimDbTo(n - 1);
        adapter.notifyDataSetChanged();
        startModelRequest(apiKey, isRu);
        lvChat.setSelection(messages.size() - 1);
    }

    // ---- Изменение сообщения пользователя ----

    // Помещает текст (и картинку, если была) сообщения в поле ввода. При отправке
    // это сообщение и всё после него заменяется новой версией и модель отвечает заново.
    private void startEdit(int position) {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        if (pendingResponse) {
            Toast.makeText(this, isRu ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
            return;
        }
        if (position < 0 || position >= messages.size()) return;
        GeminiApi.Message m = messages.get(position);
        if (!"user".equals(m.role)) return;

        // Черновик сохраняем только при входе в режим (при переключении между сообщениями не затираем)
        if (editingIndex < 0) {
            editDraftText = etMessage.getText().toString();
            editDraftImage = pendingImageBase64;
            editDraftImageMime = pendingImageMime;
        }
        editingIndex = position;
        AppLog.d("UI", "Edit message #" + position + " of " + messages.size());

        etMessage.setText(m.text != null ? m.text : "");
        etMessage.setSelection(etMessage.getText().length());

        if (m.imageBase64 != null && m.imageBase64.length() > 0 && !m.isGeneratedImage) {
            setPendingImage(m.imageBase64, m.imageMimeType);
        } else {
            setPendingImage(null, null);
        }

        tvEditing.setText(isRu
            ? "Изменение сообщения. Все сообщения после него будут удалены."
            : "Editing message. All messages after it will be removed.");
        editBar.setVisibility(View.VISIBLE);

        etMessage.requestFocus();
        try {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etMessage, 0);
        } catch (Throwable ignored) {}
    }

    // ---- Изменение ответа модели ----

    // Окно с текстом ответа: после «Сохранить» ответ в чате (и в БД) заменяется на изменённый.
    // Сообщения после него не удаляются: модель увидит новую версию в истории при следующем запросе.
    private void startEditResponse(final int position) {
        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        if (pendingResponse) {
            Toast.makeText(this, isRu ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
            return;
        }
        if (position < 0 || position >= messages.size()) return;
        GeminiApi.Message m = messages.get(position);
        if (!"model".equals(m.role) || m.isGeneratedImage) return;

        final EditText input = new EditText(this);
        input.setText(m.text != null ? m.text : "");
        input.setGravity(android.view.Gravity.TOP);
        input.setMinLines(6);

        android.widget.ScrollView box = new android.widget.ScrollView(this);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        box.addView(input, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.FILL_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
            .setTitle(isRu ? "Изменить ответ" : "Edit response")
            .setView(box)
            .setPositiveButton(isRu ? "Сохранить" : "Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    applyResponseEdit(position, input.getText().toString());
                }
            })
            .setNegativeButton(isRu ? "Отмена" : "Cancel", null)
            .show();
    }

    private void applyResponseEdit(int position, String newText) {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        if (pendingResponse || position < 0 || position >= messages.size()) return;
        GeminiApi.Message old = messages.get(position);
        if (!"model".equals(old.role) || old.isGeneratedImage) return;

        String oldText = old.text != null ? old.text : "";
        if (newText.equals(oldText)) return;
        if (newText.trim().length() == 0) {
            Toast.makeText(this, isRu ? "Ответ не может быть пустым, изменения не сохранены"
                                  : "The response cannot be empty, changes were not saved", Toast.LENGTH_LONG).show();
            return;
        }

        AppLog.d("UI", "Edit response #" + position + " of " + messages.size());
        // Пересказ, который заменяет это сообщение, стал неактуальным
        if (ctxSummaryCount > position) clearSummaryState();

        messages.set(position, new GeminiApi.Message("model", newText));
        adapter.notifyDataSetChanged();

        // Если сообщение уже в БД - обновляем его; иначе оно сохранится вместе с новыми
        if (currentChatId != -1 && chatDb.chatExists(currentChatId)
                && position < chatDb.getMessageCount(currentChatId)) {
            chatDb.updateMessageText(currentChatId, position, newText);
        } else {
            saveNewMessages();
        }
    }

    // Выход из режима изменения; restoreDraft - вернуть в поле то, что было до изменения
    private void exitEditMode(boolean restoreDraft) {
        if (editingIndex < 0) return;
        editingIndex = -1;
        editBar.setVisibility(View.GONE);
        if (restoreDraft) {
            etMessage.setText(editDraftText != null ? editDraftText : "");
            setPendingImage(editDraftImage, editDraftImageMime);
        }
        editDraftText = "";
        editDraftImage = null;
        editDraftImageMime = "image/jpeg";
    }

    // Вызывается при отправке: удаляет изменяемое сообщение и всё после него (из списка и из БД)
    private void applyPendingEdit() {
        if (editingIndex < 0) return;
        int idx = editingIndex;
        exitEditMode(false);
        if (idx < messages.size()) {
            AppLog.d("UI", "Edit applied: removing messages from #" + idx + " (total " + messages.size() + ")");
            while (messages.size() > idx) {
                messages.remove(messages.size() - 1);
            }
            trimDbTo(idx);
            adapter.notifyDataSetChanged();
        }
    }

    // Устанавливает (или убирает, если b64 == null) прикреплённое изображение и превью
    private void setPendingImage(String b64, String mime) {
        if (b64 != null && b64.length() > 0) {
            try {
                byte[] bytes = Base64Compat.decode(b64);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    pendingImageBase64 = b64;
                    pendingImageMime = mime != null ? mime : "image/jpeg";
                    PendingState.pendingImageBase64 = pendingImageBase64;
                    PendingState.pendingImageMime = pendingImageMime;
                    ivPreview.setImageBitmap(bmp);
                    attachPreviewLayout.setVisibility(View.VISIBLE);
                    return;
                }
            } catch (Exception ignored) {}
        }
        pendingImageBase64 = null;
        pendingImageMime = "image/jpeg";
        PendingState.clearImage();
        attachPreviewLayout.setVisibility(View.GONE);
    }

    // Удаляет из БД последние сообщения текущего чата, пока там не останется size штук
    private void trimDbTo(int size) {
        if (ctxSummaryCount > size) clearSummaryState();
        if (currentChatId == -1 || !chatDb.chatExists(currentChatId)) return;
        int count = chatDb.getMessageCount(currentChatId);
        while (count > size) {
            chatDb.deleteLastMessage(currentChatId);
            count--;
        }
    }

    private void startImageGeneration(String prompt, String selectedModel, boolean isRu, String apiKey) {
        applyPendingEdit();
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

        AppLog.d("UI", "Starting image generation, model=" + selectedModel);
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

    // ---- Плавное появление текста ----

    // Работает только при включённом постепенном выводе и включённой галке в настройках
    private boolean useSmoothText() {
        return prefs.isStream() && prefs.isSmoothText();
    }

    private void startReveal() {
        if (!revealRunning) scheduleReveal();
    }

    private void scheduleReveal() {
        revealRunning = true;
        mainHandler.postDelayed(revealTick, REVEAL_TICK_MS);
    }

    private void stopReveal() {
        mainHandler.removeCallbacks(revealTick);
        revealRunning = false;
        revealFinishing = false;
        revealFinalText = null;
        revealTarget = "";
        revealShown = 0;
    }

    private void onRevealTick() {
        revealRunning = false;
        if (!pendingResponse || messages.size() == 0) return;

        int len = revealTarget.length();
        if (revealShown < len) {
            // Чем больше накопилось непоказанного текста, тем быстрее печатаем, чтобы не отставать
            int step = Math.max(2, (len - revealShown) / 5);
            revealShown = Math.min(len, revealShown + step);
            // Не разрываем суррогатную пару (эмодзи)
            if (revealShown < len && Character.isHighSurrogate(revealTarget.charAt(revealShown - 1))) {
                revealShown++;
            }
            boolean pinned = shouldFollowChat();
            messages.set(messages.size() - 1,
                new GeminiApi.Message("model", revealTarget.substring(0, revealShown)));
            adapter.notifyDataSetChanged();
            if (pinned) scrollChatToBottom();
        }

        if (revealShown < len) {
            scheduleReveal();
        } else if (revealFinishing) {
            finishSuccess(revealFinalText);
        }
    }

    // ---- Автопрокрутка ----

    // Пользователь у самого низа списка (последний элемент виден до конца)?
    private boolean isChatAtBottom() {
        int count = adapter.getCount();
        int childCount = lvChat.getChildCount();
        if (count == 0 || childCount == 0) return true;
        if (lvChat.getFirstVisiblePosition() + childCount < count) return false;
        View last = lvChat.getChildAt(childCount - 1);
        return last.getBottom() <= lvChat.getHeight() - lvChat.getPaddingBottom() + dp(4);
    }

    // Тянуть вниз только если пользователь внизу и сейчас не листает
    private boolean shouldFollowChat() {
        return !userTouchingChat && isChatAtBottom();
    }

    // Прокрутка в самый низ: низ последнего сообщения прижимается к низу списка (даже если оно длиннее экрана)
    private void scrollChatToBottom() {
        int count = adapter.getCount();
        if (count == 0) return;
        lvChat.setSelection(count - 1);
        if (alignScheduled) return;
        alignScheduled = true;
        final ViewTreeObserver vto = lvChat.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                alignScheduled = false;
                ViewTreeObserver cur = lvChat.getViewTreeObserver();
                if (cur.isAlive()) cur.removeOnPreDrawListener(this);
                // true - рисовать как есть; false - была корректировка, кадр перерисуется после нового layout
                return !alignLastItemBottom();
            }
        });
    }

    private boolean alignLastItemBottom() {
        int count = adapter.getCount();
        int childCount = lvChat.getChildCount();
        if (count == 0 || childCount == 0) return false;
        if (lvChat.getFirstVisiblePosition() + childCount < count) return false;
        View last = lvChat.getChildAt(childCount - 1);
        int overflow = last.getBottom() - (lvChat.getHeight() - lvChat.getPaddingBottom());
        if (overflow > 0) {
            lvChat.setSelectionFromTop(count - 1, last.getTop() - overflow);
            return true;
        }
        return false;
    }

    private void showErrorDialog(String rawError) {
        AppLog.w("UI", "Error dialog shown: " + rawError);
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
        exitEditMode(false);
        resetSummaryState();
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
        btnAttach.setText(prefs.isSearch() ? "+*" : "+");
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

        Button btnPhoto = new Button(this);
        btnPhoto.setText(ru ? "Фото" : "Photo");
        box.addView(btnPhoto);

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

        // Нижняя панель: обычный Dialog, прижатый к низу экрана
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(box);
        android.view.Window win = dlg.getWindow();
        win.setGravity(android.view.Gravity.BOTTOM);
        win.setLayout(android.view.ViewGroup.LayoutParams.FILL_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

        cbSearch.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                prefs.setSearch(checked);
                updateToolsLabel();
            }
        });
        btnPhoto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("image/*");
                startActivityForResult(intent, REQUEST_PICK_IMAGE);
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

    // Иконка "три полоски" рисуется кодом: символ \u2630 есть не во всех шрифтах старых Android
    private static class MenuIcon extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint();
        private final int size;

        MenuIcon(int size, int color) {
            this.size = size;
            paint.setColor(color);
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float barH = b.height() / 12f;
            for (int i = 0; i < 3; i++) {
                float center = b.top + b.height() * (0.25f + 0.25f * i);
                float top = center - barH / 2f;
                canvas.drawRect(b.left, top, b.right, top + barH, paint);
            }
        }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }

        @Override
        public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private int dp(int val) {
        float density = getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}