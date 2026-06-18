package com.geminiapp.chat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SetupApiKeyActivity extends Activity {

    private Prefs prefs;
    private EditText etApiKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());
        setTitle(isRu ? "Настройка API Ключа" : "Setup API Key");

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scrollView.addView(layout);

        TextView tvInstruction = new TextView(this);
        tvInstruction.setTextSize(16);
        if (isRu) {
            tvInstruction.setText(
                "Как получить API ключ:\n\n" +
                "1. Перейдите на сайт aistudio.google.com/api-keys\n" +
                "2. Войдите в аккаунт Google\n" +
                "3. Нажмите кнопку \"Create API key\"\n" +
                "4. Выберите проект или создайте новый\n" +
                "5. Скопируйте полученный ключ\n" +
                "6. Вставьте его в поле ниже\n\n" +
                "Бесплатный тариф доступен без оплаты.\n" +
                "Ключ начинается с \"AIza...\"\n");
        } else {
            tvInstruction.setText(
                "How to get an API key:\n\n" +
                "1. Go to aistudio.google.com/api-keys\n" +
                "2. Sign in with your Google account\n" +
                "3. Click the \"Create API key\" button\n" +
                "4. Select a project or create a new one\n" +
                "5. Copy the generated key\n" +
                "6. Paste it in the field below\n\n" +
                "The free tier is available without payment.\n" +
                "The key starts with \"AIza...\"\n");
        }
        layout.addView(tvInstruction);

        TextView tvOpenAiStudio = new TextView(this);
        tvOpenAiStudio.setText(isRu ? "Открыть AI Studio в браузере" : "Open AI Studio in browser");
        tvOpenAiStudio.setTextColor(0xFF33B5E5);
        tvOpenAiStudio.setPadding(0, 0, 0, 30);
        layout.addView(tvOpenAiStudio);

        etApiKey = new EditText(this);
        etApiKey.setHint(isRu ? "Вставьте API ключ сюда" : "Paste API key here");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(etApiKey);

        Button btnSave = new Button(this);
        btnSave.setText(isRu ? "Сохранить и продолжить" : "Save and continue");
        layout.addView(btnSave);

        Button btnLater = new Button(this);
        btnLater.setText(isRu ? "Настроить позже" : "Setup later");
        layout.addView(btnLater);

        setContentView(scrollView);

        tvOpenAiStudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/api-keys"));
                startActivity(intent);
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = etApiKey.getText().toString().trim();
                if (TextUtils.isEmpty(key)) {
                    Toast.makeText(SetupApiKeyActivity.this, isRu ? "Введите API ключ" : "Enter API key", Toast.LENGTH_SHORT).show();
                    return;
                }
                prefs.setApiKey(key);
                prefs.setSetupDone(true);
                goToMain();
            }
        });

        btnLater.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setSetupDone(true);
                goToMain();
            }
        });
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}