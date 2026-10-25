package com.geminiapp.chat;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Простой логгер без зависимостей (совместим с Android 1.x):
 *  - пишет лог приложения в файл во внутренней памяти (с ротацией);
 *  - перехватывает необработанные исключения (краши) и сохраняет их в отдельный файл;
 *  - собирает полный отчёт: информация об устройстве + настройки + краши + логи.
 *
 * В отчёт НЕ попадают: API ключ, текст системного промпта, тексты сообщений и картинки.
 */
public class AppLog {

    private static final String TAG = "OldGemAI";
    private static final String LOG_FILE = "app.log";
    private static final String LOG_FILE_OLD = "app.log.1";
    private static final String CRASH_FILE = "crash.log";

    private static final int MAX_LOG_BYTES = 80 * 1024;
    private static final int MAX_CRASH_BYTES = 40 * 1024;
    private static final int REPORT_LOG_BYTES = 60 * 1024;
    private static final int REPORT_CRASH_BYTES = 20 * 1024;

    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private static Context appContext;
    private static boolean crashHandlerInstalled = false;

    private AppLog() {}

    // ------------------------------------------------------------------ init

    public static void init(Context ctx) {
        if (ctx == null) return;
        Context a = ctx.getApplicationContext();
        appContext = a != null ? a : ctx;
    }

