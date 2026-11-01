package com.geminiapp.chat;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Автообновление: раз в сутки смотрит на GitHub, какая версия вышла последней, и если она новее
 * установленной, показывает окно с кнопкой "Установить" (скачивание с прогрессбаром).
 *
 * Последняя версия определяется по перенаправлению https://github.com/andr9y3/OldGemAI/releases/latest
 * на https://github.com/andr9y3/OldGemAI/releases/tag/ТЕГ (API GitHub не нужен, лимитов нет).
 * APK в релизе всегда называется app-debug-aligned-signed.apk.
 *
 * Сеть идёт через Http, поэтому всё работает и на Android 1.0-4.0 (встроенный TLS 1.2).
 *
 * Поворот экрана: всё состояние окна (версии, фаза, сколько скачано) хранится в статических полях, а не в Activity.
 * При уничтожении Activity окно снимается (detach), у новой Activity оно возвращается (attach) уже с текущим прогрессом;
 * сама загрузка идёт в своём потоке и поворот не замечает. Если Activity закрыта пользователем (isFinishing),
 * загрузка отменяется.
 */
public final class Updater {

    private Updater() {}

    static final String REPO = "andr9y3/OldGemAI";
    static final String APK_NAME = "app-debug-aligned-signed.apk";
    /** Куда сохраняется скачанный APK (во внутреннем хранилище приложения). */
    static final String UPDATE_FILE = "update.apk";
    /** Должен совпадать с android:authorities у UpdateFileProvider в манифесте. */
    static final String AUTHORITY = "com.geminiapp.chat.updates";
    static final String APK_MIME = "application/vnd.android.package-archive";

    private static final String LATEST_URL = "https://github.com/" + REPO + "/releases/latest";
    private static final String TAG_MARK = "/releases/tag/";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean checking = false;

    // ------------------------------------------------------------------ состояние окна (живёт дольше Activity)

    private static final int PH_OFFER = 0;       // предложено, ничего не скачивается
    private static final int PH_DOWNLOADING = 1; // идёт скачивание
    private static final int PH_ERROR = 2;       // ошибка загрузки или запуска установки

    private static final class Offer {
        final String cur;
        final String newVer;
        final String tag;
        int phase = PH_OFFER;
        long done = 0;
        long total = -1;
        String message = "";
        Download download = null;

        Offer(String cur, String newVer, String tag) {
            this.cur = cur;
            this.newVer = newVer;
            this.tag = tag;
        }
    }

    private static final class Ui {
        final Activity owner;
        final Dialog dialog;
        final Button btnInstall;
        final ProgressBar bar;
        final TextView tvStatus;

        Ui(Activity owner, Dialog dialog, Button btnInstall, ProgressBar bar, TextView tvStatus) {
            this.owner = owner;
            this.dialog = dialog;
            this.btnInstall = btnInstall;
            this.bar = bar;
            this.tvStatus = tvStatus;
        }
    }

    /** Текущее предложение обновления (null - окна нет). Трогаем только из главного потока. */
    private static Offer pending = null;
    /** Окно, показанное сейчас (null - не показано, например между уничтожением и созданием Activity при повороте). */
    private static Ui ui = null;
    /** Класс Activity, в которой окно было показано последний раз: именно она получает его обратно после поворота. */
    private static Class<?> lastOwnerClass = null;

    /**
     * Вызывать из onCreate экрана: после поворота возвращает окно, если оно было на этом экране.
     * Окно чужого экрана (например, MainActivity при открытых настройках) не перехватывается.
     */
    public static void attach(Activity a) {
        if (pending == null || ui != null || a.isFinishing()) return;
        if (lastOwnerClass != a.getClass()) return;
        buildUi(a);
    }

    /**
     * Вызывать из onDestroy экрана. Поворот: окно снимается, загрузка продолжается.
     * Закрытие экрана пользователем: загрузка отменяется.
     */
    public static void detach(Activity a) {
        if (ui == null || ui.owner != a) return;
        lastOwnerClass = a.getClass();
        dismissUi();
        if (a.isFinishing()) closePending();
    }

    // ------------------------------------------------------------------ запуск проверки

    /** Вызывается при запуске приложения: проверяет не чаще раза в сутки и только если автообновление включено. */
    public static void checkDaily(Activity a) {
        Context app = a.getApplicationContext();
        if (pending == null) app.deleteFile(UPDATE_FILE); // остаток прошлой установки
        Prefs prefs = new Prefs(app);
        if (!prefs.isAutoUpdate()) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLastUpdateCheck();
        if (last > 0 && now >= last && now - last < DAY_MS) return;
        check(a, false);
    }

