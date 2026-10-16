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

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * 背屏媒体显示：开启时读取当前媒体会话，并在背屏显示播放状态。
 */
public class MediaDisplayService extends Service {
    private static final String TAG = "MediaDisplayService";
    public static final String PREFS = "media_display";
    public static final String KEY_ENABLED = "media_mode_enabled";
    public static final String ACTION_FINISH = "com.tgwgroup.MiRearScreenSwitcher.MEDIA_FINISH";
    private static final int NOTIFICATION_ID = 10005;
    private static final int DEFAULT_THEME = 0xFF4A2030;

    public interface Listener {
        void onMediaChanged(MediaSnapshot snapshot);
    }

    public static final class MediaSnapshot {
        public final boolean active;
        public final boolean playing;
        public final String title;
        public final String artist;
        public final Bitmap artwork;
        public final int themeColor;
        public final long durationMs;
        public final boolean canPrev;
        public final boolean canNext;
        public final boolean canPlay;
        public final boolean canPause;

        MediaSnapshot(boolean active, boolean playing, String title, String artist, Bitmap artwork,
                int themeColor, long durationMs, boolean canPrev, boolean canNext, boolean canPlay,
                boolean canPause) {
            this.active = active;
            this.playing = playing;
            this.title = title;
            this.artist = artist;
            this.artwork = artwork;
            this.themeColor = themeColor;
            this.durationMs = durationMs;
            this.canPrev = canPrev;
            this.canNext = canNext;
            this.canPlay = canPlay;
            this.canPause = canPause;
        }

        static MediaSnapshot inactive() {
            return new MediaSnapshot(false, false, "", "", null, DEFAULT_THEME, 0,
                    false, false, false, false);
        }
    }

    private static MediaDisplayService instance;
    private static MediaSnapshot snapshot = MediaSnapshot.inactive();
    private static final List<Listener> listeners = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager sessionManager;
    private MediaController controller;
    private List<MediaController> currentSessions = new ArrayList<>();
    private ITaskService taskService;
    private boolean sessionsRegistered;
    private int artGeneration;
    private long lastLaunchMs;

    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
            .daemon(false)
            .processNameSuffix("task_service")
            .debuggable(false)
            .version(1);

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            taskService = ITaskService.Stub.asInterface(binder);
            handler.post(() -> {
                if (snapshot.active) {
                    ensureUi();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            taskService = null;
            handler.postDelayed(() -> {
                if (instance != null) {
                    bindTaskService();
                }
            }, 500);
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> handler.post(() -> onSessionsChanged(controllers));

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            publishFromController();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publishFromController();
        }

        @Override
        public void onSessionDestroyed() {
            controller = null;
            onSessionsChanged(currentSessions);
        }
    };

    private final Runnable ensureRunnable = new Runnable() {
        @Override
        public void run() {
            if (instance == null || !snapshot.active || !isModeEnabled(MediaDisplayService.this)) {
                return;
            }
            ensureUi();
            handler.postDelayed(this, 3000);
        }
    };

    public static boolean isModeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static MediaSnapshot getSnapshot() {
        return snapshot;
    }

    public static long currentPosition() {
        MediaDisplayService service = instance;
        if (service == null || service.controller == null) {
            return 0;
        }
        PlaybackState state = service.controller.getPlaybackState();
        if (state == null) {
            return 0;
        }
        return Math.max(0, state.getPosition());
    }

    public static void skipToPrevious() {
        transport(controls -> controls.skipToPrevious());
    }

    public static void skipToNext() {
        transport(controls -> controls.skipToNext());
    }

    public static void playPause() {
        MediaDisplayService service = instance;
        if (service == null || service.controller == null) {
            return;
        }
        MediaController.TransportControls controls = service.controller.getTransportControls();
        if (snapshot.playing) {
            controls.pause();
        } else {
            controls.play();
        }
    }

    public static void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static void refreshTiles(Context context) {
        TileService.requestListeningState(context,
                new ComponentName(context, MediaDisplayTileService.class));
        TileService.requestListeningState(context,
                new ComponentName(context, SwitchToRearTileService.class));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private interface TransportAction {
        void run(MediaController.TransportControls controls);
    }

    private static void transport(TransportAction action) {
        MediaDisplayService service = instance;
        if (service == null || service.controller == null) {
            return;
        }
        action.run(service.controller.getTransportControls());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        bindTaskService();
        registerSessions();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        if (!isModeEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        registerSessions();
        refreshTiles(this);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ensureRunnable);
        unregisterSessions();
        if (controller != null) {
            controller.unregisterCallback(controllerCallback);
            controller = null;
        }
        publish(MediaSnapshot.inactive());
        requestFinishUi();
        unbindTaskService();
        if (instance == this) {
            instance = null;
        }
        refreshTiles(this);
        super.onDestroy();
    }

    private void registerSessions() {
        if (sessionManager == null) {
            return;
        }
        ComponentName listener = new ComponentName(this, NotificationService.class);
        try {
            if (!sessionsRegistered) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listener, handler);
                sessionsRegistered = true;
            }
            onSessionsChanged(sessionManager.getActiveSessions(listener));
        } catch (SecurityException e) {
            Log.e(TAG, "通知使用权未开启，无法读取媒体会话", e);
        }
    }

