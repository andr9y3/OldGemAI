package com.geminiapp.chat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class ChatAdapter extends BaseAdapter {

    private static final int FLAG = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;

    // Код: чёрный фон, светлый текст
    private static final int CODE_BG = 0xFF000000;
    private static final int CODE_FG = 0xFFEEEEEE;
    // Ссылки: синие
    private static final int LINK_COLOR = 0xFF0B57D0;

    // Картинки из Markdown: ![описание](https://...)
    private static final int CACHE_MAX = 8;
    private static final int MAX_IMG_PX = 640;
    private static final int MAX_DOWNLOAD = 6 * 1024 * 1024;
    // Все три коллекции трогаем только из главного потока
    private static final LinkedHashMap<String, Bitmap> imageCache = new LinkedHashMap<String, Bitmap>();
    private static final HashSet<String> imageLoading = new HashSet<String>();
    private static final HashSet<String> imageFailed = new HashSet<String>();

    // Типы блоков в ответе модели
    private static final int P_TEXT = 0;
    private static final int P_CODE = 1;
    private static final int P_IMAGE = 2;
    private static final int P_RULE = 3;
    private static final int P_TABLE = 4;

    private static class Part {
        int type;
        String text;          // текст, код или описание картинки
        String url;           // адрес картинки
        List<String[]> rows;  // строки таблицы, первая - заголовок
    }

    private final Context context;

    // Кнопка "перегенерировать ответ" показывается только под последним ответом модели
    public interface RegenerateListener {
        boolean canRegenerate();
        void onRegenerate();
    }

    private RegenerateListener regenerateListener;

    // Меню по долгому нажатию на сообщении пользователя: «Изменить» и «Копировать»
    public interface EditListener {
        void onEdit(int position);
    }

    private EditListener editListener;

    public void setEditListener(EditListener l) {
        this.editListener = l;
    }

    public void setRegenerateListener(RegenerateListener l) {
        this.regenerateListener = l;
    }

    // Кнопки «Копировать» и «Изменить» под ответом модели
    public interface ResponseActionListener {
        // true, пока ответ ещё генерируется (кнопки под ним не показываются)
        boolean isBusy();
        void onEditResponse(int position);
    }

    private ResponseActionListener responseActionListener;

    public void setResponseActionListener(ResponseActionListener l) {
        this.responseActionListener = l;
    }
    private final List<GeminiApi.Message> messages;

    public ChatAdapter(Context context, List<GeminiApi.Message> messages) {
        this.context = context;
        this.messages = messages;
    }

    @Override
    public int getCount() { return messages.size(); }

    @Override
    public Object getItem(int pos) { return messages.get(pos); }

    @Override
    public long getItemId(int pos) { return pos; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        GeminiApi.Message msg = messages.get(position);
        boolean isUser = "user".equals(msg.role);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(4), dp(8), dp(4));
        container.setGravity(isUser ? Gravity.RIGHT : Gravity.LEFT);

        // Если это сгенерированная картинка
        if (msg.isGeneratedImage && msg.imageBase64 != null) {
            try {
                byte[] bytes = Base64Compat.decode(msg.imageBase64);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ImageView iv = new ImageView(context);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    int maxW = parent.getWidth() > 0 ? (int)(parent.getWidth() * 0.8f) : dp(240);
                    float scale = maxW / (float) bmp.getWidth();
                    int scaledH = (int)(bmp.getHeight() * scale);
                    iv.setImageBitmap(Bitmap.createScaledBitmap(bmp, maxW, scaledH, true));
                    iv.setBackgroundColor(0xFF333333);
                    iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                    container.addView(iv);
                }
            } catch (Exception ignored) {}

            if (msg.text != null && msg.text.length() > 0) {
                TextView caption = new TextView(context);
                caption.setText(msg.text);
                caption.setTextSize(13);
                caption.setTextColor(0xFF888888);
                container.addView(caption);
            }
            addActionButtons(container, position, null);
            return container;
        }

        // Текстовый пузырь
        LinearLayout bubbleRow = new LinearLayout(context);
        bubbleRow.setOrientation(LinearLayout.HORIZONTAL);
        bubbleRow.setGravity(isUser ? Gravity.RIGHT : Gravity.LEFT);

        if (isUser && msg.imageBase64 != null) {
            try {
                byte[] bytes = Base64Compat.decode(msg.imageBase64);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ImageView iv = new ImageView(context);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setImageBitmap(Bitmap.createScaledBitmap(bmp, dp(120), dp(120), true));
                    iv.setPadding(dp(2), dp(2), dp(2), dp(2));
                    iv.setBackgroundColor(0xFF444444);
                    LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(dp(120), dp(120));
                    ivp.setMargins(0, 0, dp(6), 0);
                    container.addView(iv, ivp);
                }
            } catch (Exception ignored) {}
        }

        if (isUser) {
            TextView tv = new TextView(context);
            tv.setText(msg.text);
            tv.setTextSize(15);
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));
            tv.setBackgroundResource(R.drawable.bubble_user);
            tv.setTextColor(0xFFFFFFFF);
            attachUserMessageMenu(tv, msg.text, position);
            bubbleRow.addView(tv);
            container.addView(bubbleRow);
            return container;
        }

        // Ответ модели: пузырь из блоков (текст, код, картинки, таблицы, линии)
        String full = msg.text != null ? msg.text : "";
        LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackgroundResource(R.drawable.bubble_model);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));

        // Пока ответа ещё нет (заглушка "..."), показываем анимацию из трёх точек
        if ("...".equals(full) && new Prefs(context).isThinkingAnim()) {
            bubble.addView(new TypingDotsView(context), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            bubbleRow.addView(bubble);
            container.addView(bubbleRow);
            return container;
        }

        List<Part> parts = splitParts(full);
        if (parts.isEmpty()) {
            Part empty = new Part();
            empty.type = P_TEXT;
            empty.text = "";
            parts.add(empty);
        }
        int maxImgW = parent.getWidth() > 0 ? (int)(parent.getWidth() * 0.75f) : dp(220);
        for (int i = 0; i < parts.size(); i++) {
            addPartView(bubble, parts.get(i), full, maxImgW);
        }

        bubbleRow.addView(bubble);
        container.addView(bubbleRow);
        addActionButtons(container, position, full);
        return container;
    }

    // ---------------------------------------------------------------------------------
    // Блоки
    // ---------------------------------------------------------------------------------

    private void addPartView(LinearLayout bubble, Part p, String fullText, int maxImgW) {
        final boolean ru = Prefs.LANG_RU.equals(new Prefs(context).getLanguage());
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        int fill = ViewGroup.LayoutParams.FILL_PARENT;

        if (p.type == P_TEXT) {
            TextView tv = new TextView(context);
            tv.setTextSize(15);
            tv.setTextColor(0xFF222222);
            tv.setText(parseMarkdown(p.text));
            tv.setLinkTextColor(LINK_COLOR);
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.addView(tv, new LinearLayout.LayoutParams(wrap, wrap));
            return;
        }

        if (p.type == P_CODE) {
            TextView tv = new TextView(context);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(13);
            tv.setTextColor(CODE_FG);
            tv.setBackgroundColor(CODE_BG);
            tv.setPadding(dp(8), dp(6), dp(8), dp(6));
            tv.setText(p.text);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(fill, wrap);
            lp.setMargins(0, dp(4), 0, dp(4));
            bubble.addView(tv, lp);
            return;
        }

        if (p.type == P_RULE) {
            View line = new View(context);
            line.setBackgroundColor(0xFF999999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(fill, dp(1));
            lp.setMargins(0, dp(6), 0, dp(6));
            bubble.addView(line, lp);
            return;
        }

        if (p.type == P_TABLE) {
            TableLayout table = new TableLayout(context);
            table.setShrinkAllColumns(true);
            table.setBackgroundColor(0xFF999999);
            for (int r = 0; r < p.rows.size(); r++) {
                TableRow tr = new TableRow(context);
                String[] cells = p.rows.get(r);
                for (int c = 0; c < cells.length; c++) {
                    TextView cell = new TextView(context);
                    SpannableStringBuilder sb = new SpannableStringBuilder();
                    parseInlineMarkdown(cells[c].trim(), sb, false);
                    if (r == 0 && sb.length() > 0) {
                        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), FLAG);
                    }
                    cell.setText(sb);
                    cell.setTextSize(13);
                    cell.setTextColor(0xFF222222);
                    cell.setLinkTextColor(LINK_COLOR);
                    cell.setMovementMethod(LinkMovementMethod.getInstance());
                    cell.setBackgroundColor(r == 0 ? 0xFFD9D9D9 : 0xFFF2F2F2);
                    cell.setPadding(dp(6), dp(4), dp(6), dp(4));
                    TableRow.LayoutParams lp = new TableRow.LayoutParams(wrap, wrap);
                    lp.setMargins(1, 1, 1, 1);
                    tr.addView(cell, lp);
                }
                table.addView(tr, new TableLayout.LayoutParams(fill, wrap));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap, wrap);
            lp.setMargins(0, dp(4), 0, dp(4));
            bubble.addView(table, lp);
            return;
        }

        // P_IMAGE
        final String url = p.url;
        Bitmap bmp = imageCache.get(url);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap, wrap);
        lp.setMargins(0, dp(4), 0, dp(4));
        if (bmp != null) {
            ImageView iv = new ImageView(context);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setMaxWidth(maxImgW);
            iv.setImageBitmap(bmp);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl(url);
                }
            });
            bubble.addView(iv, lp);
        } else if (imageFailed.contains(url)) {
            TextView tv = new TextView(context);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(ru ? "Изображение: " : "Image: ");
            int st = sb.length();
            sb.append(p.text != null && p.text.trim().length() > 0 ? p.text.trim() : url);
            sb.setSpan(new URLSpan(url), st, sb.length(), FLAG);
            tv.setText(sb);
            tv.setTextSize(14);
            tv.setTextColor(0xFF555555);
            tv.setLinkTextColor(LINK_COLOR);
            tv.setMovementMethod(LinkMovementMethod.getInstance());
            bubble.addView(tv, lp);
        } else {
            ensureImage(url);
            TextView tv = new TextView(context);
            tv.setText(ru ? "Загрузка изображения..." : "Loading image...");
            tv.setTextSize(13);
            tv.setTextColor(0xFF888888);
            bubble.addView(tv, lp);
        }
    }

    /**
     * Делит ответ на блоки: обычный текст, блоки кода, картинки, таблицы и горизонтальные линии.
     * Внутри блоков кода и обратных кавычек ничего не разбирается.
     */
    private static List<Part> splitParts(String text) {
        List<Part> parts = new ArrayList<Part>();
        String[] lines = text.split("\n", -1);
        List<String> textLines = new ArrayList<String>();
        List<String> codeLines = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.trim();

            // Внутри блока кода: ждём закрывающее ограждение
            if (codeLines != null) {
                if (t.startsWith("```")) {
                    parts.add(codePart(codeLines));
                    codeLines = null;
                } else {
                    codeLines.add(line);
                }
                continue;
            }

            if (t.startsWith("```")) {
                flushText(parts, textLines);
                codeLines = new ArrayList<String>();
                continue;
            }

            if (isRule(t)) {
                flushText(parts, textLines);
                Part rule = new Part();
                rule.type = P_RULE;
                parts.add(rule);
                continue;
            }

            // Таблица: строка с | и следом строка-разделитель |---|---|
            if (t.startsWith("|") && i + 1 < lines.length && isTableSeparator(lines[i + 1].trim())) {
                flushText(parts, textLines);
                List<String[]> rows = new ArrayList<String[]>();
                rows.add(splitRow(t));
                int j = i + 2;
                while (j < lines.length && lines[j].trim().startsWith("|")) {
                    rows.add(splitRow(lines[j].trim()));
                    j++;
                }
                Part table = new Part();
                table.type = P_TABLE;
                table.rows = rows;
                parts.add(table);
                i = j - 1;
                continue;
            }

            // Картинки ![описание](http...) режут строку на текст до, картинку и текст после
            String work = line;
            int from = 0;
            boolean hadImage = false;
            while (true) {
                int[] im = findImage(work, from);
                if (im == null) break;
                String url = cleanUrl(work.substring(im[4], im[5]));
                if (!isHttp(url)) {
                    from = im[1];
                    continue;
                }
                String before = work.substring(0, im[0]);
                if (before.trim().length() > 0) textLines.add(before);
                flushText(parts, textLines);
                Part img = new Part();
                img.type = P_IMAGE;
                img.url = url;
                img.text = work.substring(im[2], im[3]);
                parts.add(img);
                work = work.substring(im[1]);
                from = 0;
                hadImage = true;
            }
            if (!hadImage || work.trim().length() > 0) textLines.add(work);
        }

        // Незакрытый блок кода (ответ ещё печатается)
        if (codeLines != null) parts.add(codePart(codeLines));
        flushText(parts, textLines);
        return parts;
    }

    private static Part codePart(List<String> codeLines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < codeLines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(codeLines.get(i));
        }
        Part p = new Part();
        p.type = P_CODE;
        p.text = sb.toString();
        return p;
    }

    private static void flushText(List<Part> parts, List<String> lines) {
        int a = 0;
        int b = lines.size();
        while (a < b && lines.get(a).trim().length() == 0) a++;
        while (b > a && lines.get(b - 1).trim().length() == 0) b--;
        if (a < b) {
            StringBuilder sb = new StringBuilder();
            for (int i = a; i < b; i++) {
                if (i > a) sb.append('\n');
                sb.append(lines.get(i));
            }
            Part p = new Part();
            p.type = P_TEXT;
            p.text = sb.toString();
            parts.add(p);
        }
        lines.clear();
    }

    // Горизонтальная линия: три и больше одинаковых символов - * _ (пробелы между ними допустимы)
    private static boolean isRule(String t) {
        String s = t.replace(" ", "");
        if (s.length() < 3) return false;
        char c = s.charAt(0);
        if (c != '-' && c != '*' && c != '_') return false;
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) != c) return false;
        }
        return true;
    }

    private static boolean isTableSeparator(String t) {
        if (t.indexOf('|') < 0 || t.indexOf('-') < 0) return false;
        for (int i = 0; i < t.length(); i++) {
            if ("|-: ".indexOf(t.charAt(i)) < 0) return false;
        }
        return true;
    }

    private static String[] splitRow(String t) {
        String s = t;
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
        return s.split("\\|", -1);
    }

    // ---------------------------------------------------------------------------------
    // Разбор Markdown внутри текстовых блоков
    // ---------------------------------------------------------------------------------

    /**
     * Построчный разбор: заголовки (# ... ######, пробел после # обязателен), цитаты (> ...),
     * списки (*, -, + с учётом отступа), а внутри строк - `код`, **жирный**, __жирный__,
     * *курсив*, _курсив_, ***жирный курсив***, ~~зачёркнутый~~, [ссылки](url), голые http-адреса
     * и экранирование через \.
     */
    private CharSequence parseMarkdown(String text) {
        if (text == null) return "";

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (i > 0) builder.append("\n");
            int lineStart = builder.length();

            // Заголовки: 1-6 символов # и обязательно пробел после них
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
            if (hashes >= 1 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ') {
                parseInlineMarkdown(line.substring(hashes + 1).trim(), builder, false);
                int end = builder.length();
                if (end > lineStart) {
                    builder.setSpan(new StyleSpan(Typeface.BOLD), lineStart, end, FLAG);
                    builder.setSpan(new RelativeSizeSpan(headingScale(hashes)), lineStart, end, FLAG);
                }
                continue;
            }

            // Цитата
            String trimmed = line.trim();
            if (trimmed.startsWith(">")) {
                String q = trimmed.substring(1);
                if (q.startsWith(" ")) q = q.substring(1);
                parseInlineMarkdown(q, builder, false);
                if (builder.length() > lineStart) {
                    builder.setSpan(new QuoteSpan(0xFF888888), lineStart, builder.length(), FLAG);
                }
                continue;
            }

            // Списки (*, -, +), в том числе вложенные с отступом
            int indent = 0;
            while (indent < line.length()
                    && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            String rest = line.substring(indent);
            if (rest.startsWith("* ") || rest.startsWith("- ") || rest.startsWith("+ ")) {
                builder.append(line.substring(0, indent));
                builder.append("\u2022 ");
                parseInlineMarkdown(rest.substring(2), builder, false);
                continue;
            }

            // Обычный текст
            parseInlineMarkdown(line, builder, false);
        }
        return builder;
    }

    private static float headingScale(int level) {
        if (level == 1) return 1.4f;
        if (level == 2) return 1.3f;
        if (level == 3) return 1.2f;
        return 1.1f;
    }

    /**
     * Разбирает строку и дописывает результат в out. inLink = true внутри текста ссылки
     * (вложенные ссылки не создаются).
     * Маркер открывает форматирование, только если сразу после него не пробел,
     * а закрывает, только если сразу перед ним не пробел. Поэтому "2 * 3 * 4" остаётся текстом,
     * а подчёркивания внутри слов (snake_case) не превращаются в курсив.
     */
    private void parseInlineMarkdown(String s, SpannableStringBuilder out, boolean inLink) {
        int len = s.length();
        int i = 0;
        int plainStart = 0;

        while (i < len) {
            char c = s.charAt(i);

            // Экранирование: слэш убираем, символ оставляем как текст
            if (c == '\\' && i + 1 < len && "\\*_`~#[]()!".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.substring(plainStart, i));
                plainStart = i + 1;
                i += 2;
                continue;
            }

            // `код`: чёрный фон, светлый моноширинный текст
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    out.append(s.substring(plainStart, i));
                    int st = out.length();
                    out.append(s.substring(i + 1, end));
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(), FLAG);
                    out.setSpan(new BackgroundColorSpan(CODE_BG), st, out.length(), FLAG);
                    out.setSpan(new ForegroundColorSpan(CODE_FG), st, out.length(), FLAG);
                    i = end + 1;
                    plainStart = i;
                    continue;
                }
                i++;
                continue;
            }

            // ![описание](url) вне отдельного блока (например в таблице): показываем описание
            if (c == '!' && i + 1 < len && s.charAt(i + 1) == '[') {
                int[] lk = matchLink(s, i + 1);
                if (lk != null) {
                    out.append(s.substring(plainStart, i));
                    out.append(s.substring(lk[0] + 1, lk[1]));
                    i = lk[3] + 1;
                    plainStart = i;
                    continue;
                }
                i++;
                continue;
            }

            // [текст](url)
            if (c == '[' && !inLink) {
                int[] lk = matchLink(s, i);
                if (lk != null) {
                    String url = cleanUrl(s.substring(lk[2], lk[3]));
                    if (isLinkable(url)) {
                        out.append(s.substring(plainStart, i));
                        int st = out.length();
                        parseInlineMarkdown(s.substring(i + 1, lk[1]), out, true);
                        if (out.length() > st) {
                            out.setSpan(new URLSpan(url), st, out.length(), FLAG);
                        }
                        i = lk[3] + 1;
                        plainStart = i;
                        continue;
                    }
                }
                i++;
                continue;
            }

            // Голый адрес http:// или https://
            if (c == 'h' && !inLink
                    && (s.startsWith("http://", i) || s.startsWith("https://", i))
                    && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))) {
                int end = i;
                while (end < len) {
                    char ch = s.charAt(end);
                    if (Character.isWhitespace(ch) || ch == '<' || ch == '>' || ch == '"') break;
                    end++;
                }
                // Убираем завершающую пунктуацию и лишнюю закрывающую скобку
                while (end > i) {
                    char last = s.charAt(end - 1);
                    if (".,;:!?'*_~]".indexOf(last) >= 0) {
                        end--;
                    } else if (last == ')' && count(s, i, end, '(') < count(s, i, end, ')')) {
                        end--;
                    } else {
                        break;
                    }
                }
                if (end > i + 8) {
                    out.append(s.substring(plainStart, i));
                    int st = out.length();
                    out.append(s.substring(i, end));
                    out.setSpan(new URLSpan(s.substring(i, end)), st, out.length(), FLAG);
                    i = end;
                    plainStart = i;
                    continue;
                }
                i++;
                continue;
            }

            // ***жирный курсив***
            if (c == '*' && s.startsWith("***", i) && i + 3 < len
                    && !Character.isWhitespace(s.charAt(i + 3))) {
                int end = findClosing(s, "***", i + 3);
                if (end > 0) {
                    out.append(s.substring(plainStart, i));
                    int st = out.length();
                    parseInlineMarkdown(s.substring(i + 3, end), out, inLink);
                    out.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), st, out.length(), FLAG);
                    i = end + 3;
                    plainStart = i;
                    continue;
                }
            }

            // **жирный**
            if (c == '*' && i + 1 < len && s.charAt(i + 1) == '*') {
                if (i + 2 < len && !Character.isWhitespace(s.charAt(i + 2))) {
                    int end = findClosing(s, "**", i + 2);
                    if (end > 0) {
                        out.append(s.substring(plainStart, i));
                        int st = out.length();
                        parseInlineMarkdown(s.substring(i + 2, end), out, inLink);
                        out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), FLAG);
                        i = end + 2;
                        plainStart = i;
                        continue;
                    }
                }
                // Не открылось как жирный: обе звёздочки остаются текстом
                i += 2;
                continue;
            }

            // *курсив* (не открываем внутри слова, чтобы 2*3*4 не ломалось)
            if (c == '*') {
                boolean opensWord = i + 1 < len && !Character.isWhitespace(s.charAt(i + 1));
                boolean afterWord = i > 0 && Character.isLetterOrDigit(s.charAt(i - 1));
                if (opensWord && !afterWord) {
                    int end = findClosing(s, "*", i + 1);
                    if (end > 0) {
                        out.append(s.substring(plainStart, i));
                        int st = out.length();
                        parseInlineMarkdown(s.substring(i + 1, end), out, inLink);
                        out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(), FLAG);
                        i = end + 1;
                        plainStart = i;
                        continue;
                    }
                }
                i++;
                continue;
            }

            // __жирный__ и _курсив_ (только на границах слов)
            if (c == '_') {
                boolean afterWord = i > 0 && Character.isLetterOrDigit(s.charAt(i - 1));
                if (i + 1 < len && s.charAt(i + 1) == '_') {
                    if (!afterWord && i + 2 < len && !Character.isWhitespace(s.charAt(i + 2))) {
                        int end = findClosingUnderscore(s, "__", i + 2);
                        if (end > 0) {
                            out.append(s.substring(plainStart, i));
                            int st = out.length();
                            parseInlineMarkdown(s.substring(i + 2, end), out, inLink);
                            out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), FLAG);
                            i = end + 2;
                            plainStart = i;
                            continue;
                        }
                    }
                    i += 2;
                    continue;
                }
                if (!afterWord && i + 1 < len && !Character.isWhitespace(s.charAt(i + 1))) {
                    int end = findClosingUnderscore(s, "_", i + 1);
                    if (end > 0) {
                        out.append(s.substring(plainStart, i));
                        int st = out.length();
                        parseInlineMarkdown(s.substring(i + 1, end), out, inLink);
                        out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(), FLAG);
                        i = end + 1;
                        plainStart = i;
                        continue;
                    }
                }
                i++;
                continue;
            }

            // ~~зачёркнутый~~
            if (c == '~' && i + 1 < len && s.charAt(i + 1) == '~') {
                if (i + 2 < len && !Character.isWhitespace(s.charAt(i + 2))) {
                    int end = findClosing(s, "~~", i + 2);
                    if (end > 0) {
                        out.append(s.substring(plainStart, i));
                        int st = out.length();
                        parseInlineMarkdown(s.substring(i + 2, end), out, inLink);
                        out.setSpan(new StrikethroughSpan(), st, out.length(), FLAG);
                        i = end + 2;
                        plainStart = i;
                        continue;
                    }
                }
                i += 2;
                continue;
            }

            i++;
        }

        out.append(s.substring(plainStart));
    }

    /**
     * Ищет закрывающий маркер: содержимое не пустое, перед маркером не пробел.
     * Для одиночной * пропускает пары ** (они принадлежат жирному).
     * Возвращает индекс маркера или -1.
     */
    private static int findClosing(String s, String delim, int from) {
        int len = s.length();
        boolean single = delim.length() == 1 && delim.charAt(0) == '*';
        int idx = from;
        while (idx < len) {
            idx = s.indexOf(delim, idx);
            if (idx < 0) return -1;
            if (single && idx + 1 < len && s.charAt(idx + 1) == '*') {
                idx += 2;
                continue;
            }
            if (idx > from && !Character.isWhitespace(s.charAt(idx - 1))) {
                return idx;
            }
            idx += delim.length();
        }
        return -1;
    }

    // То же для _ и __: закрывающий маркер не должен стоять внутри слова
    private static int findClosingUnderscore(String s, String delim, int from) {
        int len = s.length();
        boolean single = delim.length() == 1;
        int idx = from;
        while (idx < len) {
            idx = s.indexOf(delim, idx);
            if (idx < 0) return -1;
            if (single && idx + 1 < len && s.charAt(idx + 1) == '_') {
                idx += 2;
                continue;
            }
            int after = idx + delim.length();
            boolean okAfter = after >= len || !Character.isLetterOrDigit(s.charAt(after));
            if (idx > from && !Character.isWhitespace(s.charAt(idx - 1)) && okAfter) {
                return idx;
            }
            idx += delim.length();
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------
    // Ссылки и картинки: вспомогательные функции
    // ---------------------------------------------------------------------------------

    // [текст](url) с открывающей [ в позиции open. Возвращает {open, ], начало url, ) } или null
    private static int[] matchLink(String s, int open) {
        int close = s.indexOf(']', open + 1);
        if (close < 0 || close + 1 >= s.length() || s.charAt(close + 1) != '(') return null;
        int end = findParenEnd(s, close + 2);
        if (end < 0) return null;
        return new int[]{open, close, close + 2, end};
    }

    // Закрывающая скобка с учётом вложенных пар: https://ru.wikipedia.org/wiki/Java_(язык)
    private static int findParenEnd(String s, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    // Ищет ![описание](url) и возвращает {начало, конец, описание от, описание до, url от, url до} или null
    private static int[] findImage(String s, int from) {
        int pos = from;
        while (true) {
            int st = s.indexOf("![", pos);
            if (st < 0) return null;
            if (insideInlineCode(s, st)) {
                pos = st + 2;
                continue;
            }
            int altEnd = s.indexOf(']', st + 2);
            if (altEnd < 0 || altEnd + 1 >= s.length() || s.charAt(altEnd + 1) != '(') {
                pos = st + 2;
                continue;
            }
            int close = findParenEnd(s, altEnd + 2);
            if (close < 0) {
                pos = st + 2;
                continue;
            }
            return new int[]{st, close + 1, st + 2, altEnd, altEnd + 2, close};
        }
    }

    // Нечётное число ` до позиции - значит мы внутри `кода`
    private static boolean insideInlineCode(String s, int pos) {
        int n = 0;
        for (int i = 0; i < pos; i++) {
            if (s.charAt(i) == '`') n++;
        }
        return (n % 2) == 1;
    }

    // Убирает <...> вокруг адреса и необязательный заголовок: url "title"
    private static String cleanUrl(String raw) {
        String u = raw.trim();
        if (u.startsWith("<")) {
            int e = u.indexOf('>');
            if (e > 0) return u.substring(1, e).trim();
        }
        for (int i = 0; i < u.length(); i++) {
            if (Character.isWhitespace(u.charAt(i))) return u.substring(0, i);
        }
        return u;
    }

    private static boolean isHttp(String url) {
        String l = url.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    private static boolean isLinkable(String url) {
        String l = url.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://")
                || l.startsWith("mailto:") || l.startsWith("tel:");
    }

    private static int count(String s, int from, int to, char ch) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == ch) n++;
        }
        return n;
    }

    private void openUrl(String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------------------------
    // Загрузка картинок
    // ---------------------------------------------------------------------------------

    private void ensureImage(final String url) {
        if (imageCache.containsKey(url) || imageFailed.contains(url) || imageLoading.contains(url)) return;
        imageLoading.add(url);
        new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    bmp = downloadBitmap(url);
                } catch (Throwable ignored) {}
                final Bitmap result = bmp;
                PendingState.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        imageLoading.remove(url);
                        if (result != null) {
                            imageCache.put(url, result);
                            while (imageCache.size() > CACHE_MAX) {
                                Iterator<String> it = imageCache.keySet().iterator();
                                it.next();
                                it.remove();
                            }
                        } else {
                            imageFailed.add(url);
                        }
                        notifyDataSetChanged();
                    }
                });
            }
        }).start();
    }

    private static Bitmap downloadBitmap(String startUrl) throws Exception {
        String url = startUrl;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                if (c instanceof HttpsURLConnection) {
                    try {
                        SSLContext sc = SSLContext.getInstance("TLSv1.2");
                        sc.init(null, null, null);
                        ((HttpsURLConnection) c).setSSLSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()));
                    } catch (Exception ignored) {}
                }
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) OldGemAI");

                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) return null;
                    url = new URL(new URL(url), loc).toString();
                    if (!isHttp(url)) return null;
                    continue;
                }
                if (code != 200) return null;

                InputStream is = c.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_DOWNLOAD) return null;
                    baos.write(buf, 0, n);
                }
                is.close();
                byte[] data = baos.toByteArray();

                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return null;
                o.inJustDecodeBounds = false;
                o.inSampleSize = MainActivity.calculateInSampleSize(o, MAX_IMG_PX, MAX_IMG_PX);
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } finally {
                c.disconnect();
            }
        }
        return null;
    }

    // Долгое нажатие на сообщении пользователя: меню «Изменить» / «Копировать» вместо немедленного копирования
    private void attachUserMessageMenu(View view, final String text, final int position) {
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                final boolean ru = Prefs.LANG_RU.equals(new Prefs(context).getLanguage());
                final boolean canEdit = editListener != null;
                String[] items = canEdit
                        ? new String[]{ru ? "Изменить" : "Edit", ru ? "Копировать" : "Copy"}
                        : new String[]{ru ? "Копировать" : "Copy"};
                try {
                    new android.app.AlertDialog.Builder(context)
                            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(android.content.DialogInterface dialog, int which) {
                                    int action = canEdit ? which : 1;
                                    if (action == 0) {
                                        EditListener l = editListener;
                                        if (l != null) l.onEdit(position);
                                    } else {
                                        copyText(text);
                                    }
                                }
                            })
                            .show();
                } catch (Throwable t) {
                    AppLog.e("UI", "User message menu failed", t);
                    return false;
                }
                return true;
            }
        });
    }

    private void copyText(String text) {
        if (text == null || text.length() == 0) return;
        boolean ru = Prefs.LANG_RU.equals(new Prefs(context).getLanguage());
        if (AppLog.copyToClipboard(context, text)) {
            android.widget.Toast.makeText(context, ru ? "Скопировано" : "Copied",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // Цвет иконок под ответом: окно приложения чёрное (Theme.Black), поэтому иконки белые
    private static final int ICON_COLOR = 0xFFFFFFFF;

    // Ряд кнопок под ответом модели: перегенерация (только под последним ответом),
    // копирование и изменение текста ответа. text == null - у сообщения нет текста
    // для копирования (сгенерированная картинка): остаётся только перегенерация.
    private void addActionButtons(LinearLayout container, final int position, final String text) {
        final boolean ru = Prefs.LANG_RU.equals(new Prefs(context).getLanguage());
        boolean isLast = position == messages.size() - 1;

        boolean showRegen = regenerateListener != null && isLast && regenerateListener.canRegenerate();
        boolean hasText = text != null && text.length() > 0;
        boolean busy = responseActionListener != null && responseActionListener.isBusy();
        // Пока последний ответ ещё печатается, копировать и менять его нельзя
        boolean showCopy = hasText && !(busy && isLast);
        boolean showEdit = showCopy && responseActionListener != null;
        if (!showRegen && !showCopy) return;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        if (showRegen) {
            row.addView(makeActionButton(new RegenerateIcon(dp(18), ICON_COLOR),
                    ru ? "Перегенерировать ответ" : "Regenerate response",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            RegenerateListener l = regenerateListener;
                            if (l != null) l.onRegenerate();
                        }
                    }));
        }
        if (showCopy) {
            row.addView(makeActionButton(new CopyIcon(dp(18), ICON_COLOR),
                    ru ? "Копировать ответ" : "Copy response",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            copyText(text);
                        }
                    }));
        }
        if (showEdit) {
            row.addView(makeActionButton(new PencilIcon(dp(17), ICON_COLOR),
                    ru ? "Изменить ответ" : "Edit response",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ResponseActionListener l = responseActionListener;
                            if (l != null) l.onEditResponse(position);
                        }
                    }));
        }

        container.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // Одна кнопка-иконка с подсветкой при нажатии (светлая, т.к. фон чёрный)
    private android.widget.ImageButton makeActionButton(android.graphics.drawable.Drawable icon,
                                                         String description,
                                                         View.OnClickListener click) {
        android.widget.ImageButton btn = new android.widget.ImageButton(context);
        btn.setImageDrawable(icon);

        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_pressed},
                new android.graphics.drawable.ColorDrawable(0x33FFFFFF));
        bg.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0x00000000));
        btn.setBackgroundDrawable(bg);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setOnClickListener(click);
        try {
            btn.setContentDescription(description);
        } catch (Throwable ignored) {}
        return btn;
    }

    // Иконка ic_rotate_clockwise (SVG 21x18), нарисована кодом: VectorDrawable на старых Android недоступен.
    // Координаты путей взяты из SVG, вертикальное отражение (scale(1, -1)) применяется при рисовании.
    private static class RegenerateIcon extends android.graphics.drawable.Drawable {
        private static final float W = 20f;
        private static final float H = 18f;

        private final android.graphics.Paint arcPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint headPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path arc = new android.graphics.Path();
        private final android.graphics.Path head = new android.graphics.Path();
        private final int height;

        RegenerateIcon(int height, int color) {
            this.height = height;

            arcPaint.setStyle(android.graphics.Paint.Style.STROKE);
            arcPaint.setStrokeWidth(2f);
            arcPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            arcPaint.setColor(color);

            headPaint.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE);
            headPaint.setStrokeWidth(1f);
            headPaint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            headPaint.setColor(color);

            arc.moveTo(13.7198369f, 2.40683307f);
            arc.cubicTo(12.2674265f, 0.921737027f, 10.2413548f, 0f, 8f, 0f);
            arc.cubicTo(3.581722f, 0f, 0f, 3.581722f, 0f, 8f);
            arc.cubicTo(0f, 12.418278f, 3.581722f, 16f, 8f, 16f);
            arc.cubicTo(12.418278f, 16f, 16f, 12.418278f, 16f, 8f);

            head.moveTo(13f, 9f);
            head.lineTo(19f, 9f);
            head.lineTo(16f, 5f);
            head.close();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width() / W, b.height() / H);
            canvas.save();
            canvas.translate(b.left + (b.width() - W * s) / 2f, b.top + (b.height() - H * s) / 2f);
            canvas.scale(s, s);
            // +1 на толщину обводки, далее отражение по вертикали (y' = 16 - y)
            canvas.translate(1f, 17f);
            canvas.scale(1f, -1f);
            canvas.drawPath(arc, arcPaint);
            canvas.drawPath(head, headPaint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() { return Math.round(height * W / H); }

        @Override
        public int getIntrinsicHeight() { return height; }

        @Override
        public void setAlpha(int alpha) {
            arcPaint.setAlpha(alpha);
            headPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            arcPaint.setColorFilter(cf);
            headPaint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // Иконка «копировать» (SVG 22x27: две обведённые страницы), нарисована кодом.
    // Координаты путей и толщина линии (2) взяты из SVG.
    private static class CopyIcon extends android.graphics.drawable.Drawable {
        private static final float W = 22f;
        private static final float H = 27f;

        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path back = new android.graphics.Path();
        private final android.graphics.Path front = new android.graphics.Path();
        private final int height;

        CopyIcon(int height, int color) {
            this.height = height;

            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setStrokeJoin(android.graphics.Paint.Join.MITER);
            paint.setStrokeMiter(10f);
            paint.setColor(color);

            // Задняя страница
            back.moveTo(17.5f, 21.1664f);
            back.lineTo(17.5f, 22.9815f);
            back.cubicTo(17.5f, 24.1916f, 16.5f, 25.2f, 15.3f, 25.2f);
            back.lineTo(3.9f, 25.2f);
            back.cubicTo(2.7f, 25.2f, 1.7f, 24.1916f, 1.7f, 22.9815f);
            back.lineTo(1.7f, 7.14959f);
            back.cubicTo(1.7f, 5.93951f, 2.7f, 4.93109f, 3.9f, 4.93109f);

            // Передняя страница
            front.moveTo(18.2f, 21.4689f);
            front.lineTo(6.8f, 21.4689f);
            front.cubicTo(5.6f, 21.4689f, 4.6f, 20.4605f, 4.6f, 19.2504f);
            front.lineTo(4.6f, 3.41848f);
            front.cubicTo(4.6f, 2.2084f, 5.6f, 1.20001f, 6.8f, 1.20001f);
            front.lineTo(18.2f, 1.20001f);
            front.cubicTo(19.4f, 1.20001f, 20.4f, 2.2084f, 20.4f, 3.41848f);
            front.lineTo(20.4f, 19.1496f);
            front.cubicTo(20.4f, 20.4605f, 19.4f, 21.4689f, 18.2f, 21.4689f);
            front.close();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width() / W, b.height() / H);
            canvas.save();
            canvas.translate(b.left + (b.width() - W * s) / 2f, b.top + (b.height() - H * s) / 2f);
            canvas.scale(s, s);
            canvas.drawPath(back, paint);
            canvas.drawPath(front, paint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() { return Math.round(height * W / H); }

        @Override
        public int getIntrinsicHeight() { return height; }

        @Override
        public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // Иконка «карандаш» (SVG 297.068x297.068, заливка), нарисована кодом.
    // Относительные команды SVG (l, c, s) пересчитаны в абсолютные координаты.
    private static class PencilIcon extends android.graphics.drawable.Drawable {
        private static final float VIEW = 297.068f;

        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final int size;

        PencilIcon(int size, int color) {
            this.size = size;

            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(color);

            // Внешний контур
            path.moveTo(288.758f, 46.999f);
            path.lineTo(250.068f, 8.309f);
            path.cubicTo(244.721f, 2.955f, 237.613f, 0.006f, 230.048f, 0.006f);
            path.cubicTo(222.483f, 0.006f, 215.376f, 2.949f, 210.028f, 8.303f);
            path.lineTo(28.632f, 190.266f);
            path.lineTo(0f, 297.061f);
            path.lineTo(107.547f, 268.256f);
            path.lineTo(288.745f, 87.045f);
            path.cubicTo(294.105f, 81.691f, 297.068f, 74.583f, 297.068f, 67.019f);
            path.cubicTo(297.068f, 59.455f, 294.105f, 52.347f, 288.758f, 46.999f);
            path.close();

            // Корпус карандаша
            path.moveTo(43.478f, 193.583f);
            path.lineTo(180.71f, 55.823f);
            path.lineTo(241.264f, 116.364f);
            path.lineTo(103.761f, 253.866f);
            path.lineTo(43.478f, 193.583f);
            path.close();

            // Кончик
            path.moveTo(37.719f, 206.006f);
            path.lineTo(91.087f, 259.368f);
            path.lineTo(48.683f, 270.718f);
            path.lineTo(26.35f, 248.384f);
            path.lineTo(37.719f, 206.006f);
            path.close();

            // Ластик
            path.moveTo(279.657f, 77.951f);
            path.lineTo(260.164f, 97.456f);
            path.lineTo(199.585f, 36.915f);
            path.lineTo(219.129f, 17.39f);
            path.cubicTo(224.952f, 11.542f, 235.145f, 11.548f, 240.98f, 17.39f);
            path.lineTo(279.67f, 56.086f);
            path.cubicTo(282.594f, 59.004f, 284.214f, 62.886f, 284.214f, 67.012f);
            path.cubicTo(284.214f, 71.139f, 282.594f, 75.027f, 279.657f, 77.951f);
            path.close();
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) / VIEW;
            canvas.save();
            canvas.translate(b.left + (b.width() - VIEW * s) / 2f, b.top + (b.height() - VIEW * s) / 2f);
            canvas.scale(s, s);
            canvas.drawPath(path, paint);
            canvas.restore();
        }

        @Override
        public int getIntrinsicWidth() { return size; }

        @Override
        public int getIntrinsicHeight() { return size; }

        @Override
        public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }

        @Override
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private int dp(int val) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}
