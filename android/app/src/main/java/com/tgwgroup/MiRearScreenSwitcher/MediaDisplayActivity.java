/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 背屏媒体界面。向右滑收起后露出原来的背屏桌面，再向右滑一次拉出。
 * 收起后只有向右滑会被截下，上、下、向左滑仍交给背屏桌面。
 * 背屏 Activity 的 DisplayMetrics 可能是主屏的，所以尺寸全部按实际布局像素计算。
 */
public class MediaDisplayActivity extends Activity implements MediaDisplayService.Listener {
    private static final String TAG = "MediaDisplayActivity";
    private static final int REAR_DISPLAY_ID = 1;
    private static final long POLL_MS = 500L;
    private static final long FADE_MS = 200L;
    /** 右侧触发区占屏宽的比例，需大于系统返回手势的保留区 */
    private static final float EDGE_ZONE_RATIO = 0.30f;
    /** 触发隐藏或拉出所需的横向滑动距离占屏宽的比例 */
    private static final float SWIPE_RATIO = 0.12f;
    /** 先看出方向再决定是否交给背屏桌面，约占屏宽 */
    private static final float DIRECTION_SLOP_RATIO = 0.025f;
    /** 隐藏的框架标志：触摸移出窗口后交给下面的窗口 */
    private static final int FLAG_SLIPPERY = 0x20000000;
    /** 左侧摄像头区域占屏宽的比例，内容只放在右侧 */
    private static final float CAMERA_ZONE_RATIO = 1f / 3f;

    private static MediaDisplayActivity instance;
    private static volatile boolean rearScreenOn = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Date clockDate = new Date();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private FrameLayout root;
    private FrameLayout layer;
    private ImageView blur;
    private View gradient;
    private TextView clock;
    private LinearLayout content;
    private FrameLayout albumFrame;
    private ImageView album;
    private TextView title;
    private TextView subtitle;
    private LinearLayout controls;
    private ImageButton previous;
    private ImageButton play;
    private ImageButton next;
    private ProgressBar progress;

    private DisplayManager displayManager;
    private MediaDisplayService.MediaSnapshot snapshot = MediaDisplayService.MediaSnapshot.inactive();
    private boolean hidden;
    private boolean hiddenSettled;
    private boolean leavingFront;
    private boolean displayOn = true;
    private int sizedWidth;
    private int sizedHeight;
    private float albumRadius;

