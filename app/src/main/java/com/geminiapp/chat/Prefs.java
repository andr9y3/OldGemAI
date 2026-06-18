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
        prefs.edit().putBoolean(KEY_SETUP_DONE, done).apply();
    }

    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, null);
    }

    public void setLanguage(String lang) {
        prefs.edit().putString(KEY_LANGUAGE, lang).apply();
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key).apply();
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, "gemini-2.5-flash");
    }

    public void setModel(String model) {
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    public float getTemperature() {
        return prefs.getFloat(KEY_TEMPERATURE, 0.7f);
    }

    public void setTemperature(float temp) {
        prefs.edit().putFloat(KEY_TEMPERATURE, temp).apply();
    }

    public String getSystemPrompt() {
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public void setSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }
}
