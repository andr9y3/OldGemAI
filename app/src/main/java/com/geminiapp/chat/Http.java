package com.geminiapp.chat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * Единая точка HTTPS-запросов.
 *  - Android 4.1+ (API 16+): обычный HttpURLConnection (на 4.1-4.4 с принудительным TLS 1.2).
 *  - Android 1.0-4.0.x (API 1-15): собственный HTTP/1.1 поверх LegacyTls (TLS 1.2 на чистой Java).
 * Тело ответа читается из Response.in независимо от кода ответа (в том числе при ошибках).
 */
public final class Http {

    private Http() {}

    public static final class Response {
        public final int code;
        public final InputStream in;
        /** Заголовок Location (для перенаправлений) или null. */
        public String location;
        /** Content-Length или -1, если неизвестно. */
        public long contentLength = -1;
        private final HttpURLConnection conn;
        private final LegacyTls.Conn tls;

        Response(int code, InputStream in, HttpURLConnection conn, LegacyTls.Conn tls) {
            this.code = code;
            this.in = in;
            this.conn = conn;
            this.tls = tls;
        }

        public void disconnect() { close(); }

        public void close() {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) {} }
            if (tls != null) tls.close();
        }
    }

    /** Номер API без Build.VERSION.SDK_INT (его нет на Android 1.0-1.5). */
    @SuppressWarnings("deprecation")
    public static int sdk() {
        try {
            return Integer.parseInt(android.os.Build.VERSION.SDK);
        } catch (Throwable t) {
            return 1;
        }
    }

    /** headers: пары имя, значение, например {"Content-Type", "text/plain"}. body может быть null (GET). */
    public static Response request(String method, String url, String[] headers, byte[] body,
                                   int connectTimeout, int readTimeout) throws IOException {
        if (sdk() >= 16) return viaUrlConnection(method, url, headers, body, connectTimeout, readTimeout);
        return viaLegacyTls(method, url, headers, body, connectTimeout, readTimeout);
    }

    // ------------------------------------------------------------ API 16+

    private static Response viaUrlConnection(String method, String url, String[] headers, byte[] body,
                                             int ct, int rt) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && sdk() < 20) {
            try {
                SSLContext sc = SSLContext.getInstance("TLSv1.2");
                sc.init(null, null, null);
                ((HttpsURLConnection) c).setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
            } catch (Exception ignored) {}
        }
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(ct);
        c.setReadTimeout(rt);
        for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
        if (body != null) {
            c.setDoOutput(true);
            c.setDoInput(true);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (is == null) is = new ByteArrayInputStream(new byte[0]);
        Response resp = new Response(code, is, c, null);
        resp.location = c.getHeaderField("Location");
        try {
            String cl = c.getHeaderField("Content-Length");
            if (cl != null) resp.contentLength = Long.parseLong(cl.trim());
        } catch (Exception ignored) {}
        return resp;
    }

    // ------------------------------------------------------------ API 1-15

    private static Response viaLegacyTls(String method, String url, String[] headers, byte[] body,
                                         int ct, int rt) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("only https is supported: " + url);
        String rest = url.substring(8);
        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        String host = hostPort;
        int port = 443;
        int colon = hostPort.indexOf(':');
        if (colon > 0) {
            host = hostPort.substring(0, colon);
            port = Integer.parseInt(hostPort.substring(colon + 1));
        }

        LegacyTls.Conn tls = LegacyTls.connect(host, port, ct, rt);
        try {
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("Accept-Encoding: identity\r\n");
            for (int i = 0; i + 1 < headers.length; i += 2) {
                req.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            }
            if (body != null) req.append("Content-Length: ").append(body.length).append("\r\n");
            req.append("\r\n");

            OutputStream out = tls.proto.getOutputStream();
            out.write(req.toString().getBytes("UTF-8"));
            if (body != null) out.write(body);
            out.flush();

            InputStream raw = tls.proto.getInputStream();
            String status = readLine(raw);
            if (status == null || !status.startsWith("HTTP/")) throw new IOException("bad HTTP response");
            int sp = status.indexOf(' ');
            int code = Integer.parseInt(status.substring(sp + 1, sp + 4));

            boolean chunked = false;
            String location = null;
            long length = -1;
            String line;
            while ((line = readLine(raw)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String name = line.substring(0, c).trim().toLowerCase();
                String value = line.substring(c + 1).trim();
                if (name.equals("location")) location = value;
                if (name.equals("transfer-encoding") && value.toLowerCase().indexOf("chunked") >= 0) chunked = true;
                else if (name.equals("content-length")) {
                    try { length = Long.parseLong(value); } catch (NumberFormatException ignored) {}
                }
            }

            InputStream bodyStream;
            if (code == 204 || code == 304 || (code >= 100 && code < 200)) bodyStream = new ByteArrayInputStream(new byte[0]);
            else if (chunked) bodyStream = new ChunkedStream(raw);
            else if (length >= 0) bodyStream = new LimitedStream(raw, length);
            else bodyStream = new TolerantStream(raw);
            Response resp = new Response(code, bodyStream, null, tls);
            resp.location = location;
            resp.contentLength = (bodyStream instanceof LimitedStream) ? length : -1;
            return resp;
        } catch (IOException e) {
            tls.close();
            throw e;
        } catch (RuntimeException e) {
            tls.close();
            throw new IOException(String.valueOf(e));
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    /** Чтение до закрытия соединения; обрыв без close_notify считается концом данных. */
    private static final class TolerantStream extends InputStream {
        private final InputStream in;
        TolerantStream(InputStream in) { this.in = in; }
        @Override public int read() throws IOException {
            try { return in.read(); } catch (EOFException e) { return -1; }
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            try { return in.read(b, off, len); } catch (EOFException e) { return -1; }
        }
    }

    private static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long left;
        LimitedStream(InputStream in, long left) { this.in = in; this.left = left; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int r = in.read();
            if (r >= 0) left--;
            return r;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = in.read(b, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    private static final class ChunkedStream extends InputStream {
        private final InputStream in;
        private int left = 0;
        private boolean eof = false;
        ChunkedStream(InputStream in) { this.in = in; }

        private boolean next() throws IOException {
            if (eof) return false;
            if (left > 0) return true;
            String line = readLine(in);
            while (line != null && line.length() == 0) line = readLine(in); // CRLF после предыдущего чанка
            if (line == null) { eof = true; return false; }
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            left = Integer.parseInt(line.trim(), 16);
            if (left == 0) { eof = true; return false; }
            return true;
        }

        @Override public int read() throws IOException {
            if (!next()) return -1;
            int r = in.read();
            if (r >= 0) left--;
            return r;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (!next()) return -1;
            int r = in.read(b, off, Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }
}
