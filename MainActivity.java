package dev.tvlauncher.home;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Apple TV style home screen.
 *
 * Layout is designed in a 1920x1080 space and scaled by u = screenHeight / 1080.
 * Focus is a small state machine (zone + index) instead of Android's focus search,
 * so the D-pad is fully deterministic.
 */
public class MainActivity extends Activity {

    // ---- Design constants (1920 x 1080 space) ----
    private static final float DOCK_TILE_W = 320f;
    private static final float DOCK_GAP = 40f;
    private static final float BAND_PAD = 50f;
    private static final float BAND_RADIUS = 56f;
    private static final float DOCK_BOTTOM = 980f;

    private static final int COLS = 4;
    private static final float TILE_W = 340f;
    private static final float TILE_H = 191f;
    private static final float GAP_X = 60f;
    private static final float ROW_H = 290f;
    private static final float GRID_TOP = 1160f;
    private static final float MIN_TOP = 150f;
    private static final float MAX_TOP = 1080f - TILE_H - 140f;

    private static final float DOCK_SCALE = 1.10f;
    private static final float GRID_SCALE = 1.08f;

    private static final int ZONE_TOP = 0;
    private static final int ZONE_DOCK = 1;
    private static final int ZONE_GRID = 2;

    private static final int DEFAULT_GLOW = 0xFF4A5CFF;

    // ---- Scaling ----
    private float u;
    private int sw, sh;
    private float dw;

    // ---- Views ----
    private FrameLayout root, header, content;
    private GlowView glow;
    private HighlightView highlight;
    private View band;
    private TextView greetingTv, clockTv, dateTv, dockNameTv, gridLabelTv, wifiPill;
    private LinearLayout pillRow;
    private LinearLayout inputsPanel;
    private final List<TextView> inputRows = new ArrayList<>();

    // ---- Model ----
    private final List<TextView> pills = new ArrayList<>();
    private final List<Runnable> pillActions = new ArrayList<>();
    private final List<TileView> dockTiles = new ArrayList<>();
    private final List<TileView> gridTiles = new ArrayList<>();
    private final List<Intent> dockIntents = new ArrayList<>();
    private final List<Intent> gridIntents = new ArrayList<>();
    private final List<Integer> dockColors = new ArrayList<>();
    private final List<InputEntry> inputs = new ArrayList<>();

    private int zone = ZONE_DOCK;
    private int prevZone = -1;
    private int dockIdx, gridIdx, topIdx;
    private float scrollY;
    private boolean inputsOpen;
    private int inputIdx;
    private boolean highlightVisible;

    private float bandX, bandY, bandW, bandH;
    private int glowColor = DEFAULT_GLOW;
    private float glowCx;
    private ValueAnimator glowAnim;
    private final ArgbEvaluator argb = new ArgbEvaluator();

    private Typeface light, thin, heading;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean appsDirty;

    private static final class AppEntry {
        final ResolveInfo ri;
        final String label;

        AppEntry(ResolveInfo ri, String label) {
            this.ri = ri;
            this.label = label;
        }
    }

    private static final class InputEntry {
        final String label;
        final Intent intent;

        InputEntry(String label, Intent intent) {
            this.label = label;
            this.intent = intent;
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        sw = dm.widthPixels;
        sh = dm.heightPixels;
        u = sh / 1080f;
        dw = sw / u;

        prefs = getSharedPreferences("launcher_state", MODE_PRIVATE);
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        thin = outfit(200, "sans-serif-thin");
        heading = outfit(300, "sans-serif-light");

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.launcher_background));
        setContentView(root);

        glow = new GlowView(this);
        root.addView(glow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content = new FrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(sw, sh));