    public static void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    recordCrash(thread, ex);
                } catch (Throwable ignored) {}
                if (prev != null) {
                    prev.uncaughtException(thread, ex);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(10);
                }
            }
        });
    }

    public static void logStartup() {
        Context c = appContext;
        if (c == null) return;
        i("App", "Started: " + versionString(c) + ", Android " + bv("RELEASE") + " (SDK " + sdkInt() + "), "
                + bf("MANUFACTURER") + " " + bf("MODEL"));
    }

    // ------------------------------------------------------------------ logging

    public static void d(String tag, String msg) { write("D", tag, msg); }

    public static void i(String tag, String msg) { write("I", tag, msg); }

    public static void w(String tag, String msg) { write("W", tag, msg); }

    public static void e(String tag, String msg) { write("E", tag, msg); }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg + (t != null ? "\n" + stackTrace(t) : ""));
    }

    private static void write(String level, String tag, String msg) {
        try {
            String full = tag + ": " + msg;
            if ("E".equals(level)) Log.e(TAG, full);
            else if ("W".equals(level)) Log.w(TAG, full);
            else if ("I".equals(level)) Log.i(TAG, full);
            else Log.d(TAG, full);
        } catch (Throwable ignored) {}

        Context c = appContext;
        if (c == null) return;
        synchronized (LOCK) {
            try {
                File dir = c.getFilesDir();
                if (dir == null) return;
                File f = new File(dir, LOG_FILE);
                if (f.length() > MAX_LOG_BYTES) {
                    File old = new File(dir, LOG_FILE_OLD);
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                    f = new File(dir, LOG_FILE);
                }
                String line = TS.format(new Date()) + " " + level + "/" + tag + ": " + msg + "\n";
                appendToFile(f, line);
            } catch (Throwable ignored) {}
        }
    }

    private static void recordCrash(Thread thread, Throwable ex) {
        String trace = stackTrace(ex);
        String threadName = thread != null ? thread.getName() : "?";
        e("CRASH", "Uncaught exception in thread " + threadName + ":\n" + trace);

        Context c = appContext;
        if (c == null) return;
        synchronized (LOCK) {
            try {
                File dir = c.getFilesDir();
                if (dir == null) return;
                File f = new File(dir, CRASH_FILE);
                if (f.length() > MAX_CRASH_BYTES) f.delete();
                Runtime rt = Runtime.getRuntime();
                StringBuilder sb = new StringBuilder();
                sb.append("=== CRASH ").append(TS.format(new Date())).append(" ===\n");
                sb.append("Thread: ").append(threadName).append("\n");
                sb.append("App: ").append(versionString(c)).append("\n");
                sb.append("Android: ").append(bv("RELEASE")).append(" (SDK ").append(sdkInt()).append(")\n");
                sb.append("Memory (MB): used ").append((rt.totalMemory() - rt.freeMemory()) / 1048576)
                  .append(" / max ").append(rt.maxMemory() / 1048576).append("\n");
                sb.append(trace).append("\n");
                appendToFile(f, sb.toString());
            } catch (Throwable ignored) {}
        }
    }

    private static void appendToFile(File f, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(f, true);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    public static String stackTrace(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Throwable x) {
            return String.valueOf(t);
        }
    }

    // ------------------------------------------------------------------ report

    /** Полный отчёт: приложение, устройство, настройки, краши, логи. */
    public static String buildReport(Context ctx) {
        if (appContext == null) init(ctx);
        Context c = appContext != null ? appContext : ctx;
        StringBuilder sb = new StringBuilder();

        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sb.append("===== OldGemAI report =====\n");
        sb.append("Created: ").append(fmt.format(new Date())).append(" (")
          .append(TimeZone.getDefault().getID()).append(")\n\n");

        // --- Приложение
        sb.append("--- App ---\n");
        line(sb, "Package", c.getPackageName());
        line(sb, "Version", versionString(c));
        sb.append("\n");

        // --- Устройство
        sb.append("--- Device ---\n");
        line(sb, "Manufacturer", bf("MANUFACTURER"));
        line(sb, "Brand", bf("BRAND"));
        line(sb, "Model", bf("MODEL"));
        line(sb, "Device", bf("DEVICE"));
        line(sb, "Product", bf("PRODUCT"));
        line(sb, "Board", bf("BOARD"));
        line(sb, "Hardware", bf("HARDWARE"));
        line(sb, "CPU ABI", bf("CPU_ABI"));
        line(sb, "CPU ABI2", bf("CPU_ABI2"));
        line(sb, "Supported ABIs", bf("SUPPORTED_ABIS"));
        line(sb, "Android", bv("RELEASE") + " (SDK " + sdkInt() + ", " + bv("CODENAME") + ")");
        line(sb, "Build incremental", bv("INCREMENTAL"));
        line(sb, "Build type/tags", bf("TYPE") + " / " + bf("TAGS"));
        line(sb, "Fingerprint", bf("FINGERPRINT"));
        try {
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            String dpi;
            try { dpi = String.valueOf(dm.densityDpi); } catch (Throwable t) { dpi = "n/a"; }
            line(sb, "Screen", dm.widthPixels + "x" + dm.heightPixels + ", density " + dm.density + ", dpi " + dpi);
        } catch (Throwable t) {
            line(sb, "Screen", "n/a");
        }
        line(sb, "Locale", String.valueOf(Locale.getDefault()));
        line(sb, "Time zone", TimeZone.getDefault().getID());
        try {
            Runtime rt = Runtime.getRuntime();
            line(sb, "VM memory (MB)", "used " + ((rt.totalMemory() - rt.freeMemory()) / 1048576)
                    + ", total " + (rt.totalMemory() / 1048576) + ", max " + (rt.maxMemory() / 1048576));
        } catch (Throwable t) {
            line(sb, "VM memory", "n/a");
        }
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long bs = st.getBlockSize();
            line(sb, "Internal storage (MB)", "free " + (st.getAvailableBlocks() * bs / 1048576)
                    + " of " + (st.getBlockCount() * bs / 1048576));
        } catch (Throwable t) {
            line(sb, "Internal storage", "n/a");
        }
        try {
            line(sb, "External storage", Environment.getExternalStorageState());
        } catch (Throwable t) {
            line(sb, "External storage", "n/a");
        }
        sb.append("\n");

        // --- Настройки (без секретов)
        sb.append("--- Settings ---\n");
        try {
            Prefs p = new Prefs(c);
            String key = p.getApiKey();
            line(sb, "API key", (key != null && key.length() > 0) ? "set (length " + key.length() + ")" : "not set");
            line(sb, "Language", String.valueOf(p.getLanguage()));
            line(sb, "Model", p.getModel());
            line(sb, "Active model", p.getActiveModel());
            line(sb, "Image model", p.getImageModel().length() > 0 ? p.getImageModel() : "(none)");
            line(sb, "Image model remembered", String.valueOf(p.isImageRemember()));
            line(sb, "Temperature", String.valueOf(p.getTemperature()));
            line(sb, "Stream", String.valueOf(p.isStream()));
            line(sb, "Smooth text", String.valueOf(p.isSmoothText()));
            line(sb, "Thinking animation", String.valueOf(p.isThinkingAnim()));
            line(sb, "Google search", String.valueOf(p.isSearch()));
            line(sb, "System prompt length", String.valueOf(p.getSystemPrompt().length()));
            line(sb, "Trim context", p.isTrimContext() ? "on, limit " + p.getTrimLimit() : "off");
            line(sb, "Compress dialog", p.isCompressContext() ? "on" : "off");
            line(sb, "Compress prompt length", String.valueOf(p.getCompressPrompt().length()));
        } catch (Throwable t) {
            line(sb, "Settings", "read error: " + t);
        }
        sb.append("\n");

        // --- Краши
        sb.append("--- Crashes ---\n");
        String crashes = "";
        synchronized (LOCK) {
            try {
                File dir = c.getFilesDir();
                if (dir != null) crashes = readTail(new File(dir, CRASH_FILE), REPORT_CRASH_BYTES);
            } catch (Throwable t) {
                crashes = "(read error: " + t + ")";
            }
        }
        sb.append(crashes.trim().length() > 0 ? crashes : "No crashes recorded.\n");
        if (!crashes.endsWith("\n")) sb.append("\n");
        sb.append("\n");

        // --- Логи
        sb.append("--- Log ---\n");
        String logs = "";
        synchronized (LOCK) {
            try {
                File dir = c.getFilesDir();
                if (dir != null) {
                    String cur = readTail(new File(dir, LOG_FILE), REPORT_LOG_BYTES);
                    int left = REPORT_LOG_BYTES - cur.length();
                    String old = left > 1024 ? readTail(new File(dir, LOG_FILE_OLD), left) : "";
                    logs = old + cur;
                }
            } catch (Throwable t) {
                logs = "(read error: " + t + ")";
            }
        }
        sb.append(logs.trim().length() > 0 ? logs : "Log is empty.\n");
        if (!logs.endsWith("\n")) sb.append("\n");
        sb.append("===== end of report =====\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(value).append("\n");
    }

    private static String readTail(File f, int maxBytes) throws Exception {
        if (!f.exists()) return "";
        FileInputStream in = new FileInputStream(f);
        try {
            long len = f.length();
            long skip = len > maxBytes ? len - maxBytes : 0;
            long skipped = 0;
            while (skipped < skip) {
                long n = in.skip(skip - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            String s = new String(bos.toByteArray(), "UTF-8");
            if (skip > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0) s = s.substring(nl + 1);
                s = "...(truncated)\n" + s;
            }
            return s;
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ clipboard

    /** Копирует текст в буфер обмена (работает и на Android 1.x, и на новых версиях). */
    public static boolean copyToClipboard(Context ctx, String text) {
        try {
            if (sdkInt() >= 11) {
                ClipHelper.copy(ctx, text);
            } else {
                android.text.ClipboardManager cm =
                    (android.text.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setText(text);
            }
            return true;
        } catch (Throwable t) {
            e("AppLog", "copyToClipboard failed", t);
            return false;
        }
    }

    // Отдельный класс: на старых Android он не загрузится, пока его не вызовут
    private static class ClipHelper {
        static void copy(Context ctx, String text) {
            android.content.ClipboardManager cm =
                (android.content.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("OldGemAI logs", text));
        }
    }

    // ------------------------------------------------------------------ helpers

    static int sdkInt() {
        try {
            return Integer.parseInt(Build.VERSION.SDK);
        } catch (Throwable e) {
            return 1;
        }
    }

    private static String versionString(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (code " + pi.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    // Поля Build читаем через рефлексию: на старых Android части полей просто нет
    private static String bf(String name) {
        return readStatic(Build.class, name);
    }

    private static String bv(String name) {
        return readStatic(Build.VERSION.class, name);
    }

    private static String readStatic(Class<?> cls, String name) {
        try {
            Object v = cls.getField(name).get(null);
            if (v == null) return "?";
            if (v instanceof Object[]) {
                Object[] arr = (Object[]) v;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < arr.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(arr[i]);
                }
                return sb.toString();
            }
            return String.valueOf(v);
        } catch (Throwable t) {
            return "n/a";
        }
    }
}
