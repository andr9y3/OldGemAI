package com.geminiapp.chat;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.util.ArrayList;

/**
 * Голосовой ввод через системное окно распознавания речи (RecognizerIntent, Android 1.5+).
 * Разрешение на микрофон приложению не нужно: звук слушает приложение распознавания (обычно Google).
 * Весь код с RecognizerIntent вынесен сюда, чтобы MainActivity не ссылалась на класс,
 * которого нет на Android 1.0-1.1.
 */
public final class VoiceInput {

    private VoiceInput() {}

    /** Окно распознавания открыто, результат придёт в onActivityResult. */
    public static final int STARTED = 0;
    /** Версия Android не поддерживает голосовой ввод (RecognizerIntent есть с API 3). */
    public static final int UNSUPPORTED_SDK = 1;
    /** На устройстве нет приложения, которое умеет распознавать речь. */
    public static final int NO_RECOGNIZER = 2;
    /** Прочий сбой запуска. */
    public static final int FAILED = 3;

    /** Минимальный API: RecognizerIntent появился в Android 1.5. */
    public static final int MIN_SDK = 3;

    public static boolean isSupported() {
        return Http.sdk() >= MIN_SDK;
    }

    /**
     * Открывает окно распознавания речи; результат придёт в onActivityResult с этим requestCode.
     * Версию Android проверяет сам: на неподдерживаемой записывать не пытается и возвращает UNSUPPORTED_SDK.
     */
    public static int start(Activity activity, int requestCode, boolean ru) {
        if (!isSupported()) {
            AppLog.w("UI", "Voice input refused: API " + Http.sdk() + " < " + MIN_SDK);
            return UNSUPPORTED_SDK;
        }
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, ru ? "ru-RU" : "en-US");
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_PROMPT, ru ? "Говорите" : "Speak now");
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.getPackageName());
            activity.startActivityForResult(i, requestCode);
            return STARTED;
        } catch (ActivityNotFoundException e) {
            AppLog.w("UI", "Voice input: no speech recognizer app");
            return NO_RECOGNIZER;
        } catch (Throwable t) {
            AppLog.w("UI", "Voice input failed to start: " + t);
            return FAILED;
        }
    }

    /**
     * Текст ошибки по коду результата окна распознавания или null, если это не ошибка
     * (RESULT_CANCELED - пользователь закрыл окно, RESULT_OK - всё хорошо).
     */
    public static String errorText(int resultCode, boolean ru) {
        if (resultCode == Activity.RESULT_OK || resultCode == Activity.RESULT_CANCELED) return null;
        if (resultCode == RecognizerIntent.RESULT_NO_MATCH) {
            return ru ? "Речь не распознана" : "Speech was not recognized";
        }
        if (resultCode == RecognizerIntent.RESULT_AUDIO_ERROR) {
            return ru ? "Не удалось записать звук, проверьте микрофон" : "Could not record audio, check the microphone";
        }
        if (resultCode == RecognizerIntent.RESULT_NETWORK_ERROR || resultCode == RecognizerIntent.RESULT_SERVER_ERROR) {
            return ru ? "Ошибка сети или сервера распознавания речи" : "Speech recognition network or server error";
        }
        return ru ? "Ошибка распознавания речи" : "Speech recognition error";
    }

    /** Распознанный текст из результата (лучший вариант) или пустая строка. */
    public static String extractText(Intent data) {
        if (!isSupported() || data == null) return "";
        try {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && results.size() > 0 && results.get(0) != null) {
                return results.get(0).trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
