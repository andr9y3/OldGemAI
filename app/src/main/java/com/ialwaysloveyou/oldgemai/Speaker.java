package com.ialwaysloveyou.oldgemai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Озвучивание ответов через системный синтез речи (android.speech.tts.TextToSpeech, Android 1.6+).
 * Используется тот движок, который выбран в системе (Google, Samsung, eSpeak, RHVoice и любой другой),
 * к сервисам Google приложение не привязано.
 * Весь код с TextToSpeech вынесен сюда, чтобы MainActivity не ссылалась на класс,
 * которого нет на Android 1.0-1.5: версию Android MainActivity проверяет раньше, чем трогает Speaker.
 */
public final class Speaker {

    private Speaker() {}

    /** Минимальный API: TextToSpeech появился в Android 1.6. */
    public static final int MIN_SDK = 4;

    /** Озвучивание началось. */
    public static final int STARTED = 0;
    /** Шло озвучивание, повторное нажатие его остановило. */
    public static final int STOPPED = 1;
    /** Версия Android не поддерживает синтез речи. */
    public static final int UNSUPPORTED_SDK = 2;
    /** В системе нет движка синтеза речи или он не запустился. */
    public static final int NO_ENGINE = 3;
    /** Движок не знает нужный язык или у него нет голосовых данных этого языка. */
    public static final int LANGUAGE_UNAVAILABLE = 4;
    /** После очистки разметки читать нечего. */
    public static final int NOTHING_TO_SPEAK = 5;
    /** Прочий сбой. */
    public static final int FAILED = 6;

    public interface Listener {
        /** Вызывается в главном потоке. */
        void onResult(int code);
    }

    // Длинный текст режется на куски: у движков есть предел длины одного запроса
    // (TextToSpeech.getMaxSpeechInputLength() = 4000 с API 18), старые движки подавится и раньше
    private static final int CHUNK = 1500;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static TextToSpeech tts;
    private static boolean ready = false;
    private static boolean initializing = false;
    private static int generation = 0;

    // Текст, поставленный в очередь, пока движок запускается
    private static String pendingText;
    private static boolean pendingRu;
    private static Listener pendingListener;

    public static boolean isSupported() {
        return Http.sdk() >= MIN_SDK;
    }