    private float touchStartX;
    private float touchStartY;
    private boolean trackingEdge;
    private long lastToggleMs;
    private boolean finishReceiverRegistered;
    private View edgeCatcher;
    private WindowManager edgeWindowManager;
    private int edgeScreenWidth;
    private int edgeScreenHeight;
    private float edgeTouchStartX;
    private float edgeTouchStartY;
    private long edgeDownMs;
    private boolean edgeHandOff;
    private boolean edgeRecallSent;
    private boolean edgeTransferred;
    private final Runnable restoreEdgeRunnable = this::restoreEdgeFrame;

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MediaDisplayService.ACTION_FINISH.equals(intent.getAction())) {
                finish();
            }
        }
    };

    /** 界面自己轮询状态，不依赖回调时序 */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            MediaDisplayService.MediaSnapshot latest = MediaDisplayService.getSnapshot();
            if (latest != snapshot) {
                onMediaChanged(latest);
            }
            updateDisplayState();
            updateClock();
            updateProgress();
            handler.postDelayed(this, POLL_MS);
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == REAR_DISPLAY_ID) {
                updateDisplayState();
            }
        }
    };

    public static boolean isAlive() {
        return instance != null;
    }

    public static void setRearScreenOn(boolean on) {
        rearScreenOn = on;
        MediaDisplayActivity activity = instance;
        if (activity != null) {
            activity.runOnUiThread(activity::updateDisplayState);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!MediaDisplayService.isModeEnabled(this)) {
            finish();
            return;
        }
        instance = this;
        setContentView(R.layout.activity_media_display);
        bindViews();
        getWindow().setFormat(PixelFormat.TRANSLUCENT);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().getDecorView().setBackgroundColor(Color.TRANSPARENT);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(displayListener, handler);
        }
        registerFinishReceiver();
        registerBackCallback();
        MediaDisplayService.addListener(this);

        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width != sizedWidth || height != sizedHeight) {
                root.post(() -> applySizes(width, height));
            }
        });

        onMediaChanged(MediaDisplayService.getSnapshot());
        if (isFinishing()) {
            return;
        }
        if (MediaDisplayService.isCollapsed()) {
            hidden = true;
            applyHiddenImmediate();
            root.post(this::settleHidden);
        }
        // am start 偶尔落到主屏：退到后台，等服务把任务移到背屏
        if (!isOnRearDisplay()) {
            moveTaskToBack(true);
        }
        updateDisplayState();
        handler.post(pollRunnable);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root == null) {
            return;
        }
        sizedWidth = 0;
        sizedHeight = 0;
        root.requestLayout();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) {
            return;
        }
        if (hidden && MediaDisplayService.isCollapsed()) {
            applyWindowAlpha(0f);
            if (edgeCatcher != null) {
                setWindowTouchable(false);
                if (!leavingFront) {
                    leavingFront = true;
                    moveTaskToBack(true);
                    handler.postDelayed(() -> leavingFront = false, 500);
                }
            }
            return;
        }
        if (hidden) {
            setHidden(false, true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (hidden) {
            settleHidden();
        }
    }

    @Override
    protected void onDestroy() {
        removeEdgeCatcher();
        handler.removeCallbacksAndMessages(null);
        MediaDisplayService.removeListener(this);
        if (displayManager != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        if (finishReceiverRegistered) {
            unregisterReceiver(finishReceiver);
            finishReceiverRegistered = false;
        }
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        hideFromBack();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (hidden) {
            return edgeCatcher != null || onHiddenActivityTouch(event);
        }
        float width = root == null ? 0f : root.getWidth();
        if (width <= 0f) {
            return super.dispatchTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackingEdge = event.getX() >= width * (1f - EDGE_ZONE_RATIO);
                touchStartX = event.getX();
                touchStartY = event.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (trackingEdge) {
                    float dx = event.getX() - touchStartX;
                    float dy = event.getY() - touchStartY;
                    if (dx <= -width * SWIPE_RATIO && Math.abs(dx) > Math.abs(dy)) {
                        trackingEdge = false;
                        setHidden(true, true);
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                trackingEdge = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** 界面还盖在背屏上时的后备：只有向右滑才呼出，其余方向让给桌面 */
    private boolean onHiddenActivityTouch(MotionEvent event) {
        float width = root != null && root.getWidth() > 0 ? root.getWidth() : edgeScreenWidth;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                trackingEdge = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!trackingEdge || width <= 0f) {
                    return false;
                }
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                if (isRightSwipe(dx, dy, width)) {
                    if (dx >= width * SWIPE_RATIO) {
                        trackingEdge = false;
                        setHidden(false, true);
                    }
                    return true;
                }
                if (directionKnown(dx, dy, width)) {
                    trackingEdge = false;
                    setWindowTouchable(false);
                    moveTaskToBack(true);
                    return false;
                }
                return true;
            default:
                trackingEdge = false;
                return false;
        }
    }

    @Override
    public void onMediaChanged(MediaDisplayService.MediaSnapshot value) {
        if (!value.active || !MediaDisplayService.isModeEnabled(this)) {
            finish();
            return;
        }
        MediaDisplayService.MediaSnapshot previousSnapshot = snapshot;
        snapshot = value;
        if (!value.title.contentEquals(title.getText())) {
            title.setText(value.title);
        }
        String line = subtitleText(value);
        if (!line.contentEquals(subtitle.getText())) {
            subtitle.setText(line);
        }
        if (value.artwork != previousSnapshot.artwork || value.themeColor != previousSnapshot.themeColor
                || !previousSnapshot.active) {
            applyArtwork(value);
        }
        play.setImageResource(value.playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        setControlEnabled(previous, value.canPrev);
        setControlEnabled(next, value.canNext);
        setControlEnabled(play, value.playing ? value.canPause : value.canPlay);
        updateProgress();
        applyChrome();
    }

    private static String subtitleText(MediaDisplayService.MediaSnapshot value) {
        if (value.artist.isEmpty()) {
            return value.title;
        }
        if (value.title.isEmpty()) {
            return value.artist;
        }
        return value.title + "-" + value.artist;
    }

    private void bindViews() {
        root = findViewById(R.id.media_root);
        layer = findViewById(R.id.media_layer);
        blur = findViewById(R.id.media_blur);
        gradient = findViewById(R.id.media_gradient);
        clock = findViewById(R.id.media_clock);
        content = findViewById(R.id.media_content);
        albumFrame = findViewById(R.id.media_album_frame);
        album = findViewById(R.id.media_album);
        title = findViewById(R.id.media_title);
        subtitle = findViewById(R.id.media_subtitle);
        controls = findViewById(R.id.media_controls);
        previous = findViewById(R.id.media_prev);
        play = findViewById(R.id.media_play);
        next = findViewById(R.id.media_next);
        progress = findViewById(R.id.media_progress);

        album.setClipToOutline(true);
        album.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), albumRadius);
            }
        });
        previous.setOnClickListener(v -> MediaDisplayService.skipToPrevious());
        play.setOnClickListener(v -> MediaDisplayService.playPause());
        next.setOnClickListener(v -> MediaDisplayService.skipToNext());
    }

    /** 参考图比例：专辑约占屏高 30%，标题约 8.5%，按钮约 13%；内容放在右侧 2/3 */
    private void applySizes(int width, int height) {
        if (width <= 0 || height <= 0 || (width == sizedWidth && height == sizedHeight)) {
            return;
        }
        sizedWidth = width;
        sizedHeight = height;

        int contentLeft = Math.round(width * CAMERA_ZONE_RATIO + width * 0.03f);
        int contentRight = Math.round(width * 0.07f);
        int contentWidth = Math.max(1, width - contentLeft - contentRight);

        FrameLayout.LayoutParams contentParams = (FrameLayout.LayoutParams) content.getLayoutParams();
        contentParams.width = contentWidth;
        contentParams.leftMargin = contentLeft;
        contentParams.topMargin = Math.round(height * 0.10f);
        content.setLayoutParams(contentParams);

        int albumSize = Math.round(height * 0.30f);
        int albumPadding = Math.round(albumSize * 0.06f);
        albumRadius = albumSize * 0.12f;
        ViewGroup.LayoutParams albumParams = albumFrame.getLayoutParams();
        albumParams.width = albumSize;
        albumParams.height = albumSize;
        albumFrame.setLayoutParams(albumParams);
        albumFrame.setPadding(albumPadding, albumPadding, albumPadding, albumPadding);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(Color.WHITE);
        frame.setCornerRadius(albumSize * 0.18f);
        albumFrame.setBackground(frame);
        album.invalidateOutline();

        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.085f);
        setTopMargin(title, Math.round(height * 0.04f));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.052f);
        setTopMargin(subtitle, Math.round(height * 0.012f));

        setTopMargin(controls, Math.round(height * 0.05f));
        int buttonSize = Math.round(height * 0.13f);
        int buttonPadding = Math.round(height * 0.02f);
        for (ImageButton button : new ImageButton[] {previous, play, next}) {
            ViewGroup.LayoutParams params = button.getLayoutParams();
            params.width = buttonSize;
            params.height = buttonSize;
            button.setLayoutParams(params);
            button.setPadding(buttonPadding, buttonPadding, buttonPadding, buttonPadding);
        }

        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, height * 0.06f);
        int clockPadH = Math.round(height * 0.035f);
        int clockPadV = Math.round(height * 0.012f);
        clock.setPadding(clockPadH, clockPadV, clockPadH, clockPadV);
        GradientDrawable clockBg = new GradientDrawable();
        clockBg.setColor(0x55000000);
        clockBg.setCornerRadius(height * 0.05f);
        clock.setBackground(clockBg);
        FrameLayout.LayoutParams clockParams = (FrameLayout.LayoutParams) clock.getLayoutParams();
        clockParams.topMargin = Math.round(height * 0.09f);
        clockParams.rightMargin = Math.round(width * 0.06f);
        clock.setLayoutParams(clockParams);

        int barWidth = Math.round(width * 0.075f);
        int barHeight = Math.max(2, Math.round(height * 0.008f));
        FrameLayout.LayoutParams barParams = (FrameLayout.LayoutParams) progress.getLayoutParams();
        barParams.width = barWidth;
        barParams.height = barHeight;
        barParams.leftMargin = contentLeft + (contentWidth - barWidth) / 2;
        barParams.bottomMargin = Math.round(height * 0.04f);
        progress.setLayoutParams(barParams);

        if (hidden) {
            layer.setTranslationX(width);
        }
    }

    private static void setTopMargin(View view, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        params.topMargin = margin;
        view.setLayoutParams(params);
    }

    private void registerFinishReceiver() {
        IntentFilter filter = new IntentFilter(MediaDisplayService.ACTION_FINISH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishReceiver, filter);
        }
        finishReceiverRegistered = true;
    }

    /** 返回只隐藏界面，不结束 Activity，否则背屏会变成空白 */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackCallback33();
        }
    }

    private void registerBackCallback33() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::hideFromBack);
    }

    private void hideFromBack() {
        if (SystemClock.elapsedRealtime() - lastToggleMs < 800) {
            return;
        }
        setHidden(true, true);
    }

    private void applyArtwork(MediaDisplayService.MediaSnapshot value) {
        if (value.artwork != null) {
            album.setImageBitmap(value.artwork);
            blur.setImageBitmap(value.artwork);
            blur.setAlpha(0.55f);
        } else {
            album.setImageResource(R.mipmap.ic_launcher);
            blur.setImageDrawable(null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurEffect(value.artwork != null);
        }
        applyGradient(value.themeColor, value.artwork != null);
    }

    private void applyBlurEffect(boolean hasArtwork) {
        blur.setRenderEffect(hasArtwork
                ? RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP)
                : null);
    }

    private void applyGradient(int color, boolean hasArtwork) {
        int top = darken(color, 0.35f);
        int bottom = lighten(color, 0.25f);
        if (hasArtwork) {
            top = withAlpha(top, 225);
            bottom = withAlpha(bottom, 185);
        }
        gradient.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom}));
    }

    /** 背屏息屏时只保留专辑图片和曲名 */
    private void applyChrome() {
        int full = displayOn ? View.VISIBLE : View.GONE;
        clock.setVisibility(full);
        subtitle.setVisibility(full);
        controls.setVisibility(full);
        progress.setVisibility(full);
    }

    private void setHidden(boolean hide, boolean animate) {
        if (hidden == hide) {
            return;
        }
        hidden = hide;
        hiddenSettled = false;
        lastToggleMs = SystemClock.elapsedRealtime();
        if (hide) {
            MediaDisplayService.setCollapsed(true);
        } else {
            removeEdgeCatcher();
            MediaDisplayService.setCollapsedTouchLayer(false);
            setWindowTouchable(true);
            applyWindowAlpha(1f);
            MediaDisplayService.setCollapsed(false);
        }
        float width = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        float targetX = hide ? width : 0f;
        float targetAlpha = hide ? 0f : 1f;
        layer.animate().cancel();
        if (!animate) {
            layer.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
            layer.setAlpha(targetAlpha);
            layer.setTranslationX(targetX);
            if (hide) {
                settleHidden();
            }
            return;
        }
        layer.setVisibility(View.VISIBLE);
        layer.animate().alpha(targetAlpha).translationX(targetX).setDuration(FADE_MS)
                .withEndAction(() -> {
                    if (hide != hidden) {
                        return;
                    }
                    layer.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
                    if (hidden) {
                        settleHidden();
                    }
                })
                .start();
    }

    /** 滑走结束后让出背屏，桌面才能显示出来。右缘手势层负责再滑一次拉出 */
    private void settleHidden() {
        if (!hidden || hiddenSettled || isFinishing()) {
            return;
        }
        hiddenSettled = true;
        applyHiddenImmediate();
        boolean overlay = showEdgeCatcher();
        MediaDisplayService.setCollapsedTouchLayer(!overlay);
        Log.d(TAG, overlay ? "收起后显示背屏桌面" : "收起后使用透明手势层");
        if (overlay) {
            setWindowTouchable(false);
            leavingFront = true;
            moveTaskToBack(true);
            handler.postDelayed(() -> leavingFront = false, 500);
        }
    }

    private void setWindowTouchable(boolean touchable) {
        if (touchable) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    private void applyHiddenImmediate() {
        hidden = true;
        if (layer != null) {
            layer.animate().cancel();
            float width = root != null && root.getWidth() > 0 ? root.getWidth() : 0f;
            layer.setAlpha(0f);
            layer.setTranslationX(width);
            layer.setVisibility(View.INVISIBLE);
        }
        applyWindowAlpha(0f);
    }

    private void applyWindowAlpha(float alpha) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (params.alpha == alpha) {
            return;
        }
        params.alpha = alpha;
        getWindow().setAttributes(params);
    }

    /** 盖住背屏只为识别向右滑。上下左在看出方向后交给原来的桌面 */
    private boolean showEdgeCatcher() {
        if (edgeCatcher != null) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        DisplayManager manager = displayManager;
        if (manager == null) {
            manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        }
        Display display = manager == null ? null : manager.getDisplay(REAR_DISPLAY_ID);
        if (display == null) {
            return false;
        }
        try {
            Context windowContext = createWindowContext(
                    display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager managerOnDisplay = windowContext.getSystemService(WindowManager.class);
            if (managerOnDisplay == null) {
                return false;
            }
            edgeScreenWidth = display.getWidth();
            edgeScreenHeight = display.getHeight();
            View edge = new View(windowContext);
            edge.setBackgroundColor(Color.TRANSPARENT);
            edge.setClickable(true);
            edge.setOnTouchListener((view, event) -> onEdgeTouch(event));
            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | FLAG_SLIPPERY;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    edgeScreenWidth > 0 ? edgeScreenWidth : WindowManager.LayoutParams.MATCH_PARENT,
                    edgeScreenHeight > 0 ? edgeScreenHeight : WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                params.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            managerOnDisplay.addView(edge, params);
            edgeCatcher = edge;
            edgeWindowManager = managerOnDisplay;
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "背屏手势层添加失败", e);
            edgeCatcher = null;
            edgeWindowManager = null;
            return false;
        }
    }

    private boolean onEdgeTouch(MotionEvent event) {
        float width = edgeScreenWidth;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeTouchStartX = event.getRawX();
                edgeTouchStartY = event.getRawY();
                edgeDownMs = SystemClock.uptimeMillis();
                edgeHandOff = false;
                edgeRecallSent = false;
                edgeTransferred = false;
                handler.removeCallbacks(restoreEdgeRunnable);
                restoreEdgeFrame();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (edgeRecallSent || edgeHandOff || width <= 0f) {
                    return !edgeHandOff;
                }
                float dx = event.getRawX() - edgeTouchStartX;
                float dy = event.getRawY() - edgeTouchStartY;
                if (isRightSwipe(dx, dy, width)) {
                    if (dx >= width * SWIPE_RATIO) {
                        edgeRecallSent = true;
                        handler.post(() -> setHidden(false, true));
                    }
                    return true;
                }
                if (directionKnown(dx, dy, width)) {
                    edgeHandOff = true;
                    handOffGesture();
                    scheduleRestoreEdge(1200);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!edgeRecallSent && !edgeTransferred && width > 0f) {
                    float upDx = event.getRawX() - edgeTouchStartX;
                    float upDy = event.getRawY() - edgeTouchStartY;
                    long elapsed = SystemClock.uptimeMillis() - edgeDownMs;
                    if (forwardRearGesture(upDx, upDy, width, event.getRawX(), event.getRawY())) {
                        scheduleRestoreEdge(elapsed + 250);
                        return true;
                    }
                }
                scheduleRestoreEdge(200);
                return true;
            case MotionEvent.ACTION_CANCEL:
                edgeTransferred = true;
                scheduleRestoreEdge(200);
                return true;
            default:
                return true;
        }
    }

    private static boolean isRightSwipe(float dx, float dy, float width) {
        return directionKnown(dx, dy, width) && dx > 0f && dx > Math.abs(dy);
    }

    private static boolean directionKnown(float dx, float dy, float width) {
        float slop = Math.max(16f, width * DIRECTION_SLOP_RATIO);
        return Math.abs(dx) >= slop || Math.abs(dy) >= slop;
    }

    /** 把窗口缩到屏外，让这次上下或向左的滑动落到背屏桌面 */
    private void handOffGesture() {
        View edge = edgeCatcher;
        WindowManager manager = edgeWindowManager;
        if (edge == null || manager == null) {
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) edge.getLayoutParams();
        if (params == null) {
            return;
        }
        params.width = 1;
        params.height = 1;
        params.x = -20;
        params.y = -20;
        params.flags |= FLAG_SLIPPERY | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        try {
            manager.updateViewLayout(edge, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "让出背屏手势失败", e);
        }
    }

    /** @return 已经把这次手势转给背屏桌面 */
    private boolean forwardRearGesture(float dx, float dy, float width, float endX, float endY) {
        if (isRightSwipe(dx, dy, width)) {
            return false;
        }
        int x1 = Math.round(edgeTouchStartX);
        int y1 = Math.round(edgeTouchStartY);
        int x2 = Math.round(endX);
        int y2 = Math.round(endY);
        setEdgeTouchable(false);
        boolean tap = !directionKnown(dx, dy, width);
        long duration = SystemClock.uptimeMillis() - edgeDownMs;
        MediaDisplayService.injectRearPointer(x1, y1, x2, y2, duration, tap);
        return true;
    }

    private void setEdgeTouchable(boolean touchable) {
        View edge = edgeCatcher;
        WindowManager manager = edgeWindowManager;
        if (edge == null || manager == null) {
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) edge.getLayoutParams();
        if (params == null) {
            return;
        }
        if (touchable) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            manager.updateViewLayout(edge, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "更新背屏手势层失败", e);
        }
    }

    private void scheduleRestoreEdge(long delayMs) {
        handler.removeCallbacks(restoreEdgeRunnable);
        handler.postDelayed(restoreEdgeRunnable, delayMs);
    }

    private void restoreEdgeFrame() {
        View edge = edgeCatcher;
        WindowManager manager = edgeWindowManager;
        if (edge == null || manager == null) {
            return;
        }
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) edge.getLayoutParams();
        if (params == null) {
            return;
        }
        params.width = edgeScreenWidth > 0 ? edgeScreenWidth : WindowManager.LayoutParams.MATCH_PARENT;
        params.height = edgeScreenHeight > 0 ? edgeScreenHeight : WindowManager.LayoutParams.MATCH_PARENT;
        params.x = 0;
        params.y = 0;
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            manager.updateViewLayout(edge, params);
        } catch (RuntimeException e) {
            Log.w(TAG, "恢复背屏手势层失败", e);
        }
    }

    private void removeEdgeCatcher() {
        handler.removeCallbacks(restoreEdgeRunnable);
        View edge = edgeCatcher;
        WindowManager manager = edgeWindowManager;
        edgeCatcher = null;
        edgeWindowManager = null;
        if (edge == null || manager == null) {
            return;
        }
        try {
            manager.removeView(edge);
        } catch (RuntimeException e) {
            Log.w(TAG, "移除背屏手势层失败", e);
        }
    }

    private boolean isOnRearDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        Display display = getDisplay();
        return display != null && display.getDisplayId() == REAR_DISPLAY_ID;
    }

    private void updateDisplayState() {
        Display display = displayManager == null ? null : displayManager.getDisplay(REAR_DISPLAY_ID);
        int state = display == null ? Display.STATE_UNKNOWN : display.getState();
        boolean on;
        if (state == Display.STATE_UNKNOWN) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            on = rearScreenOn && (power == null || power.isInteractive());
        } else {
            on = state == Display.STATE_ON;
        }
        if (on == displayOn) {
            return;
        }
        displayOn = on;
        applyChrome();
    }

    private void updateClock() {
        clockDate.setTime(System.currentTimeMillis());
        String text = clockFormat.format(clockDate);
        if (!text.contentEquals(clock.getText())) {
            clock.setText(text);
        }
    }

    private void updateProgress() {
        if (snapshot.durationMs <= 0) {
            progress.setProgress(0);
            return;
        }
        long position = MediaDisplayService.currentPosition();
        int value = (int) Math.max(0, Math.min(1000, position * 1000 / snapshot.durationMs));
        progress.setProgress(value);
    }

    private void setControlEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private static int darken(int color, float factor) {
        return Color.rgb(
                clamp((int) (Color.red(color) * factor)),
                clamp((int) (Color.green(color) * factor)),
                clamp((int) (Color.blue(color) * factor)));
    }

    private static int lighten(int color, float amount) {
        return Color.rgb(
                clamp((int) (Color.red(color) + (255 - Color.red(color)) * amount)),
                clamp((int) (Color.green(color) + (255 - Color.green(color)) * amount)),
                clamp((int) (Color.blue(color) + (255 - Color.blue(color)) * amount)));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
