package com.geminiapp.chat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * Индикатор "ИИ думает": три точки, которые по очереди пульсируют и подпрыгивают.
 * Рисуется вручную по времени и перерисовывается через postInvalidateDelayed,
 * поэтому работает на любой версии Android (ValueAnimator/ObjectAnimator есть только с API 11).
 * Фаза считается от общих часов, а не от момента создания view: при пересоздании пузыря
 * (notifyDataSetChanged) анимация не дёргается и не начинается заново.
 */
public class TypingDotsView extends View {

    private static final long PERIOD_MS = 1200;   // полный цикл одной точки
    private static final long STAGGER_MS = 160;   // сдвиг между соседними точками
    private static final int FRAME_MS = 40;       // около 25 кадров в секунду

    private final Paint paint = new Paint();
    private final float radius;
    private final float gap;
    private final float jump;

    public TypingDotsView(Context context) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        radius = 4f * density;
        gap = 6f * density;
        jump = 5f * density;
        paint.setAntiAlias(true);
        paint.setColor(0xFF555555);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int) (radius * 2 * 3 + gap * 2 + radius);
        int h = (int) (radius * 2 + jump * 2);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float baseY = getHeight() - radius - jump * 0.5f;
        float startX = radius * 1.5f;

        for (int i = 0; i < 3; i++) {
            long t = (now + PERIOD_MS * 4 - i * STAGGER_MS) % PERIOD_MS;
            float phase = t / (float) PERIOD_MS;
            // Мягкий горб 0..1..0 за первую половину цикла, вторую половину точка отдыхает
            float wave = 0f;
            if (phase < 0.5f) {
                wave = (float) (0.5 - 0.5 * Math.cos(phase * 4 * Math.PI));
            }
            float cx = startX + i * (radius * 2 + gap);
            float cy = baseY - jump * wave;
            paint.setAlpha((int) (90 + 165 * wave));
            canvas.drawCircle(cx, cy, radius * (0.75f + 0.25f * wave), paint);
        }

        // Если view убрали с экрана, postInvalidateDelayed ничего не делает и цикл останавливается
        postInvalidateDelayed(FRAME_MS);
    }
}
