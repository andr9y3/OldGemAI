package com.geminiapp.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
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

public class MainActivity extends Activity {

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final int MAX_IMAGE_SIZE = 800; // макс пикселей по длинной стороне
    private static final int JPEG_QUALITY = 75;

    private Prefs prefs;
    private List<GeminiApi.Message> messages;
    private ChatAdapter adapter;
    private GeminiApi geminiApi;
    private Handler mainHandler;

    private ListView lvChat;
    private EditText etMessage;
    private Button btnSend;
    private Button btnAttach;
    private TextView tvNoKeyBanner;
    private ImageView ivPreview;
    private LinearLayout attachPreviewLayout;

    // Прикреплённое изображение
    private String pendingImageBase64 = null;
    private String pendingImageMime = "image/jpeg";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        geminiApi = new GeminiApi();
        mainHandler = new Handler(Looper.getMainLooper());
        messages = new ArrayList<GeminiApi.Message>();

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        tvNoKeyBanner = new TextView(this);
        tvNoKeyBanner.setBackgroundColor(0xFFFFCCCC);
        tvNoKeyBanner.setTextColor(0xFF000000);
        tvNoKeyBanner.setPadding(15, 15, 15, 15);
        mainLayout.addView(tvNoKeyBanner);

        lvChat = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, 0, 1.0f);
        mainLayout.addView(lvChat, listParams);

        // Панель превью прикреплённой картинки
        attachPreviewLayout = new LinearLayout(this);
        attachPreviewLayout.setOrientation(LinearLayout.HORIZONTAL);
        attachPreviewLayout.setPadding(10, 4, 10, 4);
        attachPreviewLayout.setBackgroundColor(0xFF222222);
        attachPreviewLayout.setVisibility(View.GONE);

        ivPreview = new ImageView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(60), dp(60));
        previewParams.setMargins(0, 0, 10, 0);
        attachPreviewLayout.addView(ivPreview, previewParams);

        Button btnRemoveImage = new Button(this);
        btnRemoveImage.setText("✕");
        attachPreviewLayout.addView(btnRemoveImage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainLayout.addView(attachPreviewLayout);

        // Панель ввода
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

        btnSend = new Button(this);
        inputLayout.addView(btnSend, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        mainLayout.addView(inputLayout);
        setContentView(mainLayout);

        adapter = new ChatAdapter(this, messages);
        lvChat.setAdapter(adapter);

        updateBanner();

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

        btnRemoveImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pendingImageBase64 = null;
                attachPreviewLayout.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;

                // Определяем MIME тип
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "image/jpeg";
                pendingImageMime = mime;

                // Читаем и сжимаем
                Bitmap original = BitmapFactory.decodeStream(is);
                is.close();
                if (original == null) {
                    Toast.makeText(this, "Не удалось прочитать изображение", Toast.LENGTH_SHORT).show();
                    return;
                }

                Bitmap scaled = scaleBitmap(original);
                original.recycle();

                // Кодируем в base64
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                Bitmap.CompressFormat fmt = mime.contains("png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                scaled.compress(fmt, JPEG_QUALITY, baos);
                byte[] bytes = baos.toByteArray();
                pendingImageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);

                // Показываем превью
                ivPreview.setImageBitmap(scaled);
                attachPreviewLayout.setVisibility(View.VISIBLE);

            } catch (Exception e) {
                Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
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

    private void updateBanner() {
        boolean hasKey = !TextUtils.isEmpty(prefs.getApiKey());
        tvNoKeyBanner.setVisibility(hasKey ? View.GONE : View.VISIBLE);

        boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        tvNoKeyBanner.setText(isRu
            ? "API ключ не задан - ИИ не будет работать. Добавьте ключ в настройках."
            : "No API key set - AI will not work. Add your key in Settings.");

        btnSend.setText(isRu ? "Отправить" : "Send");
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

        // Команда /image <промпт>
        if (text.toLowerCase().startsWith("/image ") || text.toLowerCase().equals("/image")) {
            final String prompt = text.length() > 7 ? text.substring(7).trim() : "";
            if (prompt.isEmpty()) {
                Toast.makeText(this, isRu ? "Укажите описание: /image кот в космосе" : "Add a description: /image cat in space", Toast.LENGTH_SHORT).show();
                return;
            }
            etMessage.setText("");
            messages.add(new GeminiApi.Message("user", text));
            messages.add(new GeminiApi.Message("model", isRu ? "Генерация картинки..." : "Generating image..."));
            adapter.notifyDataSetChanged();
            lvChat.setSelection(messages.size() - 1);
            btnSend.setEnabled(false);
            btnAttach.setEnabled(false);

            geminiApi.generateImage(apiKey, prompt, new GeminiApi.ImageCallback() {
                @Override
                public void onSuccess(final String base64png) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            messages.set(messages.size() - 1, new GeminiApi.Message("model", "", base64png, true));
                            adapter.notifyDataSetChanged();
                            lvChat.setSelection(messages.size() - 1);
                            btnSend.setEnabled(true);
                            btnAttach.setEnabled(true);
                        }
                    });
                }
                @Override
                public void onError(final String error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            messages.remove(messages.size() - 1);
                            adapter.notifyDataSetChanged();
                            btnSend.setEnabled(true);
                            btnAttach.setEnabled(true);
                            Toast.makeText(MainActivity.this, (isRu ? "Ошибка: " : "Error: ") + error, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
            return;
        }

        final String imageBase64 = pendingImageBase64;
        final String imageMime = pendingImageMime;

        // Добавляем сообщение пользователя
        GeminiApi.Message userMsg = hasImage
            ? new GeminiApi.Message("user", text, imageBase64, imageMime)
            : new GeminiApi.Message("user", text);
        messages.add(userMsg);
        adapter.notifyDataSetChanged();
        lvChat.setSelection(messages.size() - 1);
        etMessage.setText("");

        // Сбрасываем прикреплённое фото
        pendingImageBase64 = null;
        attachPreviewLayout.setVisibility(View.GONE);

        messages.add(new GeminiApi.Message("model", "..."));
        adapter.notifyDataSetChanged();

        btnSend.setEnabled(false);
        btnAttach.setEnabled(false);

        final List<GeminiApi.Message> historyCopy = new ArrayList<GeminiApi.Message>();
        for (int i = 0; i < messages.size() - 1; i++) {
            historyCopy.add(messages.get(i));
        }

        geminiApi.sendMessage(
            apiKey,
            prefs.getModel(),
            prefs.getTemperature(),
            prefs.getSystemPrompt(),
            historyCopy,
            new GeminiApi.Callback() {
                @Override
                public void onSuccess(final String response) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (messages.size() > 0) {
                                messages.set(messages.size() - 1, new GeminiApi.Message("model", response));
                                adapter.notifyDataSetChanged();
                                lvChat.setSelection(messages.size() - 1);
                            }
                            btnSend.setEnabled(true);
                            btnAttach.setEnabled(true);
                        }
                    });
                }

                @Override
                public void onError(final String error) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (messages.size() > 0) {
                                messages.remove(messages.size() - 1);
                                adapter.notifyDataSetChanged();
                            }
                            btnSend.setEnabled(true);
                            btnAttach.setEnabled(true);
                            Toast.makeText(MainActivity.this, (isRu ? "Ошибка: " : "Error: ") + error, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        );
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
            messages.clear();
            adapter.notifyDataSetChanged();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int val) {
        float density = getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}
