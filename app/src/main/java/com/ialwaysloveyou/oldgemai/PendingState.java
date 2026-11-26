// PendingState.java
package com.ialwaysloveyou.oldgemai;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;

public final class PendingState {

    public interface Listener {
        void onPendingModelUsed(String model);
        void onPendingPartial(String textSoFar);
        void onPendingSuccess(String text);
        void onPendingImage(String base64png);
        void onPendingError(String error);
    }

    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static volatile Listener listener = null;

    public static volatile long chatId = -1;
    public static volatile String startModel = null;

    public static volatile boolean pending = false;
    public static volatile String partialText = "";
    public static volatile String finalResponse = null;
    public static volatile String finalImageBase64 = null;
    public static volatile String error = null;
    public static volatile String pendingModel = null;

    // Запущенный текстовый запрос: по нему кнопка «Стоп» обрывает ответ (для картинок null)
    public static volatile GeminiApi.Request request = null;

    // Вложения, прикреплённые к ещё не отправленному сообщению (картинки, PDF, текстовые файлы).
    // Живут здесь, чтобы переживать поворот экрана; трогать только из главного потока.
    public static final ArrayList<Attachment> pendingAttachments = new ArrayList<Attachment>();
    // Сколько вложений сейчас читается в фоне (их плитки показываются как "...")
    public static int attachLoading = 0;
    // Обновление полоски вложений в текущей MainActivity (null, пока её нет)
    public static volatile Runnable attachRefresh = null;

    public static volatile String inputText = "";

    private PendingState() {}

    public static void clearResponse() {
        pending = false;
        partialText = "";
        finalResponse = null;
        finalImageBase64 = null;
        error = null;
        pendingModel = null;
        startModel = null;
        request = null;
    }

    public static void clearAttachments() {
        pendingAttachments.clear();
    }

    public static void clearAll() {
        chatId = -1;
        clearResponse();
        clearAttachments();
        inputText = "";
    }
}