    /**
     * Нажатие на кнопку озвучивания: если сейчас идёт озвучивание, останавливает его,
     * иначе читает text. Версию Android проверяет сам и на неподдерживаемой возвращает UNSUPPORTED_SDK.
     */
    public static void toggle(Context ctx, String text, boolean ru, Listener l) {
        if (!isSupported()) {
            AppLog.w("UI", "Speech refused: API " + Http.sdk() + " < " + MIN_SDK);
            report(l, UNSUPPORTED_SDK);
            return;
        }
        try {
            if (ready && tts != null && tts.isSpeaking()) {
                tts.stop();
                report(l, STOPPED);
                return;
            }
            String plain = plainText(text);
            if (plain.length() == 0) {
                report(l, NOTHING_TO_SPEAK);
                return;
            }
            if (ready && tts != null) {
                speak(plain, ru, l);
                return;
            }
            // Движок ещё не запущен: запоминаем текст, прочитаем, когда он будет готов
            pendingText = plain;
            pendingRu = ru;
            pendingListener = l;
            if (initializing) return;
            initializing = true;
            final int myGeneration = ++generation;
            tts = new TextToSpeech(ctx.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(final int status) {
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            onInitDone(myGeneration, status);
                        }
                    });
                }
            });
        } catch (Throwable t) {
            AppLog.w("UI", "Speech failed to start: " + t);
            resetEngine();
            report(l, NO_ENGINE);
        }
    }

    /** Останавливает текущее озвучивание (движок остаётся запущенным). */
    public static void stop() {
        pendingText = null;
        pendingListener = null;
        try {
            if (ready && tts != null) tts.stop();
        } catch (Throwable ignored) {}
    }

    /** Останавливает озвучивание и освобождает движок (при закрытии приложения). */
    public static void shutdown() {
        resetEngine();
    }

    private static void resetEngine() {
        generation++;
        pendingText = null;
        pendingListener = null;
        ready = false;
        initializing = false;
        TextToSpeech old = tts;
        tts = null;
        if (old != null) {
            try { old.stop(); } catch (Throwable ignored) {}
            try { old.shutdown(); } catch (Throwable ignored) {}
        }
    }

    private static void onInitDone(int myGeneration, int status) {
        if (myGeneration != generation) return;   // движок уже сброшен
        initializing = false;
        String text = pendingText;
        boolean ru = pendingRu;
        Listener l = pendingListener;
        pendingText = null;
        pendingListener = null;
        if (status != TextToSpeech.SUCCESS || tts == null) {
            AppLog.w("UI", "Speech engine init failed, status=" + status);
            resetEngine();
            report(l, NO_ENGINE);
            return;
        }
        ready = true;
        if (text != null) speak(text, ru, l);
    }

    private static void speak(String plain, boolean ru, Listener l) {
        try {
            // Язык озвучивания - язык интерфейса приложения
            int lr = tts.setLanguage(ru ? new Locale("ru") : new Locale("en"));
            if (lr < 0) {
                AppLog.w("UI", "Speech language unavailable, result=" + lr);
                report(l, LANGUAGE_UNAVAILABLE);
                return;
            }
            List<String> chunks = split(plain);
            for (int i = 0; i < chunks.size(); i++) {
                int mode = i == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
                int r = tts.speak(chunks.get(i), mode, (HashMap<String, String>) null);
                if (r == TextToSpeech.ERROR) {
                    AppLog.w("UI", "Speech speak() returned ERROR");
                    tts.stop();
                    report(l, FAILED);
                    return;
                }
            }
            report(l, STARTED);
        } catch (Throwable t) {
            AppLog.w("UI", "Speech failed: " + t);
            report(l, FAILED);
        }
    }

    private static void report(final Listener l, final int code) {
        if (l == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                l.onResult(code);
            }
        });
    }

    /**
     * Убирает из ответа Markdown, чтобы движок не читал звёздочки, решётки и адреса:
     * блоки кода и картинки пропускаются, у ссылок остаётся только текст, голые адреса убираются.
     */
    static String plainText(String s) {
        if (s == null) return "";
        String t = s.replace("\r", "");
        t = t.replaceAll("(?s)```.*?(```|$)", " ");
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");
        t = t.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("https?://\\S+", " ");
        t = t.replaceAll("(?m)^[ \\t]*([-*_][ \\t]*){3,}$", " ");
        t = t.replaceAll("(?m)^[ \\t|:\\-]*-[ \\t|:\\-]*$", " ");
        t = t.replaceAll("(?m)^[ \\t]*[-+*][ \\t]+", "");
        t = t.replaceAll("(?m)^[ \\t]*#{1,6}[ \\t]+", "");
        t = t.replaceAll("(?m)^[ \\t]*>[ \\t]?", "");
        t = t.replaceAll("[`*_~|#]+", " ");
        t = t.replaceAll("[ \\t]+", " ");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.trim();
    }

    // Делит текст на куски не длиннее CHUNK, по возможности по концу строки или предложения
    private static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        String[] delims = new String[]{"\n", ". ", "! ", "? ", "; ", ", ", " "};
        String rest = s;
        while (rest.length() > CHUNK) {
            int cut = -1;
            for (int d = 0; d < delims.length; d++) {
                int i = rest.lastIndexOf(delims[d], CHUNK);
                if (i > CHUNK / 2) {
                    cut = i + delims[d].length();
                    break;
                }
            }
            if (cut < 0) cut = CHUNK;
            String piece = rest.substring(0, cut).trim();
            if (piece.length() > 0) out.add(piece);
            rest = rest.substring(cut);
        }
        rest = rest.trim();
        if (rest.length() > 0) out.add(rest);
        return out;
    }
}
