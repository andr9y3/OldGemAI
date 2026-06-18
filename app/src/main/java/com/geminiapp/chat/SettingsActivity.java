package com.geminiapp.chat;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {

    private Prefs prefs;
    private EditText etApiKey;
    private Spinner spinnerModel;
    private Spinner spinnerLanguage;
    private SeekBar seekTemperature;
    private TextView tvTemperatureValue;
    private EditText etSystemPrompt;
    private TextView tvModelStatus;
    private Button btnLoadModels;
    private List<String> modelList = new ArrayList<String>();
    private ArrayAdapter<String> modelAdapter;
    private Handler mainHandler;
    private GeminiApi geminiApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = new Prefs(this);
        mainHandler = new Handler(Looper.getMainLooper());
        geminiApi = new GeminiApi();
        final boolean isRu = Prefs.LANG_RU.equals(prefs.getLanguage());

        setTitle(isRu ? "Настройки" : "Settings");

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        scrollView.addView(layout);

        TextView labelKey = new TextView(this);
        labelKey.setText(isRu ? "API ключ" : "API Key");
        layout.addView(labelKey);

        etApiKey = new EditText(this);
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(etApiKey);

        TextView labelModel = new TextView(this);
        labelModel.setText(isRu ? "Модель" : "Model");
        layout.addView(labelModel);

        tvModelStatus = new TextView(this);
        tvModelStatus.setPadding(0, 4, 0, 4);
        layout.addView(tvModelStatus);

        btnLoadModels = new Button(this);
        btnLoadModels.setText(isRu ? "Обновить список моделей" : "Refresh model list");
        layout.addView(btnLoadModels);

        spinnerModel = new Spinner(this);
        layout.addView(spinnerModel);

        TextView labelLang = new TextView(this);
        labelLang.setText(isRu ? "Язык" : "Language");
        layout.addView(labelLang);

        spinnerLanguage = new Spinner(this);
        layout.addView(spinnerLanguage);

        TextView labelTemp = new TextView(this);
        labelTemp.setText(isRu ? "Температура" : "Temperature");
        layout.addView(labelTemp);

        tvTemperatureValue = new TextView(this);
        layout.addView(tvTemperatureValue);

        seekTemperature = new SeekBar(this);
        layout.addView(seekTemperature);

        TextView labelPrompt = new TextView(this);
        labelPrompt.setText(isRu ? "Системный промпт" : "System prompt");
        layout.addView(labelPrompt);

        etSystemPrompt = new EditText(this);
        etSystemPrompt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        layout.addView(etSystemPrompt);

        Button btnSave = new Button(this);
        btnSave.setText(isRu ? "Сохранить" : "Save");
        layout.addView(btnSave);

        TextView tvGithub = new TextView(this);
        tvGithub.setPadding(0, 20, 0, 10);
        layout.addView(tvGithub);

        TextView tvBug = new TextView(this);
        tvBug.setTextColor(0xFF33B5E5);
        layout.addView(tvBug);

        setContentView(scrollView);

        // Инициализируем адаптер с текущей моделью как заглушкой
        modelList.add(prefs.getModel());
        modelAdapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, modelList);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(modelAdapter);

        etApiKey.setText(prefs.getApiKey());
        etSystemPrompt.setText(prefs.getSystemPrompt());

        String[] langs = isRu ? new String[]{"Русский", "English"} : new String[]{"Russian", "English"};
        ArrayAdapter<String> langAdapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, langs);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(langAdapter);
        spinnerLanguage.setSelection(Prefs.LANG_RU.equals(prefs.getLanguage()) ? 0 : 1);

        float temp = prefs.getTemperature();
        seekTemperature.setMax(20);
        seekTemperature.setProgress((int)(temp * 10));
        tvTemperatureValue.setText(String.valueOf(temp));
        seekTemperature.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvTemperatureValue.setText(String.valueOf(progress / 10f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        final Runnable loadModels = new Runnable() {
            @Override
            public void run() {
                final String key = etApiKey.getText().toString().trim();
                if (key.isEmpty()) {
                    tvModelStatus.setText(isRu ? "Введите API ключ для загрузки моделей" : "Enter API key to load models");
                    return;
                }
                tvModelStatus.setText(isRu ? "Загрузка..." : "Loading...");
                btnLoadModels.setEnabled(false);
                geminiApi.listModels(key, new GeminiApi.ModelsCallback() {
                    @Override
                    public void onSuccess(final List<String> models) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                modelList.clear();
                                modelList.addAll(models);
                                modelAdapter.notifyDataSetChanged();
                                String cur = prefs.getModel();
                                for (int i = 0; i < modelList.size(); i++) {
                                    if (modelList.get(i).equals(cur)) {
                                        spinnerModel.setSelection(i);
                                        break;
                                    }
                                }
                                tvModelStatus.setText((isRu ? "Найдено моделей: " : "Models found: ") + models.size());
                                btnLoadModels.setEnabled(true);
                            }
                        });
                    }
                    @Override
                    public void onError(final String error) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                tvModelStatus.setText((isRu ? "Ошибка: " : "Error: ") + error);
                                btnLoadModels.setEnabled(true);
                            }
                        });
                    }
                });
            }
        };

        btnLoadModels.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadModels.run();
            }
        });

        // Автоматически загружаем модели при открытии
        loadModels.run();

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.setApiKey(etApiKey.getText().toString().trim());
                prefs.setModel(modelList.isEmpty() ? "" : modelList.get(spinnerModel.getSelectedItemPosition()));
                prefs.setLanguage(spinnerLanguage.getSelectedItemPosition() == 0 ? Prefs.LANG_RU : Prefs.LANG_EN);
                prefs.setTemperature(seekTemperature.getProgress() / 10f);
                prefs.setSystemPrompt(etSystemPrompt.getText().toString().trim());

                boolean nowRu = Prefs.LANG_RU.equals(prefs.getLanguage());
                Toast.makeText(SettingsActivity.this, nowRu ? "Сохранено" : "Saved", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        tvGithub.setText("GitHub: github.com/andr9y3/OldGemAI");
        tvGithub.setTextColor(0xFF33B5E5);
        tvGithub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/andr9y3/OldGemAI/"));
                startActivity(intent);
            }
        });

        tvBug.setText(isRu ? "Сообщить о баге: @ialwaysloveyou0" : "Report a bug: @ialwaysloveyou0");
        tvBug.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ialwaysloveyou0"));
                startActivity(intent);
            }
        });
    }
}
