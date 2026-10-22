// PendingState.java
package com.geminiapp.chat;

import android.os.Handler;
import android.os.Looper;

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

    public static volatile String pendingImageBase64 = null;
    public static volatile String pendingImageMime = "image/jpeg";
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
    }

    public static void clearImage() {
        pendingImageBase64 = null;
        pendingImageMime = "image/jpeg";
    }

    public static void clearAll() {
        chatId = -1;
        clearResponse();
        clearImage();
        inputText = "";
    }
}