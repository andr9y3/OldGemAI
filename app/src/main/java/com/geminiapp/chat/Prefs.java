package com.geminiapp.chat;

import android.content.Context;
import android.content.SharedPreferences;

public class Prefs {
    private static final String NAME = "gemini_prefs";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";
    private static final String KEY_TEMPERATURE = "temperature";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_STREAM = "stream_response";
    private static final String KEY_WOLFIUS_HINT = "wolfius_hint_shown";
    private static final String KEY_IMAGE_MODEL = "image_model";
    private static final String KEY_IMAGE_REMEMBER = "image_model_remember";
    private static final String KEY_SEARCH = "google_search";
    private static final String KEY_FALLBACK_MODEL = "fallback_model";
    private static final String KEY_FALLBACK_FOR = "fallback_for";
    private static final String KEY_FALLBACK_UNTIL = "fallback_until";
    private static final long FALLBACK_TTL = 5 * 60 * 1000L;

    public static final String LANG_RU = "ru";
    public static final String LANG_EN = "en";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean isSetupDone() {
        return prefs.getBoolean(KEY_SETUP_DONE, false);
    }

    public void setSetupDone(boolean done) {
        prefs.edit().putBoolean(KEY_SETUP_DONE, done).commit();
    }

    public boolean isWolfiusHintShown() {
        return prefs.getBoolean(KEY_WOLFIUS_HINT, false);
    }

    public void setWolfiusHintShown(boolean shown) {
        prefs.edit().putBoolean(KEY_WOLFIUS_HINT, shown).commit();
    }

    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, null);
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANGUAGE, lang).commit();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key).commit();
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, "gemini-2.5-flash");
    }

    public void setModel(String model) {
        prefs.edit().putString(KEY_MODEL, model).commit();
    }

    public float getTemperature() {
        return prefs.getFloat(KEY_TEMPERATURE, 0.7f);
    }

    public void setTemperature(float temp) {
        prefs.edit().putFloat(KEY_TEMPERATURE, temp).commit();
    }

    // true - ответ печатается постепенно, false - сразу целиком (старый вариант)
    public boolean isStream() {
        return prefs.getBoolean(KEY_STREAM, true);
    }

    public void setStream(boolean stream) {
        prefs.edit().putBoolean(KEY_STREAM, stream).commit();
    }

    // Модель для генерации картинок, которая в последний раз успешно сработала
    public String getImageModel() {
        return prefs.getString(KEY_IMAGE_MODEL, "");
    }

    public void setImageModel(String model) {
        prefs.edit().putString(KEY_IMAGE_MODEL, model).commit();
    }

    // Модель, с которой стоит начать запрос: запомненная резервная (если основная недавно была
    // перегружена и выбор в настройках не менялся) или основная из настроек
    public String getActiveModel() {
        String main = getModel();
        String fb = prefs.getString(KEY_FALLBACK_MODEL, "");
        String forModel = prefs.getString(KEY_FALLBACK_FOR, "");
        long until = prefs.getLong(KEY_FALLBACK_UNTIL, 0);
        if (fb.length() > 0 && forModel.equals(main) && System.currentTimeMillis() < until) {
            return fb;
        }
        return main;
    }

    public void setFallbackModel(String model, String forModel) {
        prefs.edit()
            .putString(KEY_FALLBACK_MODEL, model)
            .putString(KEY_FALLBACK_FOR, forModel)
            .putLong(KEY_FALLBACK_UNTIL, System.currentTimeMillis() + FALLBACK_TTL)
            .commit();
    }

    public void clearFallbackModel() {
        prefs.edit()
            .remove(KEY_FALLBACK_MODEL)
            .remove(KEY_FALLBACK_FOR)
            .remove(KEY_FALLBACK_UNTIL)
            .commit();
    }

    // true - выбранную модель для картинок запоминаем и /image больше не спрашивает модель
    public boolean isImageRemember() {
        return prefs.getBoolean(KEY_IMAGE_REMEMBER, false);
    }

    public void setImageRemember(boolean remember) {
        prefs.edit().putBoolean(KEY_IMAGE_REMEMBER, remember).commit();
    }

    // true - к запросу к Gemini подключается поиск Google (grounding) и в ответе показываются источники
    public boolean isSearch() {
        return prefs.getBoolean(KEY_SEARCH, false);
    }

    public void setSearch(boolean search) {
        prefs.edit().putBoolean(KEY_SEARCH, search).commit();
    }

    public String getSystemPrompt() {
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public void setSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).commit();
    }
}
