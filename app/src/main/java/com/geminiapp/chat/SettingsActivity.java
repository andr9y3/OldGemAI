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
    private Spinner spinnerImageModel;
    private android.widget.CheckBox cbRememberImage;
    private List<String> imageModelList = new ArrayList<String>();
    private ArrayAdapter<String> imageModelAdapter;
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
        etApiKey.setSingleLine(true);
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

        TextView labelImageModel = new TextView(this);
        labelImageModel.setText(isRu ? "Модель для картинок" : "Image model");
        layout.addView(labelImageModel);

        TextView tvImageWarn = new TextView(this);
        tvImageWarn.setText(isRu
            ? "На бесплатном тарифе (Free) ни одна модель не может генерировать картинки."
            : "On the Free plan no model can generate images.");
        tvImageWarn.setTextColor(0xFFFF8800);
        tvImageWarn.setPadding(0, 4, 0, 4);
        layout.addView(tvImageWarn);

        spinnerImageModel = new Spinner(this);
        layout.addView(spinnerImageModel);

        cbRememberImage = new android.widget.CheckBox(this);
        cbRememberImage.setText(isRu ? "Запомнить выбор (не спрашивать при /image)"
                                     : "Remember choice (do not ask on /image)");
        cbRememberImage.setChecked(prefs.isImageRemember());
        layout.addView(cbRememberImage);

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
        
        layout.addView(etSystemPrompt);

        final android.widget.CheckBox cbStream = new android.widget.CheckBox(this);
        cbStream.setText(isRu ? "Постепенный вывод ответа (выключите для старого варианта)"
                              : "Stream response (turn off for the old way)");
        cbStream.setChecked(prefs.isStream());
        layout.addView(cbStream);

        Button btnSave = new Button(this);
        btnSave.setText(isRu ? "Сохранить" : "Save");
        layout.addView(btnSave);

        TextView tvGithub = new TextView(this);
        tvGithub.setPadding(0, 20, 0, 10);
        layout.addView(tvGithub);

        TextView tvBug = new TextView(this);
        tvBug.setTextColor(0xFF33B5E5);
        layout.addView(tvBug);

        TextView tvVersion = new TextView(this);
        tvVersion.setTextColor(0xFF888888);
        tvVersion.setPadding(0, 20, 0, 10);
        layout.addView(tvVersion);

        setContentView(scrollView);

        // Инициализируем адаптер с текущей моделью как заглушкой
        modelList.add(prefs.getModel());
        modelAdapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, modelList);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerModel.setAdapter(modelAdapter);

        // Модели для картинок: пока список не загружен, показываем сохранённую модель
        imageModelList.add("");
        String savedImageModel = prefs.getImageModel();
        if (savedImageModel.length() > 0) imageModelList.add(savedImageModel);
        imageModelAdapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, imageModelList);
        imageModelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerImageModel.setAdapter(imageModelAdapter);
        if (savedImageModel.length() > 0) spinnerImageModel.setSelection(1);

        // Галка "Запомнить" без выбранной модели не имеет смысла; при включённой галке список заблокирован
        if (savedImageModel.length() == 0) cbRememberImage.setChecked(false);
        spinnerImageModel.setEnabled(!cbRememberImage.isChecked());
        cbRememberImage.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                if (checked && spinnerImageModel.getSelectedItemPosition() <= 0) {
                    b.setChecked(false);
                    Toast.makeText(SettingsActivity.this, isRu ? "Сначала выберите модель" : "Select a model first", Toast.LENGTH_SHORT).show();
                    return;
                }
                spinnerImageModel.setEnabled(!checked);
            }
        });

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
                if (key.length() == 0) {
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
                                String curImg = prefs.getImageModel();
                                imageModelList.clear();
                                imageModelList.add("");
                                int imgSel = 0;
                                for (int i = 0; i < models.size(); i++) {
                                    String m = models.get(i);
                                    if (m.contains("image")) {
                                        imageModelList.add(m);
                                        if (m.equals(curImg)) imgSel = imageModelList.size() - 1;
                                    }
                                }
                                if (curImg.length() > 0 && imgSel == 0) {
                                    imageModelList.add(curImg);
                                    imgSel = imageModelList.size() - 1;
                                }
                                imageModelAdapter.notifyDataSetChanged();
                                spinnerImageModel.setSelection(imgSel);
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
                prefs.setStream(cbStream.isChecked());
                int imgPos = spinnerImageModel.getSelectedItemPosition();
                prefs.setImageModel(imgPos > 0 && imgPos < imageModelList.size() ? imageModelList.get(imgPos) : "");
                prefs.setImageRemember(cbRememberImage.isChecked() && imgPos > 0);

                boolean nowRu = Prefs.LANG_RU.equals(prefs.getLanguage());
                Toast.makeText(SettingsActivity.this, nowRu ? "Сохранено" : "Saved", Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        String versionName = "?";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        tvVersion.setText((isRu ? "Версия: " : "Version: ") + versionName);

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
