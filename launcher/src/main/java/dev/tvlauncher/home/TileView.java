package dev.tvlauncher.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** One app tile: the app banner (or icon + name on a dark card) clipped to a rounded rectangle. */
final class TileView extends View {
    Drawable banner;
    Drawable icon;
    String label = "";

    // Geometry in content coordinates (pixels), set when the tile is placed.
    float tx, ty, tw, th;
    // Scale this tile is animating towards (used to avoid restarting animations needlessly).
    float scaleTarget = 1f;

    private final float radius;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    TileView(Context c, float radius) {
        super(c);
        this.radius = radius;
        fill.setColor(0xFF1C1C1E);
        text.setColor(0xFFFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        if (banner != null) {
            int bw = banner.getIntrinsicWidth();
            int bh = banner.getIntrinsicHeight();
            if (bw <= 0 || bh <= 0) {
                bw = w;
                bh = h;
            }
            float scale = Math.max(w / (float) bw, h / (float) bh);
            int dw = Math.round(bw * scale);
            int dh = Math.round(bh * scale);
            int l = (w - dw) / 2;
            int t = (h - dh) / 2;
            banner.setBounds(l, t, l + dw, t + dh);
            banner.draw(canvas);
        } else {
            canvas.drawRect(rect, fill);
            if (icon != null) {
                int s = Math.round(h * 0.45f);
                int l = (w - s) / 2;
                int t = Math.round(h * 0.16f);
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(canvas);
            }
            text.setTextSize(h * 0.13f);
            CharSequence shown = TextUtils.ellipsize(label, text, w * 0.9f, TextUtils.TruncateAt.END);
            canvas.drawText(shown.toString(), w / 2f, h * 0.88f, text);
        }
        canvas.restore();
    }
}
