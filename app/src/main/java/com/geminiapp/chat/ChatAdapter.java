package com.geminiapp.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class ChatAdapter extends BaseAdapter {

    private static final int FLAG = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;
    private static final int CODE_BG = 0x22000000;

    private final Context context;
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
                attachCopyOnLongPress(caption, msg.text);
                container.addView(caption);
            }
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

        TextView tv = new TextView(context);

        if (!isUser && msg.text != null) {
            tv.setText(parseMarkdown(msg.text));
        } else {
            tv.setText(msg.text);
        }

        tv.setTextSize(15);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));

        if (isUser) {
            tv.setBackgroundResource(R.drawable.bubble_user);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            tv.setBackgroundResource(R.drawable.bubble_model);
            tv.setTextColor(0xFF222222);
        }

        attachCopyOnLongPress(tv, msg.text);
        bubbleRow.addView(tv);
        container.addView(bubbleRow);
        return container;
    }

    /**
     * Построчный разбор Markdown: заголовки (# ... ######, обязательно с пробелом после #),
     * списки (*, -, + с учётом отступа), блоки кода ```...```, а внутри строк -
     * `код`, **жирный**, *курсив*, ***жирный курсив***, ~~зачёркнутый~~ и экранирование через \.
     */
    private CharSequence parseMarkdown(String text) {
        if (text == null) return "";

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        boolean inCode = false;
        boolean wroteLine = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // Строка-ограждение блока кода: сама не выводится, только переключает режим
            if (line.trim().startsWith("```")) {
                inCode = !inCode;
                continue;
            }

            if (wroteLine) builder.append("\n");
            wroteLine = true;
            int lineStart = builder.length();

            // Внутри блока кода ничего не разбираем
            if (inCode) {
                builder.append(line);
                if (builder.length() > lineStart) {
                    builder.setSpan(new TypefaceSpan("monospace"), lineStart, builder.length(), FLAG);
                    builder.setSpan(new BackgroundColorSpan(CODE_BG), lineStart, builder.length(), FLAG);
                }
                continue;
            }

            // Заголовки: 1-6 символов # и обязательно пробел после них
            int hashes = 0;
            while (hashes < line.length() && line.charAt(hashes) == '#') hashes++;
            if (hashes >= 1 && hashes <= 6 && hashes < line.length() && line.charAt(hashes) == ' ') {
                parseInlineMarkdown(line.substring(hashes + 1).trim(), builder);
                int end = builder.length();
                if (end > lineStart) {
                    builder.setSpan(new StyleSpan(Typeface.BOLD), lineStart, end, FLAG);
                    builder.setSpan(new RelativeSizeSpan(headingScale(hashes)), lineStart, end, FLAG);
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
                parseInlineMarkdown(rest.substring(2), builder);
                continue;
            }

            // Обычный текст
            parseInlineMarkdown(line, builder);
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
     * Разбирает строку и дописывает результат в out.
     * Маркер открывает форматирование, только если сразу после него не пробел,
     * а закрывает, только если сразу перед ним не пробел. Поэтому "2 * 3 * 4" остаётся текстом.
     */
    private void parseInlineMarkdown(String s, SpannableStringBuilder out) {
        int len = s.length();
        int i = 0;
        int plainStart = 0;

        while (i < len) {
            char c = s.charAt(i);

            // Экранирование: \* \_ \` \~ \# \\ - слэш убираем, символ оставляем как текст
            if (c == '\\' && i + 1 < len && "\\*_`~#".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.substring(plainStart, i));
                plainStart = i + 1;
                i += 2;
                continue;
            }

            // `код`
            if (c == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    out.append(s.substring(plainStart, i));
                    int st = out.length();
                    out.append(s.substring(i + 1, end));
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(), FLAG);
                    out.setSpan(new BackgroundColorSpan(CODE_BG), st, out.length(), FLAG);
                    i = end + 1;
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
                    parseInlineMarkdown(s.substring(i + 3, end), out);
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
                        parseInlineMarkdown(s.substring(i + 2, end), out);
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
                        parseInlineMarkdown(s.substring(i + 1, end), out);
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
                        parseInlineMarkdown(s.substring(i + 2, end), out);
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

    @SuppressWarnings("deprecation")
    private void attachCopyOnLongPress(View view, final String text) {
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (text == null || text.length() == 0) return false;
                android.text.ClipboardManager cm = (android.text.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return false;
                cm.setText(text);
                boolean ru = Prefs.LANG_RU.equals(new Prefs(context).getLanguage());
                android.widget.Toast.makeText(context, ru ? "Скопировано" : "Copied",
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private int dp(int val) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}
