package com.ialwaysloveyou.oldgemai;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Вложение сообщения пользователя: картинка, PDF или текстовый файл (текст, код, CSV, JSON и т.п.).
 *
 * Данные хранятся как Base64. Пока сообщение не сохранено, Base64 лежит в памяти; при сохранении
 * в историю он уезжает в файл во внутреннем хранилище приложения (files/att), а в БД остаётся только
 * имя файла. Так строки БД не раздуваются (у CursorWindow лимит около 1-2 МБ на строку).
 *
 * Картинки уменьшаются (до MAX_IMAGE_SIZE по большей стороне), PDF уходит в Gemini как есть,
 * текстовые файлы перекодируются в UTF-8 и передаются модели обычным текстом.
 */
public final class Attachment {

    public static final int KIND_IMAGE = 0;
    public static final int KIND_PDF = 1;
    public static final int KIND_TEXT = 2;

    /** Сколько вложений можно прикрепить к одному сообщению. */
    public static final int MAX_COUNT = 10;

    public static final int MAX_IMAGE_SIZE = 512;
    public static final int JPEG_QUALITY = 60;
    private static final int THUMB_SIZE = 200;
    private static final int MAX_TEXT_BYTES = 1024 * 1024;

    private static final String[] IMAGE_EXT = {"jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"};
    private static final String[] TEXT_EXT = {
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "css", "scss",
        "js", "mjs", "ts", "tsx", "jsx", "java", "kt", "kts", "py", "c", "h", "cpp", "cc", "hpp",
        "cs", "go", "rs", "rb", "php", "sh", "bat", "cmd", "ps1", "sql", "yaml", "yml", "ini",
        "cfg", "conf", "properties", "gradle", "log", "tex", "srt", "vtt", "swift", "lua", "r",
        "dart", "vue", "toml", "svg"
    };

    private static final AtomicInteger COUNTER = new AtomicInteger();

    /** Вложение нельзя добавить; сообщение уже готово для показа пользователю. */
    public static final class AttachException extends Exception {
        public AttachException(String message) {
            super(message);
        }
    }

    public final int kind;
    public final String name;
    public final String mime;

    private String base64;   // данные в памяти (null после сохранения в файл)
    private String path;     // файл с Base64 (null, пока вложение не сохранено)
    private Bitmap thumb;    // миниатюра картинки, создаётся при первом показе

    Attachment(int kind, String name, String mime, String base64, String path) {
        this.kind = kind;
        this.name = name != null && name.length() > 0 ? name : "file";
        this.mime = mime != null ? mime : "application/octet-stream";
        this.base64 = base64;
        this.path = path;
    }

    /** Картинка из старой версии БД (одна картинка в колонке сообщения). */
    static Attachment fromLegacyImage(String base64, String mime) {
        return new Attachment(KIND_IMAGE, "image", mime != null ? mime : "image/jpeg", base64, null);
    }

    // ---------------------------------------------------------------------------------
    // Данные
    // ---------------------------------------------------------------------------------

    /** Base64 данных или null, если файл с данными потерян. */
    public synchronized String getBase64() {
        if (base64 != null) return base64;
        if (path == null) return null;
        try {
            File f = new File(path);
            if (!f.exists()) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                return new String(buf, 0, off, "US-ASCII");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            AppLog.w("Attach", "Read failed: " + t);
            return null;
        }
    }