    private void unregisterSessions() {
        if (!sessionsRegistered || sessionManager == null) {
            sessionsRegistered = false;
            return;
        }
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (Exception e) {
            Log.w(TAG, "注销媒体会话监听失败", e);
        }
        sessionsRegistered = false;
    }

    private void onSessionsChanged(List<MediaController> controllers) {
        currentSessions = controllers == null ? new ArrayList<>() : controllers;
        MediaController next = pick(currentSessions);
        if (sameSession(controller, next)) {
            publishFromController();
            return;
        }
        if (controller != null) {
            controller.unregisterCallback(controllerCallback);
        }
        controller = next;
        if (controller != null) {
            controller.registerCallback(controllerCallback, handler);
        }
        publishFromController();
    }

    private MediaController pick(List<MediaController> controllers) {
        MediaController paused = null;
        for (MediaController item : controllers) {
            PlaybackState state = item.getPlaybackState();
            if (state == null) {
                continue;
            }
            int code = state.getState();
            if (code == PlaybackState.STATE_PLAYING
                    || code == PlaybackState.STATE_BUFFERING
                    || code == PlaybackState.STATE_CONNECTING) {
                return item;
            }
            if (code == PlaybackState.STATE_PAUSED && paused == null) {
                paused = item;
            }
        }
        return paused;
    }

    private boolean sameSession(MediaController left, MediaController right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.getSessionToken().equals(right.getSessionToken());
    }

    private void publishFromController() {
        if (controller == null) {
            artGeneration++;
            publish(MediaSnapshot.inactive());
            requestFinishUi();
            handler.removeCallbacks(ensureRunnable);
            return;
        }
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        if (state == null || !isActiveState(state.getState())) {
            artGeneration++;
            publish(MediaSnapshot.inactive());
            requestFinishUi();
            handler.removeCallbacks(ensureRunnable);
            return;
        }
        String title = text(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) {
            artist = text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        long duration = metadata == null ? 0 : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long actions = state.getActions();
        boolean canPrev = hasAction(actions, PlaybackState.ACTION_SKIP_TO_PREVIOUS);
        boolean canNext = hasAction(actions, PlaybackState.ACTION_SKIP_TO_NEXT);
        boolean canPlay = hasAction(actions, PlaybackState.ACTION_PLAY)
                || hasAction(actions, PlaybackState.ACTION_PLAY_PAUSE);
        boolean canPause = hasAction(actions, PlaybackState.ACTION_PAUSE)
                || hasAction(actions, PlaybackState.ACTION_PLAY_PAUSE);
        if (actions == 0) {
            canPrev = true;
            canNext = true;
            canPlay = true;
            canPause = true;
        }
        boolean playing = state.getState() != PlaybackState.STATE_PAUSED;
        Bitmap immediate = artworkBitmap(metadata);
        int generation = ++artGeneration;
        if (immediate != null) {
            Bitmap copy = scaleArtwork(immediate);
            publish(new MediaSnapshot(true, playing, title, artist, copy, themeColor(copy), duration,
                    canPrev, canNext, canPlay, canPause));
            ensureUi();
            startHeartbeat();
            return;
        }
        String uri = artworkUri(metadata);
        publish(new MediaSnapshot(true, playing, title, artist, snapshot.artwork,
                snapshot.artwork == null ? DEFAULT_THEME : snapshot.themeColor, duration,
                canPrev, canNext, canPlay, canPause));
        ensureUi();
        startHeartbeat();
        if (uri == null) {
            publish(new MediaSnapshot(true, playing, title, artist, null, DEFAULT_THEME, duration,
                    canPrev, canNext, canPlay, canPause));
            return;
        }
        final String titleFinal = title;
        final String artistFinal = artist;
        final boolean playingFinal = playing;
        final long durationFinal = duration;
        final boolean prevFinal = canPrev;
        final boolean nextFinal = canNext;
        final boolean playFinal = canPlay;
        final boolean pauseFinal = canPause;
        new Thread(() -> {
            Bitmap loaded = loadArtwork(uri);
            Bitmap copy = scaleArtwork(loaded);
            int color = themeColor(copy);
            handler.post(() -> {
                if (generation != artGeneration) {
                    return;
                }
                publish(new MediaSnapshot(true, playingFinal, titleFinal, artistFinal, copy, color,
                        durationFinal, prevFinal, nextFinal, playFinal, pauseFinal));
            });
        }, "media-art").start();
    }

    private boolean isActiveState(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_PAUSED
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING;
    }

    private boolean hasAction(long actions, long flag) {
        return (actions & flag) != 0;
    }

    private String text(MediaMetadata metadata, String key) {
        if (metadata == null) {
            return "";
        }
        CharSequence value = metadata.getText(key);
        return value == null ? "" : value.toString();
    }

    private Bitmap artworkBitmap(MediaMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            return art;
        } catch (Exception e) {
            Log.w(TAG, "读取专辑图失败", e);
            return null;
        }
    }

