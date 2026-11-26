package com.ialwaysloveyou.oldgemai;

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
    private static final int REQUEST_VOICE = 1003;
    private static final int REQUEST_PICK_FILE = 1004;

    // Быстрое переключение модели (вкладки рядом с кнопкой истории чатов)
    private static final String QUICK_MODEL_LITE = "gemini-flash-lite-latest";
    private static final String QUICK_MODEL_FLASH = "gemini-flash-latest";
    private static final String QUICK_MODEL_PRO = "gemini-pro-latest";

    private Prefs prefs;
    private List<GeminiApi.Message> messages;
    private ChatAdapter adapter;
    private GeminiApi geminiApi;
    private Handler mainHandler;
    private ChatDatabase chatDb;

    private ListView lvChat;
    private EditText etMessage;
    private android.widget.ImageButton btnSend;
    private android.widget.ImageButton btnMic;
    private Button btnAttach;
    private LinearLayout topBar;
    private android.widget.ImageButton btnMenu;
    private TextView tvModelLite;
    private TextView tvModelFlash;
    private TextView tvModelPro;

    private TextView tvNoKeyBanner;
    // Полоска вложений над полем ввода (картинки, PDF, файлы). Сами вложения - в PendingState.pendingAttachments
    private android.widget.ScrollView attachPreviewLayout;
    private LinearLayout attachStrip;
    private final Runnable attachRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshAttachPreview();
        }
    };

    private long currentChatId = -1;
    private boolean pendingResponse = false;

    // Автопрокрутка: список тянется вниз только если пользователь уже внизу и не трогает экран
    private boolean userTouchingChat = false;
    private boolean alignScheduled = false;

    // Режим изменения сообщения: индекс изменяемого сообщения (-1 - обычный режим)
    private int editingIndex = -1;
    private String editDraftText = "";
    private List<Attachment> editDraftAttachments = new ArrayList<Attachment>();
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
        pendingResponse = PendingState.pending;

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        btnMenu = new android.widget.ImageButton(this);
        btnMenu.setImageDrawable(new MenuIcon(dp(24), 0xFF000000));
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Быстрое переключение модели: три вкладки, занимают всё свободное место топ-бара
        LinearLayout modelSwitcher = new LinearLayout(this);
        modelSwitcher.setOrientation(LinearLayout.HORIZONTAL);
        modelSwitcher.setPadding(dp(6), dp(4), dp(6), dp(4));
        topBar.addView(modelSwitcher, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        tvModelLite = makeModelTab();
        tvModelFlash = makeModelTab();
        tvModelPro = makeModelTab();
        tvModelLite.setText("Lite");
        tvModelFlash.setText("Flash");
        tvModelPro.setText("Pro");
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        int tabMargin = dp(2);
        LinearLayout.LayoutParams tabParamsL = new LinearLayout.LayoutParams(tabParams);
        tabParamsL.setMargins(0, 0, tabMargin, 0);
        LinearLayout.LayoutParams tabParamsM = new LinearLayout.LayoutParams(tabParams);
        tabParamsM.setMargins(tabMargin, 0, tabMargin, 0);
        LinearLayout.LayoutParams tabParamsR = new LinearLayout.LayoutParams(tabParams);
        tabParamsR.setMargins(tabMargin, 0, 0, 0);
        modelSwitcher.addView(tvModelLite, tabParamsL);
        modelSwitcher.addView(tvModelFlash, tabParamsM);
        modelSwitcher.addView(tvModelPro, tabParamsR);

        tvModelLite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectQuickModel(QUICK_MODEL_LITE); }
        });
        tvModelFlash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectQuickModel(QUICK_MODEL_FLASH); }
        });
        tvModelPro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectQuickModel(QUICK_MODEL_PRO); }
        });

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

        // Полоска вложений: плитки рядами (миниатюры картинок и файлы), у каждой кнопка «✕».
        // Высота ограничена двумя рядами, дальше прокручивается (ScrollView есть на любом Android)
        attachPreviewLayout = new android.widget.ScrollView(this);
        attachPreviewLayout.setBackgroundColor(0xFF222222);
        attachPreviewLayout.setVisibility(View.GONE);
        attachStrip = new LinearLayout(this);
        attachStrip.setOrientation(LinearLayout.VERTICAL);
        attachStrip.setPadding(dp(6), dp(6), dp(6), dp(6));
        attachPreviewLayout.addView(attachStrip, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.FILL_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        mainLayout.addView(attachPreviewLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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

        // Кнопка голосового ввода: иконка из res/raw/ic_mic.svg, распознанный текст вставляется в поле ввода
        btnMic = new android.widget.ImageButton(this);
        btnMic.setImageDrawable(ChatAdapter.SvgIcon.fromRaw(this, R.raw.ic_mic, dp(24), 0xFF000000));
        btnMic.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVoiceInput();
            }
        });
        inputLayout.addView(btnMic, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

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

            @Override
            public void onSpeakResponse(String text) {
                speakResponse(text);
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
        PendingState.attachRefresh = attachRefreshRunnable;

        btnSend.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Пока идёт текстовый ответ, это кнопка «Стоп»
                if (isStopMode()) stopGeneration();
                else sendMessage();
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

    }

    private void restoreFromPendingState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            String input = savedInstanceState.getString("input_text");
            if (input != null) etMessage.setText(input);
        } else if (PendingState.inputText != null) {
            etMessage.setText(PendingState.inputText);
        }

        refreshAttachPreview();

        if (PendingState.error != null) {
            final String err = PendingState.error;
            PendingState.clearResponse();
            pendingResponse = false;
            updateSendButton();
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
            updateSendButton();
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
            updateSendButton();
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
            updateSendButton();   // если это текстовый ответ, кнопка остаётся «Стоп»
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
        if (PendingState.attachRefresh == attachRefreshRunnable) {
            PendingState.attachRefresh = null;
        }
        if (isFinishing()) {
            PendingState.clearAll();
            // Приложение закрывается: останавливаем озвучивание и освобождаем движок
            if (Http.sdk() >= Speaker.MIN_SDK) {
                try { Speaker.shutdown(); } catch (Throwable ignored) {}
            }
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
        // Ответ уже остановлен кнопкой «Стоп»: запаздывшее уведомление игнорируем
        if (!pendingResponse) return;
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
        btnAttach.setEnabled(true);
        pendingResponse = false;
        saveNewMessages();
        PendingState.clearResponse();
        updateSendButton();
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
        btnAttach.setEnabled(true);
        pendingResponse = false;
        saveNewMessages();
        PendingState.clearResponse();
        updateSendButton();
    }

    @Override
    public void onPendingError(String error) {
        // Ответ уже остановлен кнопкой «Стоп»: запаздывшее уведомление игнорируем
        if (!pendingResponse) return;
        stopReveal();
        pendingResponse = false;
        if (messages.size() > 0) {
            messages.remove(messages.size() - 1);
            adapter.notifyDataSetChanged();
        }
        btnAttach.setEnabled(true);
        PendingState.clearResponse();
        updateSendButton();
        showErrorDialog(error);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_VOICE) {
            boolean ruVoice = Prefs.LANG_RU.equals(prefs.getLanguage());
            if (resultCode == RESULT_OK) {
                String spoken = VoiceInput.extractText(data);
                if (spoken.length() > 0) {
                    insertVoiceText(spoken);
                } else {
                    Toast.makeText(this, ruVoice ? "Речь не распознана" : "Speech was not recognized", Toast.LENGTH_SHORT).show();
                }
            } else {
                String err = VoiceInput.errorText(resultCode, ruVoice);
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            }
            return;
        }

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

        // Выбор картинок или файлов (можно сразу несколько, с Android 4.3)
        if ((requestCode == REQUEST_PICK_IMAGE || requestCode == REQUEST_PICK_FILE)
                && resultCode == RESULT_OK && data != null) {
            addAttachmentsFromUris(extractUris(data));
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
        stopSpeech();
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
        PendingState.inputText = etMessage != null ? etMessage.getText().toString() : "";
    }

    // Создаёт одну вкладку переключателя моделей (стиль задаётся отдельно в styleModelTab)
    private TextView makeModelTab() {
        TextView tv = new TextView(this);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setTextSize(12);
        tv.setPadding(0, dp(6), 0, dp(6));
        return tv;
    }

    // Нажатие на вкладку: сразу переключает активную модель (без захода в настройки)
    private void selectQuickModel(String model) {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        if (pendingResponse) {
            Toast.makeText(this, isRu ? "Дождитесь ответа" : "Wait for the response", Toast.LENGTH_SHORT).show();
            return;
        }
        if (model.equals(prefs.getModel())) return;
        prefs.setModel(model);
        // Явный выбор модели отменяет временный резервный выбор (см. Prefs.setFallbackModel)
        prefs.clearFallbackModel();
        AppLog.d("UI", "Quick model switch: " + model);
        updateModelSwitcher();
        Toast.makeText(this, (isRu ? "Модель: " : "Model: ") + model, Toast.LENGTH_SHORT).show();
    }

    // Подсвечивает вкладку текущей активной модели (с учётом запомненного резерва - см. Prefs.getActiveModel)
    private void updateModelSwitcher() {
        if (tvModelLite == null) return;
        String active = prefs.getActiveModel();
        styleModelTab(tvModelLite, QUICK_MODEL_LITE.equals(active));
        styleModelTab(tvModelFlash, QUICK_MODEL_FLASH.equals(active));
        styleModelTab(tvModelPro, QUICK_MODEL_PRO.equals(active));
    }

    private void styleModelTab(TextView tv, boolean selected) {
        tv.setBackgroundColor(selected ? 0xFF1A73E8 : 0xFF2B2B2B);
        tv.setTextColor(selected ? 0xFFFFFFFF : 0xFFAAAAAA);
    }

    private void updateBanner() {
        boolean hasKey = !TextUtils.isEmpty(prefs.getApiKey());
        tvNoKeyBanner.setVisibility(hasKey ? View.GONE : View.VISIBLE);
        updateModelSwitcher();

        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        tvNoKeyBanner.setText(isRu
            ? "API ключ не задан - ИИ не будет работать. Добавьте ключ в настройках."
            : "No API key set - AI will not work. Add your key in Settings.");

        updateSendButton();
        updateMicButton();
        updateToolsLabel();


        etMessage.setHint(isRu ? "Сообщение..." : "Message...");
        setTitle(isRu ? "OldGemAI" : "OldGemAI");
    }

    private void sendMessage() {
        final String text = etMessage.getText().toString().trim();
        final boolean hasAttachments = !PendingState.pendingAttachments.isEmpty();

        if (PendingState.attachLoading > 0) {
            Toast.makeText(this, Prefs.LANG_RU.equals(prefs.getLanguage())
                ? "Дождитесь загрузки вложений" : "Wait for the attachments to load", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(text) && !hasAttachments) return;

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

        applyPendingEdit();
        GeminiApi.Message userMsg = hasAttachments
            ? new GeminiApi.Message("user", text, new ArrayList<Attachment>(PendingState.pendingAttachments))
            : new GeminiApi.Message("user", text);
        messages.add(userMsg);
        adapter.notifyDataSetChanged();
        lvChat.setSelection(messages.size() - 1);
        etMessage.setText("");

        PendingState.clearAttachments();
        refreshAttachPreview();

        startModelRequest(apiKey, isRu);
    }

    // ---- Голосовой ввод ----

    private void updateMicButton() {
        if (btnMic == null) return;
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        try {
            btnMic.setContentDescription(isRu ? "Голосовой ввод" : "Voice input");
        } catch (Throwable ignored) {}
    }

    // Открывает системное окно распознавания речи; язык распознавания - язык интерфейса приложения.
    // На версии Android, где голосового ввода нет (ниже Android 1.5), запись не запускается, показывается отказ.
    private void startVoiceInput() {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        // MIN_SDK - константа времени компиляции, поэтому класс VoiceInput здесь ещё не загружается
        if (Http.sdk() < VoiceInput.MIN_SDK) {
            AppLog.w("UI", "Voice input refused: API " + Http.sdk() + " < " + VoiceInput.MIN_SDK);
            String ver = android.os.Build.VERSION.RELEASE;
            Toast.makeText(this, isRu
                ? "Голосовой ввод не поддерживается на Android " + ver + " (нужен Android 1.5 или новее)"
                : "Voice input is not supported on Android " + ver + " (Android 1.5 or newer is required)", Toast.LENGTH_LONG).show();
            return;
        }
        // Текущий текст поля сохраняем заранее: пока окно распознавания открыто, экран может пересоздаться
        PendingState.inputText = etMessage.getText().toString();
        int status = VoiceInput.start(this, REQUEST_VOICE, isRu);
        if (status == VoiceInput.STARTED) return;
        String msg;
        if (status == VoiceInput.UNSUPPORTED_SDK) {
            msg = isRu ? "Голосовой ввод не поддерживается на этой версии Android"
                       : "Voice input is not supported on this Android version";
        } else if (status == VoiceInput.NO_RECOGNIZER) {
            msg = isRu ? "Нет приложения для распознавания речи (нужно приложение Google)"
                       : "No speech recognition app found (the Google app is required)";
        } else {
            msg = isRu ? "Не удалось открыть голосовой ввод"
                       : "Could not open voice input";
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // Вставляет распознанный текст в поле ввода в место курсора (или заменяет выделенное), пробел добавляется при необходимости
    private void insertVoiceText(String spoken) {
        android.text.Editable e = etMessage.getText();
        int start = etMessage.getSelectionStart();
        int end = etMessage.getSelectionEnd();
        if (start < 0 || end < 0) {
            start = e.length();
            end = start;
        }
        if (end < start) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        String ins = spoken;
        if (start > 0 && !Character.isWhitespace(e.charAt(start - 1))) ins = " " + ins;
        e.replace(start, end, ins);
        etMessage.setSelection(Math.min(start + ins.length(), e.length()));
        etMessage.requestFocus();
    }

    // ---- Озвучивание ответа ----

    // Останавливает озвучивание; на Android без синтеза речи ничего не делает и класс Speaker не трогает
    private void stopSpeech() {
        if (Http.sdk() < Speaker.MIN_SDK) return;
        try { Speaker.stop(); } catch (Throwable ignored) {}
    }

    // Кнопка «Озвучить» под ответом: читает ответ вслух, повторное нажатие останавливает.
    // Версия Android проверяется до обращения к Speaker (MIN_SDK - константа времени компиляции):
    // на неподдерживаемой версии синтез речи не запускается, показывается ошибка.
    private void speakResponse(String text) {
        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        if (Http.sdk() < Speaker.MIN_SDK) {
            AppLog.w("UI", "Speech refused: API " + Http.sdk() + " < " + Speaker.MIN_SDK);
            showSpeakError(Speaker.UNSUPPORTED_SDK, isRu);
            return;
        }
        try {
            Speaker.toggle(this, text, isRu, new Speaker.Listener() {
                @Override
                public void onResult(int code) {
                    showSpeakError(code, isRu);
                }
            });
        } catch (Throwable t) {
            AppLog.w("UI", "Speech call failed: " + t);
            showSpeakError(Speaker.FAILED, isRu);
        }
    }

    // Текст ошибки по коду результата озвучивания; для «началось» и «остановлено» ничего не показывается
    private void showSpeakError(int code, boolean isRu) {
        String msg;
        if (code == Speaker.UNSUPPORTED_SDK) {
            String ver = android.os.Build.VERSION.RELEASE;
            msg = isRu
                ? "Озвучивание не поддерживается на Android " + ver + " (нужен Android 1.6 или новее)"
                : "Speech is not supported on Android " + ver + " (Android 1.6 or newer is required)";
        } else if (code == Speaker.NO_ENGINE) {
            msg = isRu
                ? "Нет движка синтеза речи или он не запустился. Установите или включите его в настройках (Язык и ввод - Синтез речи)"
                : "No text-to-speech engine found or it failed to start. Install or enable one in Settings (Language & input - Text-to-speech)";
        } else if (code == Speaker.LANGUAGE_UNAVAILABLE) {
            msg = isRu
                ? "Выбранный движок синтеза речи не умеет читать по-русски или у него нет голосовых данных этого языка"
                : "The selected text-to-speech engine cannot read English or has no voice data for it";
        } else if (code == Speaker.NOTHING_TO_SPEAK) {
            msg = isRu ? "В ответе нет текста для озвучивания" : "There is no text to read in this response";
        } else if (code == Speaker.FAILED) {
            msg = isRu ? "Не удалось озвучить текст" : "Could not read the text aloud";
        } else {
            return;   // STARTED и STOPPED - это не ошибки
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ---- Остановка ответа ----

    // Идёт текстовый ответ (или подготовка к нему): кнопка отправки работает как «Стоп».
    // У генерации картинки PendingState.request равен null, поэтому стопа там нет.
    private boolean isStopMode() {
        return pendingResponse && PendingState.request != null;
    }

    // Иконка и состояние кнопки отправки: «Стоп» (квадрат) пока идёт текстовый ответ,
    // в остальных случаях самолётик (выключен, пока идёт генерация картинки)
    private void updateSendButton() {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        boolean stop = isStopMode();
        btnSend.setImageDrawable(ChatAdapter.SvgIcon.fromRaw(this,
                stop ? R.raw.ic_stop : R.raw.ic_send, dp(24), 0xFF000000));
        try {
            if (stop) btnSend.setContentDescription(isRu ? "Остановить" : "Stop");
            else btnSend.setContentDescription(isRu ? "Отправить" : "Send");
        } catch (Throwable ignored) {}
        // setEnabled вызываем после смены иконки: он затемняет текущую иконку
        btnSend.setEnabled(stop || !pendingResponse);
    }

    // Кнопка «Стоп»: обрывает запрос, то, что уже напечатано, остаётся в чате и сохраняется в историю.
    // Если ничего не пришло, заглушка ответа убирается, сообщение пользователя остаётся.
    private void stopGeneration() {
        if (!isStopMode()) return;
        AppLog.d("UI", "Generation stopped by user");

        // После cancel() колбэки запроса больше не вызываются
        GeminiApi.Request req = PendingState.request;
        if (req != null) req.cancel();

        // Оставляем то, что уже показано в чате (при плавном выводе оно может отставать от полученного).
        // "..." - заглушка до появления текста: тогда берём то, что уже пришло от модели.
        String got = PendingState.partialText != null ? PendingState.partialText : "";
        String keep = "";
        if (messages.size() > 0) {
            String shown = messages.get(messages.size() - 1).text;
            keep = shown != null ? shown : "";
        }
        if (keep.equals("...")) keep = got;

        stopReveal();
        showCompressing(false);
        pendingResponse = false;
        if (messages.size() > 0) {
            if (keep.trim().length() == 0) {
                messages.remove(messages.size() - 1);
            } else {
                messages.set(messages.size() - 1, new GeminiApi.Message("model", keep));
            }
            adapter.notifyDataSetChanged();
        }
        btnAttach.setEnabled(true);
        saveNewMessages();
        PendingState.clearResponse();
        updateSendButton();
    }

    // Запускает запрос к модели по текущей истории (последнее сообщение - от пользователя)
    private void startModelRequest(String apiKey, boolean isRu) {
        stopSpeech();
        messages.add(new GeminiApi.Message("model", "..."));
        pendingResponse = true;
        adapter.notifyDataSetChanged();
        // Отправка сообщения: всегда прокручиваем вниз
        scrollChatToBottom();

        btnAttach.setEnabled(false);

        PendingState.pending = true;
        PendingState.partialText = "";
        PendingState.finalResponse = null;
        PendingState.finalImageBase64 = null;
        PendingState.error = null;
        PendingState.pendingModel = null;
        PendingState.request = null;
        stopReveal();

        // Контекст (обрезка / сжатие) собирается в buildContextAndSend, запрос уходит в dispatchRequest
        buildContextAndSend(apiKey, isRu);

        // Запрос запущен (PendingState.request задан): кнопка отправки становится «Стоп»
        updateSendButton();
    }

    private void dispatchRequest(String apiKey, boolean isRu, String systemPrompt,
                                 List<GeminiApi.Message> historyCopy) {
        AppLog.d("UI", "Sending text message, history=" + historyCopy.size()
            + " of " + (messages.size() - 1) + ", summary=" + (ctxSummaryCount > 0 && prefs.isCompressContext()));
        PendingState.request = geminiApi.sendMessage(
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
        PendingState.request = geminiApi.sendMessage(apiKey, prefs.getModel(), 0.3f, prefs.getCompressPrompt(), request,
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
        if (!pendingResponse) return;   // ответ остановлен кнопкой «Стоп»
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
        if (!pendingResponse) return;   // ответ остановлен кнопкой «Стоп»
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
            String tag = isRu ? "[сгенерированная картинка]" : "[generated image]";
            t = t.length() > 0 ? t + " " + tag : tag;
        }
        for (int i = 0; i < m.attachments.size(); i++) {
            Attachment a = m.attachments.get(i);
            String tag = a.kind == Attachment.KIND_IMAGE
                ? (isRu ? "[изображение]" : "[image]")
                : (isRu ? "[файл: " : "[file: ") + a.name + "]";
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
            editDraftAttachments = new ArrayList<Attachment>(PendingState.pendingAttachments);
        }
        editingIndex = position;
        AppLog.d("UI", "Edit message #" + position + " of " + messages.size());

        etMessage.setText(m.text != null ? m.text : "");
        etMessage.setSelection(etMessage.getText().length());

        // Вложения возвращаются в полоску копиями: файлы старого сообщения удалятся при отправке новой версии
        List<Attachment> copies = new ArrayList<Attachment>();
        for (int i = 0; i < m.attachments.size(); i++) {
            Attachment c = m.attachments.get(i).duplicate();
            if (c != null) copies.add(c);
        }
        setPendingAttachments(copies);

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
            setPendingAttachments(editDraftAttachments);
        }
        editDraftText = "";
        editDraftAttachments = new ArrayList<Attachment>();
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

    // ---- Вложения: несколько картинок, PDF и текстовые файлы ----

    // Заменяет все прикреплённые вложения (вход в режим изменения сообщения и выход из него)
    private void setPendingAttachments(List<Attachment> list) {
        PendingState.pendingAttachments.clear();
        if (list != null) PendingState.pendingAttachments.addAll(list);
        refreshAttachPreview();
    }

    // Открывает системный выбор: images = true - картинки, false - любые файлы
    private void pickAttachment(boolean images) {
        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        int room = Attachment.MAX_COUNT - PendingState.pendingAttachments.size() - PendingState.attachLoading;
        if (room <= 0) {
            Toast.makeText(this, isRu
                ? "Максимум вложений в одном сообщении: " + Attachment.MAX_COUNT
                : "Maximum attachments per message: " + Attachment.MAX_COUNT, Toast.LENGTH_SHORT).show();
            return;
        }
        // Пока окно выбора открыто, экран может пересоздаться: текст поля сохраняем заранее
        PendingState.inputText = etMessage.getText().toString();
        if (!PickerCompat.start(this, images ? REQUEST_PICK_IMAGE : REQUEST_PICK_FILE, images)) {
            Toast.makeText(this, isRu
                ? "Не найдено приложение для выбора файлов"
                : "No app found to pick files", Toast.LENGTH_LONG).show();
        }
    }

    // Выбранные файлы из результата выбора: несколько (ClipData, с Android 4.1) или один
    private List<Uri> extractUris(Intent data) {
        List<Uri> uris = new ArrayList<Uri>();
        if (Http.sdk() >= PickerCompat.CLIP_SDK) {
            try {
                uris.addAll(PickerClip.uris(data));
            } catch (Throwable ignored) {}
        }
        if (uris.isEmpty() && data.getData() != null) uris.add(data.getData());
        return uris;
    }

    // Читает и уменьшает выбранные файлы в фоновом потоке (большие картинки и PDF быстрыми не бывают).
    // Результат попадает в PendingState, поэтому не теряется, если экран повернули.
    private void addAttachmentsFromUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return;
        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());

        int room = Attachment.MAX_COUNT - PendingState.pendingAttachments.size() - PendingState.attachLoading;
        if (room <= 0) {
            Toast.makeText(this, isRu
                ? "Максимум вложений в одном сообщении: " + Attachment.MAX_COUNT
                : "Maximum attachments per message: " + Attachment.MAX_COUNT, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Uri> use = new ArrayList<Uri>();
        for (int i = 0; i < uris.size() && i < room; i++) use.add(uris.get(i));
        if (use.size() < uris.size()) {
            Toast.makeText(this, isRu
                ? "Добавлены не все файлы: максимум вложений в одном сообщении: " + Attachment.MAX_COUNT
                : "Not all files were added: maximum attachments per message is " + Attachment.MAX_COUNT,
                Toast.LENGTH_LONG).show();
        }

        PendingState.attachLoading += use.size();
        refreshAttachPreview();

        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < use.size(); i++) {
                    Attachment att = null;
                    String err = null;
                    try {
                        att = Attachment.load(app, use.get(i), isRu);
                    } catch (Attachment.AttachException e) {
                        err = e.getMessage();
                    } catch (Throwable t) {
                        AppLog.e("UI", "Attachment load failed", t);
                        err = (isRu ? "Ошибка: " : "Error: ") + t;
                    }
                    final Attachment doneAtt = att;
                    final String doneErr = err;
                    PendingState.MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            finishAttachLoad(app, doneAtt, doneErr);
                        }
                    });
                }
            }
        }).start();
    }

    // Вызывается в главном потоке, когда одно вложение прочитано (att) или не удалось (err).
    // Статический, чтобы работать, даже если Activity между тем пересоздали.
    private static void finishAttachLoad(android.content.Context app, Attachment att, String err) {
        if (PendingState.attachLoading > 0) PendingState.attachLoading--;
        if (att != null) PendingState.pendingAttachments.add(att);
        if (err != null) Toast.makeText(app, err, Toast.LENGTH_LONG).show();
        Runnable r = PendingState.attachRefresh;
        if (r != null) r.run();
    }

    // Перестраивает полоску вложений: плитки рядами по ширине экрана, последняя плитка "..." пока идёт загрузка
    private void refreshAttachPreview() {
        if (attachStrip == null || attachPreviewLayout == null) return;
        attachStrip.removeAllViews();
        List<Attachment> list = PendingState.pendingAttachments;
        int total = list.size() + (PendingState.attachLoading > 0 ? 1 : 0);
        if (total == 0) {
            attachPreviewLayout.setVisibility(View.GONE);
            return;
        }

        int tile = dp(72);
        int gap = dp(3);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int cols = Math.max(2, (screenW - dp(12)) / (tile + gap * 2));
        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                attachStrip.addView(row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            View v = i < list.size() ? makeAttachTile(list.get(i)) : makeLoadingTile();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tile, tile);
            lp.setMargins(gap, gap, gap, gap);
            row.addView(v, lp);
        }

        // Полоска не больше двух рядов в высоту, остальное прокручивается
        int rows = (total + cols - 1) / cols;
        android.view.ViewGroup.LayoutParams p = attachPreviewLayout.getLayoutParams();
        if (p != null) {
            p.height = Math.min(rows, 2) * (tile + gap * 2) + dp(12);
            attachPreviewLayout.setLayoutParams(p);
        }
        attachPreviewLayout.setVisibility(View.VISIBLE);
    }

    // Плитка одного вложения: миниатюра картинки или расширение и имя файла, в углу кнопка «✕»
    private View makeAttachTile(final Attachment att) {
        final int fill = android.view.ViewGroup.LayoutParams.FILL_PARENT;
        android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
        tile.setBackgroundColor(0xFF444444);

        if (att.kind == Attachment.KIND_IMAGE) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap thumb = att.getThumb();
            if (thumb != null) iv.setImageBitmap(thumb);
            tile.addView(iv, new android.widget.FrameLayout.LayoutParams(fill, fill));
        } else {
            TextView tv = new TextView(this);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(11);
            tv.setPadding(dp(4), dp(14), dp(4), dp(2));
            tv.setMaxLines(4);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            tv.setText(att.extLabel() + "\n" + att.name);
            tile.addView(tv, new android.widget.FrameLayout.LayoutParams(fill, fill));
        }

        TextView remove = new TextView(this);
        remove.setText("✕");
        remove.setTextColor(0xFFFFFFFF);
        remove.setTextSize(12);
        remove.setGravity(android.view.Gravity.CENTER);
        remove.setBackgroundColor(0xAA000000);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PendingState.pendingAttachments.remove(att);
                refreshAttachPreview();
            }
        });
        tile.addView(remove, new android.widget.FrameLayout.LayoutParams(
                dp(24), dp(24), android.view.Gravity.TOP | android.view.Gravity.RIGHT));
        return tile;
    }

    private View makeLoadingTile() {
        TextView tv = new TextView(this);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackgroundColor(0xFF333333);
        tv.setTextColor(0xFFAAAAAA);
        tv.setText("...");
        return tv;
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
        btnAttach.setEnabled(false);
        pendingResponse = true;
        PendingState.request = null;   // картинку останавливать нельзя: кнопка отправки просто выключается
        updateSendButton();

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
        stopSpeech();
        saveNewMessages();
        exitEditMode(false);
        resetSummaryState();
        messages.clear();
        adapter.notifyDataSetChanged();
        currentChatId = -1;
        PendingState.clearAll();
        refreshAttachPreview();
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

        Button btnFile = new Button(this);
        btnFile.setText(ru ? "Файл (PDF, текст, код)" : "File (PDF, text, code)");
        box.addView(btnFile);

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
                pickAttachment(true);
            }
        });
        btnFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                pickAttachment(false);
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