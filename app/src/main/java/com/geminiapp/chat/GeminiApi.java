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
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class GeminiApi {

    private static final String BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/";

    public static class Message {
        public final String role;
        public final String text;
        public final String imageBase64;
        public final String imageMimeType;
        public final boolean isGeneratedImage;

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

    public interface StreamCallback extends Callback {
        void onPartial(String textSoFar);
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
                    URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey + "&pageSize=1000");
                    connection = (HttpURLConnection) url.openConnection();
                    if (connection instanceof HttpsURLConnection) {
                        try {
                            SSLContext sc = SSLContext.getInstance("TLSv1.2");
                            sc.init(null, null, null);
                            ((HttpsURLConnection) connection).setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
                        } catch (Exception ignored) {}
                    }
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(30000);

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
                        callback.onError(formatHttpError(responseCode, sb.toString()));
                        return;
                    }

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray arr = json.getJSONArray("models");
                    java.util.List<String> models = new java.util.ArrayList<String>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        String name = m.getString("name");
                        if (name.startsWith("models/")) name = name.substring(7);

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

    static String formatHttpError(int code, String body) {
        try {
            JSONObject e = new JSONObject(body).getJSONObject("error");
            return "[" + code + "|" + e.optString("status", "") + "] " + e.getString("message");
        } catch (Exception ex) {
            return "HTTP " + code + ": " + body;
        }
    }

    public interface ImageCallback {
        void onSuccess(String base64png);
        void onError(String error);
    }

    public void generateImage(final String apiKey, final String model, final String prompt, final ImageCallback callback) {
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

                    // ИСПРАВЛЕНИЕ: Используем правильные модальности "TEXT" и "IMAGE"
                    JSONObject genConfig = new JSONObject();
                    JSONArray responseModalities = new JSONArray();
                    responseModalities.put("TEXT");
                    responseModalities.put("IMAGE");
                    genConfig.put("responseModalities", responseModalities);
                    body.put("generationConfig", genConfig);

                    URL url = new URL(BASE_URL + model + ":generateContent?key=" + apiKey);
                    connection = (HttpURLConnection) url.openConnection();
                    if (connection instanceof HttpsURLConnection) {
                        try {
                            SSLContext sc = SSLContext.getInstance("TLSv1.2");
                            sc.init(null, null, null);
                            ((HttpsURLConnection) connection).setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
                        } catch (Exception ignored) {}
                    }
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setConnectTimeout(30000);
                    connection.setReadTimeout(120000); 
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
                            callback.onError(formatHttpError(responseCode, sb.toString()));
                        } catch (Exception e) {
                            callback.onError("HTTP " + responseCode);
                        }
                        return;
                    }

                    JSONObject json = new JSONObject(sb.toString());
                    JSONArray candidates = json.getJSONArray("candidates");
                    JSONArray resParts = candidates.getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts");

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

    // Собирает источники из groundingMetadata (поиск Google): каждый элемент - {название, ссылка}, без повторов
    private static void collectSources(JSONObject groundingMetadata, List<String[]> out) {
        if (groundingMetadata == null) return;
        JSONArray chunks = groundingMetadata.optJSONArray("groundingChunks");
        if (chunks == null) return;
        for (int i = 0; i < chunks.length(); i++) {
            JSONObject chunk = chunks.optJSONObject(i);
            if (chunk == null) continue;
            JSONObject web = chunk.optJSONObject("web");
            if (web == null) continue;
            String uri = web.optString("uri", "");
            if (uri.length() == 0) continue;
            boolean dup = false;
            for (int j = 0; j < out.size(); j++) {
                if (out.get(j)[1].equals(uri)) { dup = true; break; }
            }
            if (!dup) out.add(new String[]{web.optString("title", uri), uri});
        }
    }

    private static String formatSources(List<String[]> sources, String title) {
        if (sources.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n").append(title).append(":");
        for (int i = 0; i < sources.size(); i++) {
            String[] s = sources.get(i);
            sb.append("\n").append(i + 1).append(". ").append(s[0]).append(" - ").append(s[1]);
        }
        return sb.toString();
    }

    public void sendMessage(final String apiKey, final String model, final float temperature,
                            final String systemPrompt, final List<Message> history,
                            final boolean stream, final boolean search, final String sourcesTitle,
                            final Callback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                try {
                    JSONObject body = new JSONObject();

                    if (systemPrompt != null && systemPrompt.length() > 0) {
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
                        if (msg.imageBase64 != null && msg.imageBase64.length() > 0) {
                            JSONObject imgPart = new JSONObject();
                            JSONObject inlineData = new JSONObject();
                            inlineData.put("mime_type", msg.imageMimeType != null ? msg.imageMimeType : "image/jpeg");
                            inlineData.put("data", msg.imageBase64);
                            imgPart.put("inline_data", inlineData);
                            parts.put(imgPart);
                        }
                        JSONObject part = new JSONObject();
                        part.put("text", msg.text.length() == 0 ? " " : msg.text);
                        parts.put(part);
                        content.put("parts", parts);
                        contents.put(content);
                    }
                    body.put("contents", contents);

                    JSONObject genConfig = new JSONObject();
                    genConfig.put("temperature", (double) temperature);
                    if (search) {
                        JSONArray toolsArr = new JSONArray();
                        JSONObject searchTool = new JSONObject();
                        searchTool.put("google_search", new JSONObject());
                        toolsArr.put(searchTool);
                        body.put("tools", toolsArr);
                    }
                    body.put("generationConfig", genConfig);

                    final boolean doStream = stream && callback instanceof StreamCallback;
                    URL url = new URL(BASE_URL + model
                        + (doStream ? ":streamGenerateContent?alt=sse&key=" : ":generateContent?key=")
                        + apiKey);
                    connection = (HttpURLConnection) url.openConnection();
                    if (connection instanceof HttpsURLConnection) {
                        try {
                            SSLContext sc = SSLContext.getInstance("TLSv1.2");
                            sc.init(null, null, null);
                            ((HttpsURLConnection) connection).setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
                        } catch (Exception ignored) {}
                    }
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(60000);
                    if (doStream) connection.setRequestProperty("Accept-Encoding", "identity");
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

                    if (is == null) {
                        callback.onError("HTTP " + responseCode);
                        return;
                    }

                    if (doStream && responseCode >= 200 && responseCode < 300) {
                        BufferedReader sbr = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        StringBuilder acc = new StringBuilder();
                        List<String[]> sources = new ArrayList<String[]>();
                        String sline;
                        while ((sline = sbr.readLine()) != null) {
                            if (!sline.startsWith("data:")) continue;
                            String payload = sline.substring(5).trim();
                            if (payload.length() == 0 || payload.equals("[DONE]")) continue;
                            try {
                                JSONArray cands = new JSONObject(payload).optJSONArray("candidates");
                                if (cands == null || cands.length() == 0) continue;
                                collectSources(cands.getJSONObject(0).optJSONObject("groundingMetadata"), sources);
                                JSONObject cont = cands.getJSONObject(0).optJSONObject("content");
                                if (cont == null) continue;
                                JSONArray ps = cont.optJSONArray("parts");
                                if (ps == null) continue;
                                boolean changed = false;
                                for (int k = 0; k < ps.length(); k++) {
                                    JSONObject pp = ps.getJSONObject(k);
                                    if (pp.optBoolean("thought", false)) continue;
                                    String t = pp.optString("text", "");
                                    if (t.length() > 0) { acc.append(t); changed = true; }
                                }
                                if (changed) ((StreamCallback) callback).onPartial(acc.toString());
                            } catch (JSONException ignored) {}
                        }
                        sbr.close();
                        if (acc.length() == 0) callback.onError("Empty response");
                        else callback.onSuccess(acc.toString() + formatSources(sources, sourcesTitle));
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
                            callback.onError(formatHttpError(responseCode, responseBody));
                        } catch (Exception e) {
                            callback.onError("HTTP " + responseCode);
                        }
                        return;
                    }

                    JSONObject json = new JSONObject(responseBody);
                    StringBuilder txtBuilder = new StringBuilder();
                    List<String[]> srcList = new ArrayList<String[]>();
                    JSONArray candArr = json.optJSONArray("candidates");
                    if (candArr != null && candArr.length() > 0) {
                        JSONObject cand0 = candArr.getJSONObject(0);
                        JSONObject cont0 = cand0.optJSONObject("content");
                        if (cont0 != null) {
                            JSONArray ps0 = cont0.optJSONArray("parts");
                            if (ps0 != null) {
                                for (int k = 0; k < ps0.length(); k++) {
                                    JSONObject pp0 = ps0.getJSONObject(k);
                                    if (pp0.optBoolean("thought", false)) continue;
                                    txtBuilder.append(pp0.optString("text", ""));
                                }
                            }
                        }
                        collectSources(cand0.optJSONObject("groundingMetadata"), srcList);
                    }
                    if (txtBuilder.length() == 0) {
                        callback.onError("Empty response");
                        return;
                    }
                    String text = txtBuilder.toString() + formatSources(srcList, sourcesTitle);

                    callback.onSuccess(text);
                } catch (Throwable t) {
                    String errLog = t.getMessage();
                    if (errLog == null) errLog = t.getClass().getSimpleName();
                    callback.onError(errLog);
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }).start();
    }
}