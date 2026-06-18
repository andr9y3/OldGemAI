package com.geminiapp.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
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

        // Если это сгенерированная картинка — показываем ImageView
        if (msg.isGeneratedImage && msg.imageBase64 != null) {
            try {
                byte[] bytes = Base64.decode(msg.imageBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ImageView iv = new ImageView(context);
                    // Масштабируем чтобы влезло на экран
                    int maxW = parent.getWidth() > 0 ? (int)(parent.getWidth() * 0.8f) : dp(240);
                    float scale = maxW / (float) bmp.getWidth();
                    int scaledH = (int)(bmp.getHeight() * scale);
                    iv.setImageBitmap(Bitmap.createScaledBitmap(bmp, maxW, scaledH, true));
                    iv.setBackgroundColor(0xFF333333);
                    iv.setPadding(dp(4), dp(4), dp(4), dp(4));
                    container.addView(iv);
                }
            } catch (Exception ignored) {}

            // Подпись под картинкой если есть текст
            if (msg.text != null && !msg.text.isEmpty()) {
                TextView caption = new TextView(context);
                caption.setText(msg.text);
                caption.setTextSize(13);
                caption.setTextColor(0xFF888888);
                container.addView(caption);
            }
            return container;
        }

        // Обычный текстовый пузырь
        LinearLayout bubbleRow = new LinearLayout(context);
        bubbleRow.setOrientation(LinearLayout.HORIZONTAL);
        bubbleRow.setGravity(isUser ? Gravity.RIGHT : Gravity.LEFT);

        // Если пользователь прикрепил картинку — показываем превью
        if (!isUser && msg.imageBase64 != null && !msg.isGeneratedImage) {
            // не показываем картинку пользователя в ответе модели
        }
        if (isUser && msg.imageBase64 != null) {
            try {
                byte[] bytes = Base64.decode(msg.imageBase64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    ImageView iv = new ImageView(context);
                    iv.setImageBitmap(Bitmap.createScaledBitmap(bmp, dp(80), dp(80), true));
                    iv.setPadding(dp(2), dp(2), dp(2), dp(2));
                    iv.setBackgroundColor(0xFF444444);
                    LinearLayout.LayoutParams ivp = new LinearLayout.LayoutParams(dp(80), dp(80));
                    ivp.setMargins(0, 0, dp(6), 0);
                    container.addView(iv, ivp);
                }
            } catch (Exception ignored) {}
        }

        TextView tv = new TextView(context);
        tv.setText(msg.text);
        tv.setTextSize(15);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));

        if (isUser) {
            tv.setBackgroundResource(R.drawable.bubble_user);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            tv.setBackgroundResource(R.drawable.bubble_model);
            tv.setTextColor(0xFF222222);
        }

        bubbleRow.addView(tv);
        container.addView(bubbleRow);
        return container;
    }

    private int dp(int val) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int)(val * density);
    }
}