    /** Содержимое текстового файла (UTF-8) или null. */
    public String getText() {
        String b = getBase64();
        if (b == null) return null;
        try {
            return new String(Base64Compat.decode(b), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Миниатюра картинки (до 200 пикселей), для файлов null. Кэшируется. */
    public synchronized Bitmap getThumb() {
        if (thumb != null || kind != KIND_IMAGE) return thumb;
        try {
            String b = getBase64();
            if (b == null) return null;
            byte[] bytes = Base64Compat.decode(b);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            o.inJustDecodeBounds = false;
            o.inSampleSize = MainActivity.calculateInSampleSize(o, THUMB_SIZE, THUMB_SIZE);
            thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Throwable t) {
            AppLog.w("Attach", "Thumbnail failed: " + t);
        }
        return thumb;
    }

    /** Подпись для плитки файла: расширение большими буквами (PDF, TXT, JAVA...). */
    public String extLabel() {
        String e = extOf(name);
        if (kind == KIND_PDF) return "PDF";
        if (e.length() == 0 || e.length() > 5) return kind == KIND_IMAGE ? "IMG" : "FILE";
        return e.toUpperCase(Locale.US);
    }

    /**
     * Независимая копия с данными в памяти: нужна, когда сообщение возвращается в поле ввода
     * для изменения, а его файлы будут удалены вместе со старым сообщением.
     * Возвращает null, если данные потеряны.
     */
    public Attachment duplicate() {
        String b = getBase64();
        if (b == null) return null;
        Attachment a = new Attachment(kind, name, mime, b, null);
        a.thumb = thumb;
        return a;
    }

    // ---------------------------------------------------------------------------------
    // Хранение в файлах и JSON для БД
    // ---------------------------------------------------------------------------------

    /** Сохраняет данные в файл каталога dir и освобождает память. true - данные теперь в файле. */
    synchronized boolean persist(File dir) {
        if (path != null) return new File(path).exists();
        if (base64 == null) return false;
        try {
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "a" + Long.toHexString(System.currentTimeMillis())
                    + "_" + COUNTER.incrementAndGet() + ".b64");
            FileOutputStream out = new FileOutputStream(f);
            try {
                int len = base64.length();
                for (int i = 0; i < len; i += 65536) {
                    out.write(base64.substring(i, Math.min(len, i + 65536)).getBytes("US-ASCII"));
                }
            } finally {
                out.close();
            }
            path = f.getPath();
            base64 = null;
            return true;
        } catch (Throwable t) {
            AppLog.w("Attach", "Save to file failed: " + t);
            return false;
        }
    }

    /** JSON-массив для колонки БД: тип, имя, mime и имя файла с данными. */
    static String toJson(List<Attachment> list, File dir) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Attachment a = list.get(i);
            try {
                boolean ok = a.persist(dir);
                JSONObject o = new JSONObject();
                o.put("k", a.kind);
                o.put("n", a.name);
                o.put("m", a.mime);
                o.put("f", ok && a.path != null ? new File(a.path).getName() : "");
                arr.put(o);
            } catch (Exception e) {
                AppLog.w("Attach", "toJson failed: " + e);
            }
        }
        return arr.toString();
    }

