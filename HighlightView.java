package dev.tvlauncher.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * The single translucent "glass" highlight. It springs from tile to tile
 * (a short overshoot animation) instead of every tile having its own border.
 */
final class HighlightView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cur = new RectF();
    private final float[] from = new float[4];
    private final float[] to = new float[4];
    private final float radius;
    private boolean placed;
    private ValueAnimator anim;

    HighlightView(Context c, float radius, float strokeWidth) {
        super(c);
        this.radius = radius;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x2EFFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeWidth);
        stroke.setColor(0xB8FFFFFF);
    }

    void moveTo(float l, float t, float r, float b, boolean animate) {
        if (anim != null) anim.cancel();
        if (!placed || !animate) {
            cur.set(l, t, r, b);
            placed = true;
            invalidate();
            return;
        }
        from[0] = cur.left;
        from[1] = cur.top;
        from[2] = cur.right;
        from[3] = cur.bottom;
        to[0] = l;
        to[1] = t;
        to[2] = r;
        to[3] = b;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(360);
        anim.setInterpolator(new OvershootInterpolator(1.0f));
        anim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            cur.set(
                    from[0] + (to[0] - from[0]) * f,
                    from[1] + (to[1] - from[1]) * f,
                    from[2] + (to[2] - from[2]) * f,
                    from[3] + (to[3] - from[3]) * f);
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!placed) return;
        canvas.drawRoundRect(cur, radius, radius, fill);
        canvas.drawRoundRect(cur, radius, radius, stroke);
    }
}
