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
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
 * 背屏媒体界面。右缘滑到中线收起，再滑拉出。
 */
public class MediaDisplayActivity extends Activity implements MediaDisplayService.Listener {
    private static final int REAR_DISPLAY_ID = 1;

    private static MediaDisplayActivity instance;
    private static boolean rearScreenOn = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Date clockDate = new Date();
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private FrameLayout root;
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

    private MediaDisplayService.MediaSnapshot snapshot = MediaDisplayService.MediaSnapshot.inactive();
    private boolean collapsed;
    private boolean trackingEdge;
    private float downRawX;
    private boolean finishReceiverRegistered;

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MediaDisplayService.ACTION_FINISH.equals(intent.getAction())) {
                finish();
            }
        }
    };

    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60000 - (now % 60000) + 50);
        }
    };

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (!collapsed && rearScreenOn && snapshot.playing) {
                handler.postDelayed(this, 500);
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
            activity.runOnUiThread(activity::applyChrome);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int displayId = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getDisplay() != null) {
            displayId = getDisplay().getDisplayId();
        }
        if (displayId != REAR_DISPLAY_ID) {
            finish();
            return;
        }
        if (!MediaDisplayService.isModeEnabled(this)) {
            finish();
            return;
        }
        instance = this;
        setContentView(R.layout.activity_media_display);
        bindViews();
        registerFinishReceiver();
        registerBackCallback();
        MediaDisplayService.addListener(this);
        onMediaChanged(MediaDisplayService.getSnapshot());
        if (isFinishing()) {
            return;
        }
        updateClock();
        handler.post(clockRunnable);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        MediaDisplayService.removeListener(this);
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
    public void onBackPressed() {
        collapse();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (onEdgeGesture(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onMediaChanged(MediaDisplayService.MediaSnapshot next) {
        if (!next.active || !MediaDisplayService.isModeEnabled(this)) {
            finish();
            return;
        }
        snapshot = next;
        title.setText(next.title);
        if (next.artist == null || next.artist.isEmpty()) {
            subtitle.setText("");
        } else if (next.title == null || next.title.isEmpty()) {
            subtitle.setText(next.artist);
        } else {
            subtitle.setText(next.title + "-" + next.artist);
        }
        if (next.artwork != null) {
            album.setImageBitmap(next.artwork);
            blur.setImageBitmap(next.artwork);
        } else {
            album.setImageDrawable(null);
            blur.setImageDrawable(null);
        }
        applyBlur();
        applyGradient(next.themeColor, next.artwork != null);
        play.setImageResource(next.playing ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        setControlEnabled(previous, next.canPrev);
        setControlEnabled(nextButton(), next.canNext);
        setControlEnabled(play, next.playing ? next.canPause : next.canPlay);
        updateProgress();
        applyChrome();
    }

    private ImageButton nextButton() {
        return next;
    }

    private void bindViews() {
        root = findViewById(R.id.media_root);
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
                float radius = 14f * getResources().getDisplayMetrics().density;
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        previous.setOnClickListener(v -> MediaDisplayService.skipToPrevious());
        play.setOnClickListener(v -> MediaDisplayService.playPause());
        next.setOnClickListener(v -> MediaDisplayService.skipToNext());
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

    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerBackCallback33();
        }
    }

    private void registerBackCallback33() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::collapse);
    }

    private void applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyBlurEffect();
        }
    }

    private void applyBlurEffect() {
        if (snapshot.artwork == null) {
            blur.setRenderEffect(null);
            return;
        }
        blur.setRenderEffect(RenderEffect.createBlurEffect(80f, 80f, Shader.TileMode.CLAMP));
    }

    private void applyGradient(int color, boolean hasArtwork) {
        int top = darken(color, 0.42f);
        int bottom = lighten(color, 0.28f);
        if (hasArtwork) {
            top = withAlpha(top, 210);
            bottom = withAlpha(bottom, 170);
        }
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        gradient.setBackground(drawable);
    }

    private void applyChrome() {
        if (collapsed) {
            content.setVisibility(View.GONE);
            clock.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            return;
        }
        content.setVisibility(View.VISIBLE);
        albumFrame.setVisibility(View.VISIBLE);
        title.setVisibility(View.VISIBLE);
        boolean full = rearScreenOn;
        clock.setVisibility(full ? View.VISIBLE : View.GONE);
        subtitle.setVisibility(full ? View.VISIBLE : View.GONE);
        controls.setVisibility(full ? View.VISIBLE : View.GONE);
        progress.setVisibility(full ? View.VISIBLE : View.GONE);
        if (full && snapshot.playing) {
            handler.removeCallbacks(progressRunnable);
            handler.post(progressRunnable);
        }
    }

    private void updateClock() {
        clockDate.setTime(System.currentTimeMillis());
        clock.setText(clockFormat.format(clockDate));
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

    private boolean onEdgeGesture(MotionEvent event) {
        int width = screenWidth();
        if (width <= 0) {
            return false;
        }
        float edge = width - dp(48);
        float mid = width / 2f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                trackingEdge = collapsed || event.getRawX() >= edge;
                downRawX = event.getRawX();
                if (trackingEdge && collapsed) {
                    widenForDrag();
                    return true;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!trackingEdge) {
                    return false;
                }
                if (downRawX >= edge && event.getRawX() <= mid) {
                    if (collapsed) {
                        expand();
                    } else {
                        collapse();
                    }
                    trackingEdge = false;
                    return true;
                }
                return collapsed;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean consume = trackingEdge && collapsed;
                trackingEdge = false;
                if (collapsed) {
                    applyWindowSize();
                }
                return consume;
            default:
                return false;
        }
    }

    private void collapse() {
        if (isFinishing() || collapsed) {
            return;
        }
        collapsed = true;
        applyWindowSize();
        applyChrome();
    }

    private void expand() {
        if (!collapsed) {
            return;
        }
        if (!snapshot.active || !MediaDisplayService.isModeEnabled(this)) {
            finish();
            return;
        }
        collapsed = false;
        applyWindowSize();
        applyChrome();
    }

    private void widenForDrag() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.TOP | Gravity.START;
        params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        params.dimAmount = 0f;
        getWindow().setAttributes(params);
    }

    private void applyWindowSize() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (collapsed) {
            params.width = dp(28);
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.END | Gravity.TOP;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            params.dimAmount = 0f;
        } else {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | Gravity.START;
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        getWindow().setAttributes(params);
    }

    private int screenWidth() {
        if (root != null && root.getWidth() > 0 && !collapsed) {
            return root.getWidth();
        }
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