    static List<Attachment> fromJson(String json, File dir) {
        List<Attachment> out = new ArrayList<Attachment>();
        if (json == null || json.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String f = o.optString("f", "");
                out.add(new Attachment(o.optInt("k", KIND_IMAGE), o.optString("n", "file"),
                        o.optString("m", "application/octet-stream"), null,
                        f.length() > 0 ? new File(dir, f).getPath() : null));
            }
        } catch (Exception e) {
            AppLog.w("Attach", "fromJson failed: " + e);
        }
        return out;
    }

    /** Удаляет файлы данных, перечисленные в JSON вложений. */
    static void deleteFilesInJson(String json, File dir) {
        if (json == null || json.length() == 0) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String f = arr.getJSONObject(i).optString("f", "");
                if (f.length() > 0) new File(dir, f).delete();
            }
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------------------
    // Загрузка из выбранного пользователем файла
    // ---------------------------------------------------------------------------------

    /**
     * Читает выбранный файл и готовит вложение. Вызывать из фонового потока.
     * При проблеме (неподдерживаемый тип, слишком большой файл и т.п.) бросает AttachException
     * с готовым для пользователя текстом.
     */
    public static Attachment load(Context ctx, Uri uri, boolean ru) throws AttachException {
        String name = queryName(ctx, uri);
        String mime = null;
        try {
            mime = ctx.getContentResolver().getType(uri);
        } catch (Throwable ignored) {}

        int kind = classify(name, mime);
        if (kind < 0) {
            throw new AttachException(ru
                ? "Этот тип файла не поддерживается: " + name + ". Можно картинки, PDF и текстовые файлы."
                : "This file type is not supported: " + name + ". Images, PDF and text files are allowed.");
        }

        try {
            if (kind == KIND_IMAGE) return loadImage(ctx, uri, name, mime, ru);

            int limit = kind == KIND_TEXT ? maxTextBytes() : maxFileBytes();
            byte[] data = readLimited(ctx, uri, limit, name, ru);
            if (kind == KIND_PDF) {
                return new Attachment(KIND_PDF, name, "application/pdf", Base64Compat.encode(data), null);
            }
            byte[] utf8 = toUtf8Text(data, name, ru);
            return new Attachment(KIND_TEXT, name, "text/plain", Base64Compat.encode(utf8), null);
        } catch (AttachException e) {
            throw e;
        } catch (OutOfMemoryError e) {
            throw new AttachException(ru ? "Не хватает памяти для файла " + name
                                         : "Not enough memory for the file " + name);
        } catch (Exception e) {
            AppLog.e("Attach", "load failed", e);
            throw new AttachException((ru ? "Не удалось прочитать " : "Could not read ") + name
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    private static Attachment loadImage(Context ctx, Uri uri, String name, String mime, boolean ru)
            throws Exception {
        String cannotRead = ru ? "Не удалось прочитать изображение: " + name
                               : "Could not read the image: " + name;

        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new AttachException(cannotRead);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeStream(is, null, opts);
        } finally {
            is.close();
        }

        is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new AttachException(cannotRead);
        Bitmap original;
        try {
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = MainActivity.calculateInSampleSize(opts, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE);
            original = BitmapFactory.decodeStream(is, null, opts);
        } finally {
            is.close();
        }
        if (original == null) throw new AttachException(cannotRead);

        Bitmap scaled = scaleDown(original, MAX_IMAGE_SIZE);
        String m = mime != null ? mime.toLowerCase(Locale.US) : "";
        boolean png = m.indexOf("png") >= 0 || extOf(name).equals("png");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        scaled.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);

        Attachment a = new Attachment(KIND_IMAGE, name, png ? "image/png" : "image/jpeg",
                Base64Compat.encode(baos.toByteArray()), null);
        a.thumb = scaleDown(scaled, THUMB_SIZE);
        return a;
    }

    private static Bitmap scaleDown(Bitmap src, int maxSide) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxSide && h <= maxSide) return src;
        float scale = maxSide / (float) Math.max(w, h);
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * scale)),
                Math.max(1, Math.round(h * scale)), true);
    }

    private static byte[] readLimited(Context ctx, Uri uri, int max, String name, boolean ru)
            throws Exception {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) {
            throw new AttachException((ru ? "Не удалось открыть файл: " : "Could not open the file: ") + name);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > max) {
                    int mb = Math.max(1, max / (1024 * 1024));
                    throw new AttachException(ru
                        ? "Файл слишком большой (максимум " + mb + " МБ): " + name
                        : "The file is too large (" + mb + " MB maximum): " + name);
                }
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } finally {
            is.close();
        }
    }

    /** Проверяет, что это текст (а не бинарный файл), и приводит его к UTF-8 без BOM. */
    private static byte[] toUtf8Text(byte[] data, String name, boolean ru) throws Exception {
        int check = Math.min(data.length, 8000);
        for (int i = 0; i < check; i++) {
            if (data[i] == 0) {
                throw new AttachException(ru
                    ? "Это не текстовый файл: " + name
                    : "This is not a text file: " + name);
            }
        }
        String s;
        try {
            s = Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            // Не UTF-8: чаще всего старые русские файлы в windows-1251
            try {
                s = new String(data, "windows-1251");
            } catch (Exception e2) {
                s = new String(data, "ISO-8859-1");
            }
        }
        if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
        if (s.trim().length() == 0) {
            throw new AttachException(ru ? "Файл пустой: " + name : "The file is empty: " + name);
        }
        return s.getBytes("UTF-8");
    }

    // ---------------------------------------------------------------------------------
    // Вспомогательное
    // ---------------------------------------------------------------------------------

    /** Максимальный размер PDF: зависит от памяти устройства (от 1 до 8 МБ). */
    private static int maxFileBytes() {
        long limit = Runtime.getRuntime().maxMemory() / 16;
        if (limit < 1024 * 1024) limit = 1024 * 1024;
        if (limit > 8 * 1024 * 1024) limit = 8 * 1024 * 1024;
        return (int) limit;
    }

    private static int maxTextBytes() {
        return Math.min(MAX_TEXT_BYTES, maxFileBytes());
    }

    private static String queryName(Context ctx, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                        null, null, null);
                if (c != null && c.moveToFirst()) {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) name = c.getString(idx);
                }
            } catch (Throwable ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        if (name == null || name.length() == 0) name = uri.getLastPathSegment();
        if (name == null || name.length() == 0) name = "file";
        int slash = name.lastIndexOf('/');
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        return name;
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot >= name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static boolean isOneOf(String ext, String[] list) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(ext)) return true;
        }
        return false;
    }

    /** Тип вложения по mime и расширению или -1, если такой файл не поддерживается. */
    private static int classify(String name, String mime) {
        String m = mime != null ? mime.toLowerCase(Locale.US) : "";
        String ext = extOf(name);
        // SVG - это XML-текст, BitmapFactory его не читает
        if (m.indexOf("svg") >= 0 || ext.equals("svg")) return KIND_TEXT;
        if (m.startsWith("image/") || isOneOf(ext, IMAGE_EXT)) return KIND_IMAGE;
        if (m.equals("application/pdf") || ext.equals("pdf")) return KIND_PDF;
        if (m.startsWith("text/") || m.equals("application/json") || m.equals("application/xml")
                || m.equals("application/javascript") || m.equals("application/x-sh")
                || m.equals("application/x-yaml") || isOneOf(ext, TEXT_EXT)) {
            return KIND_TEXT;
        }
        return -1;
    }
}