    private String artworkUri(MediaMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        String uri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
        if (uri == null || uri.isEmpty()) {
            uri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
        }
        return (uri == null || uri.isEmpty()) ? null : uri;
    }

    private Bitmap loadArtwork(String uri) {
        try {
            return MediaStore.Images.Media.getBitmap(getContentResolver(), Uri.parse(uri));
        } catch (Exception e) {
            Log.w(TAG, "读取专辑图失败", e);
            return null;
        }
    }

    private Bitmap scaleArtwork(Bitmap source) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        int max = 480;
        int width = source.getWidth();
        int height = source.getHeight();
        float scale = Math.min(1f, max / (float) Math.max(width, height));
        int targetW = Math.max(1, Math.round(width * scale));
        int targetH = Math.max(1, Math.round(height * scale));
        Bitmap out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawBitmap(source, null, new Rect(0, 0, targetW, targetH), null);
        return out;
    }

    private int themeColor(Bitmap artwork) {
        if (artwork == null) {
            return DEFAULT_THEME;
        }
        int size = 8;
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = artwork.getPixel(
                        x * (artwork.getWidth() - 1) / (size - 1),
                        y * (artwork.getHeight() - 1) / (size - 1));
                if (Color.alpha(pixel) < 128) {
                    continue;
                }
                red += Color.red(pixel);
                green += Color.green(pixel);
                blue += Color.blue(pixel);
                count++;
            }
        }
        if (count == 0) {
            return DEFAULT_THEME;
        }
        return Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    private void publish(MediaSnapshot next) {
        snapshot = next;
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onMediaChanged(next);
        }
    }

    private void startHeartbeat() {
        handler.removeCallbacks(ensureRunnable);
        handler.postDelayed(ensureRunnable, 1500);
    }

    private void ensureUi() {
        if (!snapshot.active || MediaDisplayActivity.isAlive()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunchMs < 2000) {
            return;
        }
        if (taskService == null) {
            bindTaskService();
            return;
        }
        lastLaunchMs = now;
        String cmd = "am start --display 1 -n " + getPackageName() + "/.MediaDisplayActivity";
        try {
            boolean ok = taskService.executeShellCommand(cmd);
            if (!ok) {
                Log.w(TAG, "启动背屏媒体界面失败");
            }
        } catch (Exception e) {
            Log.e(TAG, "启动背屏媒体界面异常", e);
        }
    }

    private void requestFinishUi() {
        sendBroadcast(new Intent(ACTION_FINISH).setPackage(getPackageName()));
    }

    private void bindTaskService() {
        if (taskService != null) {
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku 未就绪");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "没有 Shizuku 权限");
                return;
            }
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "绑定 TaskService 失败", e);
        }
    }

    private void unbindTaskService() {
        if (taskService == null) {
            return;
        }
        try {
            Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
        } catch (Exception e) {
            Log.w(TAG, "解绑 TaskService 失败", e);
        }
        taskService = null;
    }
}
