package com.geminiapp.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

public class ChatAdapter extends BaseAdapter {

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
     * Парсер Markdown, который применяет стили (жирный, курсив) и заменяет теги.
     * Обрабатывает заголовки, списки, **жирный**, *курсив*, ~~зачеркнутый~~.
     */
    private CharSequence parseMarkdown(String text) {
        if (text == null) return "";

        SpannableStringBuilder builder = new SpannableStringBuilder();
        String[] lines = text.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineStart = builder.length();

            // 1. Заголовки (#, ##, ###)
            if (line.startsWith("###")) {
                builder.append(line.substring(3).trim());
                builder.setSpan(new StyleSpan(Typeface.BOLD), lineStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new RelativeSizeSpan(1.2f), lineStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("##")) {
                builder.append(line.substring(2).trim());
                builder.setSpan(new StyleSpan(Typeface.BOLD), lineStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new RelativeSizeSpan(1.3f), lineStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("#")) {
                builder.append(line.substring(1).trim());
                builder.setSpan(new StyleSpan(Typeface.BOLD), lineStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                builder.setSpan(new RelativeSizeSpan(1.4f), lineStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } 
            // 2. Списки (*, -, +)
            else if (line.startsWith("* ") || line.startsWith("- ") || line.startsWith("+ ")) {
                builder.append("• ");
                parseInlineMarkdown(line.substring(2), builder);
            } 
            // 3. Обычный текст
            else {
                parseInlineMarkdown(line, builder);
            }

            if (i < lines.length - 1) {
                builder.append("\n");
            }
        }
        return builder;
    }

    /**
     * Разбирает строку на наличие **жирного**, *курсива* и ~~зачеркнутого~~.
     * Работает без сторонних библиотек.
     */
    private void parseInlineMarkdown(String line, SpannableStringBuilder builder) {
        int len = line.length();
        int i = 0;
        int currentStart = 0;

        while (i < len) {
            // Ищем ** (жирный)
            if (i + 1 < len && line.charAt(i) == '*' && line.charAt(i + 1) == '*') {
                int endBold = line.indexOf("**", i + 2);
                if (endBold != -1) {
                    // Добавляем текст до звездочек
                    builder.append(line.substring(currentStart, i));
                    // Добавляем жирный текст
                    int bStart = builder.length();
                    builder.append(line.substring(i + 2, endBold));
                    builder.setSpan(new StyleSpan(Typeface.BOLD), bStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    i = endBold + 2;
                    currentStart = i;
                    continue;
                }
            }
            
            // Ищем * (курсив)
            if (line.charAt(i) == '*') {
                int endItalic = line.indexOf("*", i + 1);
                // Проверяем, что это не начало ** (жирного)
                boolean isBoldStart = (i + 1 < len && line.charAt(i + 1) == '*');
                if (!isBoldStart && endItalic != -1) {
                     // Проверяем, что закрывающая тоже одиночная
                    boolean isEndBold = (endItalic + 1 < len && line.charAt(endItalic + 1) == '*');
                    if (!isEndBold) {
                        builder.append(line.substring(currentStart, i));
                        int iStart = builder.length();
                        builder.append(line.substring(i + 1, endItalic));
                        builder.setSpan(new StyleSpan(Typeface.ITALIC), iStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        
                        i = endItalic + 1;
                        currentStart = i;
                        continue;
                    }
                }
            }

            // Ищем ~~ (зачеркнутый)
            if (i + 1 < len && line.charAt(i) == '~' && line.charAt(i + 1) == '~') {
                int endStrike = line.indexOf("~~", i + 2);
                if (endStrike != -1) {
                    builder.append(line.substring(currentStart, i));
                    int sStart = builder.length();
                    builder.append(line.substring(i + 2, endStrike));
                    builder.setSpan(new StrikethroughSpan(), sStart, builder.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    
                    i = endStrike + 2;
                    currentStart = i;
                    continue;
                }
            }

            i++;
        }
        
        // Добавляем оставшийся текст
        builder.append(line.substring(currentStart));
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
                android.widget.Toast.makeText(context, "Скопировано", android.widget.Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private int dp(int val) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}