    /** manual = true: проверка по кнопке, о результате сообщается всегда (даже если обновлений нет). */
    public static void check(final Activity a, final boolean manual) {
        final boolean ru = isRu(a);
        if (pending != null) {
            // Окно обновления уже есть (его могли закрыть поворотом или другим экраном): просто показываем его здесь
            if (manual) showHere(a);
            return;
        }
        if (checking) {
            if (manual) Toast.makeText(a, ru ? "Проверка уже идёт" : "Already checking", Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        if (manual) Toast.makeText(a, ru ? "Проверка обновлений..." : "Checking for updates...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = null;
                String error = null;
                try {
                    tag = fetchLatestTag();
                } catch (Throwable t) {
                    error = String.valueOf(t);
                }
                final String fTag = tag;
                final String fError = error;
                UI.post(new Runnable() {
                    @Override
                    public void run() {
                        checking = false;
                        onCheckResult(a, fTag, fError, manual);
                    }
                });
            }
        }).start();
    }

    private static void onCheckResult(Activity a, String tag, String error, boolean manual) {
        Context app = a.getApplicationContext();
        boolean ru = isRu(app);
        if (error != null) {
            AppLog.w("Update", "check failed: " + error);
            if (manual) Toast.makeText(a, ru ? "Не удалось проверить обновления" : "Could not check for updates", Toast.LENGTH_LONG).show();
            return;
        }
        // Время фиксируем только после успешной проверки: при ошибке сети следующая попытка будет при следующем запуске
        new Prefs(app).setLastUpdateCheck(System.currentTimeMillis());

        String cur = currentVersion(app);
        if (tag == null) {
            AppLog.d("Update", "no releases found");
            if (manual) Toast.makeText(a, ru ? "Релизов пока нет" : "No releases yet", Toast.LENGTH_LONG).show();
            return;
        }
        AppLog.d("Update", "installed=" + cur + ", latest=" + tag);
        if (!isNewer(tag, cur)) {
            if (manual) Toast.makeText(a, (ru ? "Установлена последняя версия: " : "You have the latest version: ") + cur, Toast.LENGTH_LONG).show();
            return;
        }
        if (a.isFinishing()) return;
        if (pending == null) pending = new Offer(cur, cleanVersion(tag), tag);
        showHere(a);
    }

    // ------------------------------------------------------------------ версия на GitHub

    /** Возвращает тег последнего релиза или null, если релизов нет. */
    private static String fetchLatestTag() throws IOException {
        String url = LATEST_URL;
        for (int hop = 0; hop < 6; hop++) {
            Http.Response r = Http.request("GET", url, headers(), null, 20000, 30000);
            try {
                if (r.code >= 300 && r.code < 400 && r.location != null) {
                    String loc = absolute(url, r.location);
                    int p = loc.indexOf(TAG_MARK);
                    if (p >= 0) {
                        String tag = loc.substring(p + TAG_MARK.length());
                        int cut = tag.indexOf('?');
                        if (cut >= 0) tag = tag.substring(0, cut);
                        cut = tag.indexOf('#');
                        if (cut >= 0) tag = tag.substring(0, cut);
                        while (tag.endsWith("/")) tag = tag.substring(0, tag.length() - 1);
                        return tag.length() > 0 ? tag : null;
                    }
                    url = loc;
                    continue;
                }
                if (r.code == 200) return null; // страница релизов без перенаправления на тег: релизов нет
                throw new IOException("HTTP " + r.code);
            } finally {
                r.close();
            }
        }
        throw new IOException("too many redirects");
    }