        highlight = new HighlightView(this, px(26f), px(3f));
        content.addView(highlight, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dockNameTv = makeText(64f, 0xFFFFFFFF, heading);
        content.addView(dockNameTv, wrap());
        dockNameTv.setAlpha(0f);

        gridLabelTv = makeText(28f, 0xFFFFFFFF, light);
        gridLabelTv.setGravity(Gravity.CENTER_HORIZONTAL);
        gridLabelTv.setSingleLine(true);
        gridLabelTv.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(gridLabelTv, new FrameLayout.LayoutParams(
                px(TILE_W + 80f), ViewGroup.LayoutParams.WRAP_CONTENT));
        gridLabelTv.setAlpha(0f);

        buildHeader();

        loadInputs();
        buildPills();
        restoreState();
        buildApps();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (appsDirty) buildApps();
        handler.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        saveState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(pkgReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home button pressed while we are the launcher: go back to the dock.
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closeInputs();
            if (!dockTiles.isEmpty()) {
                zone = ZONE_DOCK;
                applyFocus(true);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        // A launcher never exits; Back is handled in dispatchKeyEvent.
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) buildApps();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            if (wifiPill != null) wifiPill.setText(wifiLabel());
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60000L - (now % 60000L) + 50L);
        }
    };

    // =====================================================================
    // Building the UI
    // =====================================================================

    private void buildHeader() {
        header = new FrameLayout(this);
        root.addView(header, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        greetingTv = makeText(34f, 0xCCFFFFFF, light);
        header.addView(greetingTv, wrap());
        greetingTv.setX(px(96f));
        greetingTv.setY(px(58f));

        clockTv = makeText(150f, 0xFFFFFFFF, thin);
        clockTv.setIncludeFontPadding(false);
        header.addView(clockTv, wrap());
        clockTv.setX(px(92f));
        clockTv.setY(px(100f));

        dateTv = makeText(34f, 0xCCFFFFFF, light);
        header.addView(dateTv, wrap());
        dateTv.setX(px(96f));
        dateTv.setY(px(292f));

        pillRow = new LinearLayout(this);
        pillRow.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = px(56f);
        lp.rightMargin = px(96f);
        header.addView(pillRow, lp);
    }

    private void buildPills() {
        pills.clear();
        pillActions.clear();
        pillRow.removeAllViews();

        if (!inputs.isEmpty()) {
            pills.add(makePill(getString(R.string.pill_inputs)));
            pillActions.add(this::toggleInputs);
        }

        wifiPill = makePill(wifiLabel());
        pills.add(wifiPill);
        pillActions.add(() -> {
            if (!startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS))) {
                startSafe(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        pills.add(makePill(getString(R.string.pill_settings)));
        pillActions.add(() -> startSafe(new Intent(Settings.ACTION_SETTINGS)));
    }

    private TextView makePill(String label) {
        TextView t = makeText(28f, 0xFFFFFFFF, light);
        t.setText(label);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(38f), 0, px(38f), 0);
        t.setBackground(rounded(0x2EFFFFFF, px(38f)));
        t.setTag(Boolean.FALSE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(76f));
        lp.leftMargin = px(24f);
        pillRow.addView(t, lp);
        return t;
    }

    /** (Re)creates the dock and the app grid from the installed apps. */
    private void buildApps() {
        appsDirty = false;
        for (TileView t : dockTiles) content.removeView(t);
        for (TileView t : gridTiles) content.removeView(t);
        if (band != null) content.removeView(band);
        band = null;
        dockTiles.clear();
        gridTiles.clear();
        dockIntents.clear();
        gridIntents.clear();
        dockColors.clear();

        PackageManager pm = getPackageManager();
        Set<String> skip = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.hidden_packages)));
        skip.add(getPackageName());

        // --- Dock, in the order of config.xml ---
        List<TileView> dockNew = new ArrayList<>();
        for (String pkg : getResources().getStringArray(R.array.dock_packages)) {
            Intent li = launchIntent(pm, pkg);
            if (li == null) continue;
            ResolveInfo ri = pm.resolveActivity(li, 0);
            if (ri == null) continue;
            TileView t = newTile(pm, ri, String.valueOf(ri.loadLabel(pm)));
            dockNew.add(t);
            dockIntents.add(li);
            dockColors.add(sampleColor(t.banner != null ? t.banner : t.icon));
            skip.add(pkg);
        }

        int n = dockNew.size();
        float dtw = n <= 4 ? DOCK_TILE_W : (1400f - (n - 1) * DOCK_GAP) / n;
        float dth = dtw * 9f / 16f;
        bandH = dth + 2f * BAND_PAD;
        bandY = DOCK_BOTTOM - bandH;
        if (n > 0) {
            bandW = n * dtw + (n - 1) * DOCK_GAP + 2f * BAND_PAD;
            bandX = (dw - bandW) / 2f;
            band = new View(this);
            band.setBackground(rounded(0x26FFFFFF, px(BAND_RADIUS)));
            content.addView(band, new FrameLayout.LayoutParams(px(bandW), px(bandH)));
            band.setX(px(bandX));
            band.setY(px(bandY));
            for (int i = 0; i < n; i++) {
                TileView t = dockNew.get(i);
                place(t, bandX + BAND_PAD + i * (dtw + DOCK_GAP), bandY + BAND_PAD, dtw, dth);
                dockTiles.add(t);
            }
            dockNameTv.setX(px(bandX + 10f));
            dockNameTv.setY(px(bandY - 125f));
        }

        // --- Grid: every other launchable app, sorted by name ---
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (skip.contains(pkg)) continue;
                if (!seen.add(pkg + "/" + ri.activityInfo.name)) continue;
                apps.add(new AppEntry(ri, String.valueOf(ri.loadLabel(pm))));
            }
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        float gx0 = (dw - (COLS * TILE_W + (COLS - 1) * GAP_X)) / 2f;
        for (int i = 0; i < apps.size(); i++) {
            AppEntry e = apps.get(i);
            TileView t = newTile(pm, e.ri, e.label);
            int col = i % COLS;
            int row = i / COLS;
            place(t, gx0 + col * (TILE_W + GAP_X), GRID_TOP + row * ROW_H, TILE_W, TILE_H);
            gridTiles.add(t);
            Intent li = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(new ComponentName(e.ri.activityInfo.packageName, e.ri.activityInfo.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            gridIntents.add(li);
        }

        int rows = (apps.size() + COLS - 1) / COLS;
        int contentH = Math.max(sh, px(GRID_TOP + rows * ROW_H + 300f));
        content.setLayoutParams(new FrameLayout.LayoutParams(sw, contentH));

        highlight.bringToFront();
        dockNameTv.bringToFront();
        gridLabelTv.bringToFront();

        // Validate focus after the lists changed.
        if (dockTiles.isEmpty() && zone == ZONE_DOCK) zone = gridTiles.isEmpty() ? ZONE_TOP : ZONE_GRID;
        if (gridTiles.isEmpty() && zone == ZONE_GRID) zone = dockTiles.isEmpty() ? ZONE_TOP : ZONE_DOCK;
        dockIdx = clamp(dockIdx, 0, dockTiles.size() - 1);
        gridIdx = clamp(gridIdx, 0, gridTiles.size() - 1);
        topIdx = clamp(topIdx, 0, pills.size() - 1);

        scrollY = 0f;
        content.setTranslationY(0f);
        prevZone = -1;
        highlightVisible = false;
        applyFocus(false);
    }

    private Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return i;
    }

    private TileView newTile(PackageManager pm, ResolveInfo ri, String label) {
        TileView t = new TileView(this, px(22f));
        t.label = label;
        Drawable b = null;
        try {
            b = ri.activityInfo.loadBanner(pm);
            if (b == null) b = ri.activityInfo.applicationInfo.loadBanner(pm);
        } catch (Exception ignored) {
        }
        t.banner = b;
        try {
            t.icon = ri.loadIcon(pm);
        } catch (Exception ignored) {
        }
        return t;
    }

    /** Places a tile; x, y, w, h are in design units. */
    private void place(TileView t, float x, float y, float w, float h) {
        t.tx = px(x);
        t.ty = px(y);
        t.tw = px(w);
        t.th = px(h);
        content.addView(t, new FrameLayout.LayoutParams(Math.round(t.tw), Math.round(t.th)));
        t.setX(t.tx);
        t.setY(t.ty);
    }

    // =====================================================================
    // Focus
    // =====================================================================

    private TileView focusedTile() {
        if (zone == ZONE_DOCK && !dockTiles.isEmpty()) return dockTiles.get(dockIdx);
        if (zone == ZONE_GRID && !gridTiles.isEmpty()) return gridTiles.get(gridIdx);
        return null;
    }

    private void applyFocus(boolean animate) {
        // 1. Tile scale
        for (int i = 0; i < dockTiles.size(); i++) {
            setTileScale(dockTiles.get(i), zone == ZONE_DOCK && i == dockIdx ? DOCK_SCALE : 1f, animate);
        }
        for (int i = 0; i < gridTiles.size(); i++) {
            setTileScale(gridTiles.get(i), zone == ZONE_GRID && i == gridIdx ? GRID_SCALE : 1f, animate);
        }

        // 2. Pill focus
        for (int i = 0; i < pills.size(); i++) {
            setPillFocused(pills.get(i), zone == ZONE_TOP && i == topIdx, animate);
        }

        // 3. Scrolling (design units)
        float target = 0f;
        if (zone == ZONE_GRID && !gridTiles.isEmpty()) {
            float rowTop = GRID_TOP + (gridIdx / COLS) * ROW_H;
            if (prevZone != ZONE_GRID) {
                target = rowTop - MIN_TOP;
            } else {
                target = scrollY;
                if (rowTop - target < MIN_TOP) target = rowTop - MIN_TOP;
                if (rowTop - target > MAX_TOP) target = rowTop - MAX_TOP;
            }
        }
        if (target < 0f) target = 0f;
        if (Math.abs(target - scrollY) > 0.5f) {
            scrollY = target;
            content.animate().cancel();
            if (animate) {
                content.animate().translationY(-scrollY * u).setDuration(340)
                        .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            } else {
                content.setTranslationY(-scrollY * u);
            }
        }

        // 4. Glass highlight
        TileView ft = focusedTile();
        if (ft != null) {
            float s = zone == ZONE_DOCK ? DOCK_SCALE : GRID_SCALE;
            float pad = px(7f);
            float cx = ft.tx + ft.tw / 2f;
            float cy = ft.ty + ft.th / 2f;
            float hw = ft.tw * s / 2f + pad;
            float hh = ft.th * s / 2f + pad;
            highlight.moveTo(cx - hw, cy - hh, cx + hw, cy + hh, animate && highlightVisible);
            fade(highlight, 1f, animate);
            highlightVisible = true;
        } else {
            fade(highlight, 0f, animate);
            highlightVisible = false;
        }

        // 5. Dock name, glow, header
        boolean showDock = zone != ZONE_GRID && !dockTiles.isEmpty();
        if (!dockTiles.isEmpty()) {
            String name = dockTiles.get(dockIdx).label;
            if (!name.contentEquals(dockNameTv.getText())) {
                dockNameTv.setText(name);
                if (animate) dockNameTv.setAlpha(0f);
            }
            fade(dockNameTv, showDock ? 1f : 0f, animate);
            TileView dt = dockTiles.get(dockIdx);
            moveGlow(dt.tx + dt.tw / 2f, dockColors.get(dockIdx), animate);
        } else {
            fade(dockNameTv, 0f, animate);
        }
        fade(glow, showDock ? 1f : 0f, animate);
        fade(header, zone == ZONE_GRID ? 0f : 1f, animate);

        // 6. Label under the focused grid tile
        if (zone == ZONE_GRID && !gridTiles.isEmpty()) {
            TileView g = gridTiles.get(gridIdx);
            gridLabelTv.setText(g.label);
            gridLabelTv.setX(g.tx + g.tw / 2f - px(TILE_W + 80f) / 2f);
            gridLabelTv.setY(g.ty + g.th / 2f + g.th * GRID_SCALE / 2f + px(14f));
            fade(gridLabelTv, 1f, animate);
        } else {
            fade(gridLabelTv, 0f, animate);
        }

        prevZone = zone;
    }

    private void setTileScale(TileView t, float s, boolean animate) {
        if (Math.abs(t.scaleTarget - s) < 0.001f) return;
        t.scaleTarget = s;
        t.animate().cancel();
        if (animate) {
            t.animate().scaleX(s).scaleY(s).setDuration(280)
                    .setInterpolator(new OvershootInterpolator(0.8f)).start();
        } else {
            t.setScaleX(s);
            t.setScaleY(s);
        }
    }

    private void setPillFocused(TextView p, boolean focused, boolean animate) {
        if (Boolean.valueOf(focused).equals(p.getTag())) return;
        p.setTag(focused);
        p.setBackground(rounded(focused ? 0xF2FFFFFF : 0x2EFFFFFF, px(38f)));
        p.setTextColor(focused ? 0xFF111111 : 0xFFFFFFFF);
        float s = focused ? 1.07f : 1f;
        p.animate().cancel();
        if (animate) {
            p.animate().scaleX(s).scaleY(s).setDuration(160).start();
        } else {
            p.setScaleX(s);
            p.setScaleY(s);
        }
    }

    private void fade(View v, float alpha, boolean animate) {
        v.animate().cancel();
        if (animate) {
            v.animate().alpha(alpha).setDuration(220).start();
        } else {
            v.setAlpha(alpha);
        }
    }

    private void moveGlow(float cx, int color, boolean animate) {
        if (glowAnim != null) glowAnim.cancel();
        final float fromCx = glowCx;
        final int fromColor = glowColor;
        if (!animate) {
            glowCx = cx;
            glowColor = color;
            pushGlow();
            return;
        }
        if (Math.abs(fromCx - cx) < 1f && fromColor == color) return;
        glowAnim = ValueAnimator.ofFloat(0f, 1f);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            glowCx = fromCx + (cx - fromCx) * f;
            glowColor = (Integer) argb.evaluate(f, fromColor, color);
            pushGlow();
        });
        glowAnim.start();
    }

    private void pushGlow() {
        glow.set(glowCx, (bandY + bandH / 2f) * u, 1100f * u, glowColor);
    }

    /** Average colour of the saturated parts of a banner, used for the ambient glow. */
    private int sampleColor(Drawable d) {
        if (d == null) return DEFAULT_GLOW;
        try {
            int bw = 32;
            int bh = 18;
            Bitmap bm = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            Rect old = d.copyBounds();
            d.setBounds(0, 0, bw, bh);
            d.draw(c);
            d.setBounds(old);
            float[] hsv = new float[3];
            double sr = 0, sg = 0, sb = 0, sum = 0;
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    int p = bm.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                    double wgt = hsv[1] * hsv[2];
                    sr += Color.red(p) * wgt;
                    sg += Color.green(p) * wgt;
                    sb += Color.blue(p) * wgt;
                    sum += wgt;
                }
            }
            bm.recycle();
            if (sum < 0.001) return DEFAULT_GLOW;
            int col = Color.rgb((int) (sr / sum), (int) (sg / sum), (int) (sb / sum));
            Color.colorToHSV(col, hsv);
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.75f);
            return Color.HSVToColor(hsv);
        } catch (Exception e) {
            return DEFAULT_GLOW;
        }
    }

    // =====================================================================
    // Keys
    // =====================================================================

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
                || code == KeyEvent.KEYCODE_BACK;
        if (!ours) return super.dispatchKeyEvent(e);

        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    move(0, -1);
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    move(0, 1);
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    move(-1, 0);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    move(1, 0);
                    break;
                case KeyEvent.KEYCODE_BACK:
                    goBack();
                    break;
                default:
                    if (e.getRepeatCount() == 0) activate();
                    break;
            }
        }
        return true;
    }

    private void move(int dx, int dy) {
        if (inputsOpen) {
            if (dy != 0) {
                inputIdx = clamp(inputIdx + dy, 0, inputs.size() - 1);
                styleInputRows();
            }
            return;
        }
        switch (zone) {
            case ZONE_TOP:
                if (dx != 0) {
                    topIdx = clamp(topIdx + dx, 0, pills.size() - 1);
                } else if (dy > 0) {
                    if (!dockTiles.isEmpty()) {
                        zone = ZONE_DOCK;
                    } else if (!gridTiles.isEmpty()) {
                        zone = ZONE_GRID;
                    }
                }
                break;
            case ZONE_DOCK:
                if (dx != 0) {
                    dockIdx = clamp(dockIdx + dx, 0, dockTiles.size() - 1);
                } else if (dy < 0) {
                    zone = ZONE_TOP;
                } else if (dy > 0 && !gridTiles.isEmpty()) {
                    zone = ZONE_GRID;
                    gridIdx = Math.min(dockIdx, gridTiles.size() - 1);
                }
                break;
            case ZONE_GRID: {
                int count = gridTiles.size();
                int row = gridIdx / COLS;
                int col = gridIdx % COLS;
                if (dx < 0) {
                    if (col > 0) gridIdx--;
                } else if (dx > 0) {
                    if (col < COLS - 1 && gridIdx + 1 < count) gridIdx++;
                } else if (dy < 0) {
                    if (row == 0) {
                        if (!dockTiles.isEmpty()) {
                            zone = ZONE_DOCK;
                            dockIdx = Math.min(col, dockTiles.size() - 1);
                        } else {
                            zone = ZONE_TOP;
                        }
                    } else {
                        gridIdx -= COLS;
                    }
                } else if (dy > 0) {
                    if ((row + 1) * COLS < count) gridIdx = Math.min(gridIdx + COLS, count - 1);
                }
                break;
            }
            default:
                break;
        }
        applyFocus(true);
    }

    private void goBack() {
        if (inputsOpen) {
            closeInputs();
            return;
        }
        if (zone != ZONE_DOCK && !dockTiles.isEmpty()) {
            zone = ZONE_DOCK;
            applyFocus(true);
        }
    }

    private void activate() {
        if (inputsOpen) {
            if (inputIdx >= 0 && inputIdx < inputs.size()) {
                Intent i = inputs.get(inputIdx).intent;
                closeInputs();
                startSafe(i);
            }
            return;
        }
        if (zone == ZONE_TOP) {
            if (topIdx >= 0 && topIdx < pillActions.size()) pillActions.get(topIdx).run();
        } else if (zone == ZONE_DOCK) {
            if (dockIdx >= 0 && dockIdx < dockIntents.size()) startSafe(dockIntents.get(dockIdx));
        } else if (zone == ZONE_GRID) {
            if (gridIdx >= 0 && gridIdx < gridIntents.size()) startSafe(gridIntents.get(gridIdx));
        }
    }

    private boolean startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, R.string.msg_open_failed, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // =====================================================================
    // Inputs dropdown (HDMI / AV / Live TV through the TV input framework)
    // =====================================================================

    private void loadInputs() {
        inputs.clear();
        try {
            TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tim == null) return;
            for (TvInputInfo info : tim.getTvInputList()) {
                boolean pass = info.isPassthroughInput();
                if (!pass && info.getType() != TvInputInfo.TYPE_TUNER) continue;
                CharSequence label = info.loadCustomLabel(this);
                if (label == null || label.length() == 0) label = info.loadLabel(this);
                Intent i;
                if (pass) {
                    i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId()));
                } else {
                    i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.getId()));
                }
                inputs.add(new InputEntry(String.valueOf(label), i));
            }
        } catch (Exception ignored) {
        }
    }

    private void toggleInputs() {
        if (inputsOpen) {
            closeInputs();
        } else {
            openInputs();
        }
    }

    private void openInputs() {
        if (inputs.isEmpty() || pills.isEmpty()) return;
        if (inputsPanel != null) root.removeView(inputsPanel);
        inputsPanel = new LinearLayout(this);
        inputsPanel.setOrientation(LinearLayout.VERTICAL);
        inputsPanel.setBackground(rounded(0xF0222226, px(30f)));
        inputsPanel.setPadding(px(14f), px(14f), px(14f), px(14f));
        inputRows.clear();
        for (InputEntry en : inputs) {
            TextView r = makeText(30f, 0xFFFFFFFF, light);
            r.setText(en.label);
            r.setSingleLine(true);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(28f), 0, px(28f), 0);
            inputsPanel.addView(r, new LinearLayout.LayoutParams(px(440f), px(80f)));
            inputRows.add(r);
        }
        root.addView(inputsPanel, wrap());
        TextView pill = pills.get(0);
        int[] loc = new int[2];
        pill.getLocationOnScreen(loc);
        float x = Math.min(loc[0], sw - px(440f + 28f + 60f));
        inputsPanel.setX(x);
        inputsPanel.setY(loc[1] + pill.getHeight() + px(14f));
        inputsOpen = true;
        inputIdx = 0;
        styleInputRows();
    }

    private void closeInputs() {
        if (inputsPanel != null) {
            root.removeView(inputsPanel);
            inputsPanel = null;
        }
        inputRows.clear();
        inputsOpen = false;
    }

    private void styleInputRows() {
        for (int i = 0; i < inputRows.size(); i++) {
            inputRows.get(i).setBackground(i == inputIdx ? rounded(0x40FFFFFF, px(24f)) : null);
        }
    }

    // =====================================================================
    // Clock, greeting, Wi-Fi
    // =====================================================================

    private void updateClock() {
        Date now = new Date();
        boolean is24 = DateFormat.is24HourFormat(this);
        clockTv.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));

        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
        String d = new SimpleDateFormat(pattern, Locale.getDefault()).format(now);
        if (!d.isEmpty()) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        dateTv.setText(d);

        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g;
        if (hour >= 5 && hour < 12) {
            g = getString(R.string.greeting_morning);
        } else if (hour >= 12 && hour < 18) {
            g = getString(R.string.greeting_afternoon);
        } else {
            g = getString(R.string.greeting_evening);
        }
        String name = getString(R.string.greeting_name).trim();
        greetingTv.setText(name.isEmpty() ? g : g + ", " + name);
    }

    @SuppressWarnings("deprecation")
    private String wifiLabel() {
        String base = getString(R.string.pill_wifi);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();
            NetworkCapabilities nc = net == null ? null : cm.getNetworkCapabilities(net);
            if (nc == null) return getString(R.string.pill_offline);
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return getString(R.string.pill_ethernet);
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String ssid = wi == null ? null : wi.getSSID();
            if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) return base;
            return base + " · " + ssid;
        } catch (Exception e) {
            return base;
        }
    }

    // =====================================================================
    // State
    // =====================================================================

    private void saveState() {
        prefs.edit()
                .putInt("zone", zone == ZONE_TOP ? ZONE_DOCK : zone)
                .putInt("dock", dockIdx)
                .putInt("grid", gridIdx)
                .apply();
    }

    private void restoreState() {
        zone = prefs.getInt("zone", ZONE_DOCK);
        dockIdx = prefs.getInt("dock", 0);
        gridIdx = prefs.getInt("grid", 0);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private int px(float design) {
        return Math.round(design * u);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private FrameLayout.LayoutParams wrap() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView makeText(float sizeDesign, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeDesign * u);
        t.setTextColor(color);
        t.setTypeface(tf);
        return t;
    }

    private static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Outfit (if downloaded into assets) at a given weight, else a system fallback. */
    private Typeface outfit(int weight, String fallback) {
        try {
            Typeface.Builder b = new Typeface.Builder(getAssets(), "Outfit.ttf");
            b.setFontVariationSettings("'wght' " + weight);
            Typeface t = b.build();
            if (t != null) return t;
        } catch (Exception ignored) {
        }
        return Typeface.create(fallback, Typeface.NORMAL);
    }
}
