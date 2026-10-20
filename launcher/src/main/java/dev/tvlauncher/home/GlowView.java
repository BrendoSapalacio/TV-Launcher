package dev.tvlauncher.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Full-screen ambient glow: one radial gradient, no blur (cheap on low-RAM boxes). */
final class GlowView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean ready;

    GlowView(Context c) {
        super(c);
        paint.setDither(true);
    }

    void set(float cx, float cy, float radius, int color) {
        int rgb = color & 0x00FFFFFF;
        paint.setShader(new RadialGradient(
                cx, cy, radius,
                new int[]{rgb | 0x88000000, rgb | 0x30000000, rgb},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        ready = true;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!ready) return;
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