    static String currentVersion(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v != null ? v : "0";
        } catch (Throwable t) {
            return "0";
        }
    }

    /** "v2.1" -> "2.1" (для показа в окне). */
    static String cleanVersion(String tag) {
        int i = 0;
        while (i < tag.length() && !Character.isDigit(tag.charAt(i))) i++;
        return i < tag.length() ? tag.substring(i) : tag;
    }

    /** true, если версия remote строго больше версии local (сравнение по числам: 2.10 > 2.9). */
    static boolean isNewer(String remote, String local) {
        int[] a = parseVersion(remote);
        int[] b = parseVersion(local);
        if (a.length == 0) return false;
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    /** "v2.1.3-beta" -> {2, 1, 3}. Всё после первого символа, не являющегося цифрой или точкой, отбрасывается. */
    private static int[] parseVersion(String s) {
        String v = cleanVersion(s == null ? "" : s);
        int end = 0;
        while (end < v.length() && (Character.isDigit(v.charAt(end)) || v.charAt(end) == '.')) end++;
        v = v.substring(0, end);
        if (v.length() == 0 || !Character.isDigit(v.charAt(0))) return new int[0];

        java.util.Vector parts = new java.util.Vector();
        int start = 0;
        for (int i = 0; i <= v.length(); i++) {
            if (i == v.length() || v.charAt(i) == '.') {
                String part = v.substring(start, i);
                int num = 0;
                if (part.length() > 0) {
                    try { num = Integer.parseInt(part.length() > 9 ? part.substring(0, 9) : part); } catch (Exception ignored) {}
                }
                parts.addElement(Integer.valueOf(num));
                start = i + 1;
            }
        }
        int[] res = new int[parts.size()];
        for (int i = 0; i < res.length; i++) res[i] = ((Integer) parts.elementAt(i)).intValue();
        return res;
    }

    // ------------------------------------------------------------------ сеть

    private static String[] headers() {
        // На API 16+ явный identity отключает прозрачный gzip, иначе Content-Length (для прогрессбара) не совпал бы с данными.
        // На API 1-15 identity добавляет сам Http.
        if (Http.sdk() >= 16) {
            return new String[] {"User-Agent", "OldGemAI-Updater", "Accept", "*/*", "Accept-Encoding", "identity"};
        }
        return new String[] {"User-Agent", "OldGemAI-Updater", "Accept", "*/*"};
    }

    private static String absolute(String base, String loc) {
        if (loc.startsWith("http")) return loc;
        if (loc.startsWith("/")) {
            int s = base.indexOf('/', 8);
            return (s < 0 ? base : base.substring(0, s)) + loc;
        }
        return loc;
    }

    // ------------------------------------------------------------------ скачивание

    private static final class Download implements Runnable {
        private final Context app;
        private final String tag;
        volatile boolean cancelled = false;

        Download(Context app, String tag) {
            this.app = app;
            this.tag = tag;
        }

        private void postProgress(final long done, final long total) {
            UI.post(new Runnable() {
                @Override
                public void run() {
                    if (!cancelled) onDownloadProgress(Download.this, done, total);
                }
            });
        }

        @Override
        public void run() {
            Http.Response r = null;
            OutputStream out = null;
            boolean ok = false;
            String error = null;
            try {
                String url = "https://github.com/" + REPO + "/releases/download/" + tag + "/" + APK_NAME;
                for (int hop = 0; hop < 8; hop++) {
                    r = Http.request("GET", url, headers(), null, 20000, 30000);
                    if (r.code >= 300 && r.code < 400 && r.location != null) {
                        url = absolute(url, r.location);
                        r.close();
                        r = null;
                        continue;
                    }
                    break;
                }
                if (r == null) throw new IOException("too many redirects");
                if (r.code != 200) throw new IOException("HTTP " + r.code);

                long total = r.contentLength;
                // До Android 7 установщик читает файл напрямую, поэтому он должен быть доступен для чтения всем;
                // с Android 7 файл отдаёт UpdateFileProvider.
                int mode = Http.sdk() < 24 ? Context.MODE_WORLD_READABLE : Context.MODE_PRIVATE;
                out = app.openFileOutput(UPDATE_FILE, mode);

                InputStream in = r.in;
                byte[] buf = new byte[16384];
                long done = 0;
                long lastReport = 0;
                boolean checked = false;
                int n;
                postProgress(0, total);
                while (!cancelled && (n = in.read(buf)) != -1) {
                    if (!checked && n >= 2) {
                        if (buf[0] != 'P' || buf[1] != 'K') throw new IOException("not an APK file");
                        checked = true;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport >= 150) {
                        lastReport = now;
                        postProgress(done, total);
                    }
                }
                out.flush();
                if (!cancelled) {
                    if (total > 0 && done != total) throw new IOException("incomplete download " + done + "/" + total);
                    if (done < 1024) throw new IOException("file too small");
                    postProgress(done, total);
                    ok = true;
                }
            } catch (Throwable t) {
                error = String.valueOf(t);
            } finally {
                if (out != null) { try { out.close(); } catch (Throwable ignored) {} }
                if (r != null) r.close();
                if (!ok) app.deleteFile(UPDATE_FILE);
            }

            if (cancelled) return;
            if (ok) {
                UI.post(new Runnable() {
                    @Override
                    public void run() { onDownloadDone(Download.this); }
                });
            } else {
                final String msg = error != null ? error : "unknown error";
                AppLog.w("Update", "download failed: " + msg);
                UI.post(new Runnable() {
                    @Override
                    public void run() { onDownloadError(Download.this, msg); }
                });
            }
        }
    }

    // Результаты загрузки (главный поток). Проверка o.download != d отсекает запоздавшие сообщения отменённой загрузки.

    private static void onDownloadProgress(Download d, long done, long total) {
        Offer o = pending;
        if (o == null || o.download != d) return;
        o.done = done;
        o.total = total;
        render();
    }

    private static void onDownloadDone(Download d) {
        Offer o = pending;
        if (o == null || o.download != d) return;
        o.download = null;
        boolean ru = isRu(d.app);
        try {
            install(d.app);
            pending = null;
            dismissUi();
        } catch (Throwable t) {
            AppLog.w("Update", "install failed: " + t);
            o.phase = PH_ERROR;
            o.message = (ru ? "Не удалось запустить установку: " : "Could not start the installer: ") + t;
            render();
        }
    }

    private static void onDownloadError(Download d, String message) {
        Offer o = pending;
        if (o == null || o.download != d) return;
        o.download = null;
        o.phase = PH_ERROR;
        o.message = (isRu(d.app) ? "Ошибка загрузки: " : "Download failed: ") + message;
        render();
    }

    private static void startDownload(Context app) {
        Offer o = pending;
        if (o == null || o.download != null) return;
        boolean ru = isRu(app);
        if (!installAllowed(app)) {
            openInstallSettings(app);
            Toast.makeText(app, ru
                ? "Разрешите установку из этого приложения и нажмите «Установить» снова"
                : "Allow installs from this app, then tap Install again", Toast.LENGTH_LONG).show();
            return;
        }
        o.phase = PH_DOWNLOADING;
        o.done = 0;
        o.total = -1;
        o.message = "";
        Download d = new Download(app, o.tag);
        o.download = d;
        render();
        new Thread(d).start();
    }

    /** Закрыть предложение совсем: отменить загрузку (файл удалит её поток) и убрать окно. */
    private static void closePending() {
        Offer o = pending;
        pending = null;
        if (o != null && o.download != null) o.download.cancelled = true;
        dismissUi();
    }

    // ------------------------------------------------------------------ установка

    private static void install(Context app) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        if (Http.sdk() >= 24) {
            i.setDataAndType(Uri.parse("content://" + AUTHORITY + "/" + UPDATE_FILE), APK_MIME);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            i.setDataAndType(Uri.fromFile(app.getFileStreamPath(UPDATE_FILE)), APK_MIME);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        app.startActivity(i);
    }

    /** С Android 8 установка APK разрешается для каждого приложения отдельно. */
    private static boolean installAllowed(Context app) {
        if (Http.sdk() < 26) return true;
        try {
            return Api26.canInstall(app);
        } catch (Throwable t) {
            return true;
        }
    }

    private static final class Api26 {
        static boolean canInstall(Context c) {
            return c.getPackageManager().canRequestPackageInstalls();
        }
    }

    private static void openInstallSettings(Context app) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ окно

    /** Показать окно в этой Activity (если оно было в другой или уже показано, переносится сюда). */
    private static void showHere(Activity a) {
        if (pending == null || a.isFinishing()) return;
        if (ui != null && ui.owner == a) return;
        dismissUi();
        buildUi(a);
    }

    private static void dismissUi() {
        Ui u = ui;
        ui = null;
        if (u != null) {
            try { u.dialog.dismiss(); } catch (Throwable ignored) {}
        }
    }

    private static void buildUi(final Activity a) {
        final Offer o = pending;
        if (o == null) return;
        final Context app = a.getApplicationContext();
        final boolean ru = isRu(app);
        final Prefs prefs = new Prefs(app);
        final int pad = dp(a, 16);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, dp(a, 8));

        TextView tvTitle = new TextView(a);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setPadding(0, 0, 0, dp(a, 12));
        tvTitle.setText(ru ? "Доступно обновление" : "Update available");
        box.addView(tvTitle);

        TextView tvCur = new TextView(a);
        tvCur.setTextSize(16);
        tvCur.setTextColor(0xFFFFFFFF);
        tvCur.setText((ru ? "Сейчас: " : "Current: ") + o.cur);
        box.addView(tvCur);

        TextView tvNew = new TextView(a);
        tvNew.setTextSize(16);
        tvNew.setTextColor(0xFFFFFFFF);
        tvNew.setPadding(0, dp(a, 4), 0, dp(a, 12));
        tvNew.setText((ru ? "Новая версия: " : "New version: ") + o.newVer);
        box.addView(tvNew);

        Button btnInstall = new Button(a);
        btnInstall.setText(ru ? "Установить" : "Install");
        box.addView(btnInstall, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barParams.setMargins(0, dp(a, 6), 0, 0);
        box.addView(bar, barParams);

        TextView tvStatus = new TextView(a);
        tvStatus.setTextSize(13);
        tvStatus.setTextColor(0xFFCCCCCC);
        tvStatus.setVisibility(View.GONE);
        box.addView(tvStatus);

        Button btnCancel = new Button(a);
        btnCancel.setText(ru ? "Отмена" : "Cancel");
        box.addView(btnCancel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.FILL_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox cbNever = new CheckBox(a);
        cbNever.setText(ru ? "Больше не спрашивать" : "Don't ask again");
        cbNever.setTextColor(0xFFFFFFFF);
        cbNever.setChecked(!prefs.isAutoUpdate());
        box.addView(cbNever);
        // Галка сразу отключает (или снова включает) автообновление
        cbNever.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setAutoUpdate(!checked);
            }
        });

        // Фон и цвета заданы явно, чтобы окно выглядело одинаково на любой версии Android и любой теме диалогов
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(0xFF2B2B2B);
        scroll.addView(box);

        Dialog dlg = new Dialog(a);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dlg.setContentView(scroll);
        dlg.getWindow().setLayout(android.view.ViewGroup.LayoutParams.FILL_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);

        // Кнопка "Назад" равносильна "Отмене"
        dlg.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                closePending();
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePending();
            }
        });
        btnInstall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload(app);
            }
        });

        ui = new Ui(a, dlg, btnInstall, bar, tvStatus);
        lastOwnerClass = a.getClass();
        render();
        try {
            dlg.show();
        } catch (Throwable t) {
            // Окно экрана уже не принимает диалоги (экран закрывается): снимем и дождёмся следующего attach
            AppLog.w("Update", "dialog show failed: " + t);
            ui = null;
        }
    }

    /** Приводит окно в соответствие с текущим состоянием (фаза, скачано, ошибка). */
    private static void render() {
        Offer o = pending;
        Ui u = ui;
        if (o == null || u == null) return;
        boolean ru = isRu(u.owner);

        if (o.phase == PH_DOWNLOADING) {
            u.btnInstall.setEnabled(false);
            u.btnInstall.setText(ru ? "Скачивание..." : "Downloading...");
            u.bar.setVisibility(View.VISIBLE);
            u.tvStatus.setVisibility(View.VISIBLE);
            if (o.total > 0) {
                int percent = (int) (o.done * 100L / o.total);
                if (percent > 100) percent = 100;
                u.bar.setIndeterminate(false);
                u.bar.setProgress(percent);
                u.tvStatus.setText((ru ? "Скачано: " : "Downloaded: ") + fmtSize(o.done, ru)
                    + (ru ? " из " : " of ") + fmtSize(o.total, ru) + " (" + percent + "%)");
            } else {
                u.bar.setIndeterminate(true);
                u.tvStatus.setText(o.done > 0
                    ? (ru ? "Скачано: " : "Downloaded: ") + fmtSize(o.done, ru)
                    : (ru ? "Подключение..." : "Connecting..."));
            }
        } else if (o.phase == PH_ERROR) {
            u.btnInstall.setEnabled(true);
            u.btnInstall.setText(ru ? "Повторить" : "Retry");
            u.bar.setVisibility(View.GONE);
            u.tvStatus.setVisibility(View.VISIBLE);
            u.tvStatus.setText(o.message);
        } else {
            u.btnInstall.setEnabled(true);
            u.btnInstall.setText(ru ? "Установить" : "Install");
            u.bar.setVisibility(View.GONE);
            u.tvStatus.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ мелочи

    private static boolean isRu(Context c) {
        return Prefs.LANG_RU.equals(new Prefs(c).getLanguage());
    }

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String fmtSize(long bytes, boolean ru) {
        long tenths = bytes * 10L / (1024L * 1024L);
        return (tenths / 10) + "." + (tenths % 10) + (ru ? " МБ" : " MB");
    }
}
