package com.geminiapp.chat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class GeminiApi {

    private static final String BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/";

    public static class Message {
        public final String role;
        public final String text;
        public final String imageBase64; // null если нет картинки
        public final String imageMimeType;
        public final boolean isGeneratedImage; // картинка сгенерирована моделью
        public Message(String role, String text) {
            this.role = role;
            this.text = text;
            this.imageBase64 = null;
            this.imageMimeType = null;
            this.isGeneratedImage = false;
        }
        public Message(String role, String text, String imageBase64, String imageMimeType) {
            this.role = role;
            this.text = text;
            this.imageBase64 = imageBase64;
            this.imageMimeType = imageMimeType;
            this.isGeneratedImage = false;
        }
        public Message(String role, String text, String imageBase64, boolean isGeneratedImage) {
            this.role = role;
            this.text = text;
            this.imageBase64 = imageBase64;
            this.imageMimeType = "image/png";
            this.isGeneratedImage = isGeneratedImage;
        }
    }

    public interface Callback {
        void onSuccess(String text);
        void onError(String error);
    }

    public interface ModelsCallback {
        void onSuccess(java.util.List<String> models);
        void onError(String error);
    }

    public void listModels(final String apiKey, final ModelsCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey + "&pageSize=50");
                    connection = (HttpURLConnection) url.openConnection();
                    if (connection instanceof javax.net.ssl.HttpsURLConnection) {
                        try {
                            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                            sc.init(null, null, null);
                            ((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(
                                new Tls12SocketFactory(sc.getSocketFactory()));
                        } catch (Exception ignored) {}
                    }
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(30000);
                    connection.setReadTimeout(60000);

                    int responseCode = connection.getResponseCode();
                    InputStream is = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                    if (is == null) { callback.onError("HTTP " + responseCode); return; }

                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    if (responseCode < 200 || responseCode >= 300) {
                        callback.onError("HTTP " + responseCode + ": " + sb.toString());
                        return;
                    }

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray arr = json.getJSONArray("models");
                    java.util.List<String> models = new java.util.ArrayList<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        // name вида "models/gemini-2.5-flash" — берём только то что после "/"
                        String name = m.getString("name");
                        if (name.startsWith("models/")) name = name.substring(7);
                        // Фильтруем только те что поддерживают generateContent
                        if (m.has("supportedGenerationMethods")) {
                            JSONArray methods = m.getJSONArray("supportedGenerationMethods");
                            boolean supportsGenerate = false;
                            for (int j = 0; j < methods.length(); j++) {
                                if ("generateContent".equals(methods.getString(j))) {
                                    supportsGenerate = true;
                                    break;
                                }
                            }
                            if (supportsGenerate) models.add(name);
                        }
                    }
                    callback.onSuccess(models);
                } catch (Throwable t) {
                    callback.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }).start();
    }

    public interface ImageCallback {
        void onSuccess(String base64png);
        void onError(String error);
    }

    public void generateImage(final String apiKey, final String prompt, final ImageCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    JSONObject body = new JSONObject();
                    JSONArray contents = new JSONArray();
                    JSONObject content = new JSONObject();
                    content.put("role", "user");
                    JSONArray parts = new JSONArray();
                    JSONObject part = new JSONObject();
                    part.put("text", prompt);
                    parts.put(part);
                    content.put("parts", parts);
                    contents.put(content);
                    body.put("contents", contents);

                    // Необходимо указать что хотим IMAGE в ответе
                    JSONObject genConfig = new JSONObject();
                    JSONArray responseMimeTypes = new JSONArray();
                    responseMimeTypes.put("text/plain");
                    responseMimeTypes.put("image/png");
                    genConfig.put("responseModalities", responseMimeTypes);
                    body.put("generationConfig", genConfig);

                    URL url = new URL(BASE_URL + "gemini-3.1-flash-image:generateContent?key=" + apiKey);
                    connection = (HttpURLConnection) url.openConnection();
                    if (connection instanceof javax.net.ssl.HttpsURLConnection) {
                        try {
                            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
                            sc.init(null, null, null);
                            ((javax.net.ssl.HttpsURLConnection) connection).setSSLSocketFactory(
                                new Tls12SocketFactory(sc.getSocketFactory()));
                        } catch (Exception ignored) {}
                    }
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setConnectTimeout(30000);
                    connection.setReadTimeout(120000); // генерация картинок дольше
                    connection.setDoOutput(true);
                    connection.setDoInput(true);

                    byte[] outputBytes = body.toString().getBytes("UTF-8");
                    OutputStream os = connection.getOutputStream();
                    os.write(outputBytes);
                    os.flush();
                    os.close();

                    int responseCode = connection.getResponseCode();
                    InputStream is = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                    if (is == null) { callback.onError("HTTP " + responseCode); return; }

                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();

                    if (responseCode < 200 || responseCode >= 300) {
                        try {
                            JSONObject err = new JSONObject(sb.toString());
                            callback.onError(err.getJSONObject("error").getString("message"));
                        } catch (Exception e) {
                            callback.onError("HTTP " + responseCode);
                        }
                        return;
                    }

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray candidates = json.getJSONArray("candidates");
                    JSONArray resParts = candidates.getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts");

                    // Ищем часть с inlineData
                    for (int i = 0; i < resParts.length(); i++) {
                        JSONObject p = resParts.getJSONObject(i);
                        if (p.has("inlineData")) {
                            String b64 = p.getJSONObject("inlineData").getString("data");
                            callback.onSuccess(b64);
                            return;
                        }
                    }
                    callback.onError("No image in response");

                } catch (Throwable t) {
                    callback.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }).start();
    }

    public void sendMessage(final String apiKey, final String model, final float temperature,
                            final String systemPrompt, final List<Message> history,
                            final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    JSONObject body = new JSONObject();

                    if (systemPrompt != null && !systemPrompt.isEmpty()) {
                        JSONObject sysInst = new JSONObject();
                        JSONObject sysPart = new JSONObject();
                        sysPart.put("text", systemPrompt);
                        JSONArray sysParts = new JSONArray();
                        sysParts.put(sysPart);
                        sysInst.put("parts", sysParts);
                        body.put("system_instruction", sysInst);
                    }

                    JSONArray contents = new JSONArray();
                    for (int i = 0; i < history.size(); i++) {
                        Message msg = history.get(i);
                        JSONObject content = new JSONObject();
                        content.put("role", msg.role);
                        JSONArray parts = new JSONArray();
                        // Если есть картинка — добавляем inline_data
                        if (msg.imageBase64 != null && !msg.imageBase64.isEmpty()) {
                            JSONObject imgPart = new JSONObject();
                            JSONObject inlineData = new JSONObject();
                            inlineData.put("mime_type", msg.imageMimeType != null ? msg.imageMimeType : "image/jpeg");
                            inlineData.put("data", msg.imageBase64);
                            imgPart.put("inline_data", inlineData);
                            parts.put(imgPart);
                        }
                        JSONObject part = new JSONObject();
                        part.put("text", msg.text.isEmpty() ? " " : msg.text);
                        parts.put(part);
                        content.put("parts", parts);
                        contents.put(content);
                    }
                    body.put("contents", contents);

                    JSONObject genConfig = new JSONObject();
                    genConfig.put("temperature", (double) temperature);
                    body.put("generationConfig", genConfig);

                    URL url = new URL(BASE_URL + model + ":generateContent?key=" + apiKey);
                    connection = (HttpURLConnection) url.openConnection();
                    // На Android 2.3 нет TLS 1.2 — включаем принудительно
                    if (connection instanceof HttpsURLConnection) {
                        try {
                            SSLContext sc = SSLContext.getInstance("TLSv1.2");
                            sc.init(null, null, null);
                            ((HttpsURLConnection) connection).setSSLSocketFactory(
                                new Tls12SocketFactory(sc.getSocketFactory()));
                        } catch (Exception ignored) {
                            // Если не удалось — пробуем без патча (на новых Android сработает)
                        }
                    }
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(15000);
                    connection.setDoOutput(true);
                    connection.setDoInput(true);

                    byte[] outputBytes = body.toString().getBytes("UTF-8");
                    OutputStream os = connection.getOutputStream();
                    os.write(outputBytes);
                    os.flush();
                    os.close();

                    int responseCode = connection.getResponseCode();
                    InputStream is;
                    if (responseCode >= 200 && responseCode < 300) {
                        is = connection.getInputStream();
                    } else {
                        is = connection.getErrorStream();
                    }

                    if (is == null) {
                        callback.onError("HTTP " + responseCode + " (No response body)");
                        return;
                    }

                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder responseBuilder = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        responseBuilder.append(line);
                    }
                    br.close();

                    String responseBody = responseBuilder.toString();

                    if (responseCode < 200 || responseCode >= 300) {
                        try {
                            JSONObject err = new JSONObject(responseBody);
                            String msg = err.getJSONObject("error").getString("message");
                            callback.onError(msg);
                        } catch (Exception e) {
                            callback.onError("HTTP " + responseCode);
                        }
                        return;
                    }

                    JSONObject json = new JSONObject(responseBody);
                    String text = json
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text");

                    callback.onSuccess(text);
                } catch (Throwable t) {
                    String errLog = t.getMessage();
                    if (errLog == null) {
                        errLog = t.getClass().getSimpleName();
                    }
                    callback.onError(errLog);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }
}