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
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final long POLL_MS = 1000L;
    private static final long KEEPER_MS = 3000L;
    /** 切歌时播放器会短暂上报停止，超过这个时长才认为没有媒体 */
    private static final long INACTIVE_GRACE_MS = 3000L;

    public interface Listener {
        void onMediaChanged(MediaSnapshot snapshot);
    }

    public static final class MediaSnapshot {
        public final boolean active;
        public final boolean playing;
        public final String title;
        public final String artist;
        public final String lyric;
        public final Bitmap artwork;
        public final int themeColor;
        public final long durationMs;
        public final boolean canPrev;
        public final boolean canNext;
        public final boolean canPlay;
        public final boolean canPause;

        MediaSnapshot(boolean active, boolean playing, String title, String artist, String lyric,
                Bitmap artwork, int themeColor, long durationMs, boolean canPrev, boolean canNext,
                boolean canPlay, boolean canPause) {
            this.active = active;
            this.playing = playing;
            this.title = title;
            this.artist = artist;
            this.lyric = lyric;
            this.artwork = artwork;
            this.themeColor = themeColor;
            this.durationMs = durationMs;
            this.canPrev = canPrev;
            this.canNext = canNext;
            this.canPlay = canPlay;
            this.canPause = canPause;
        }

        static MediaSnapshot inactive() {
            return new MediaSnapshot(false, false, "", "", "", null, DEFAULT_THEME, 0,
                    false, false, false, false);
        }

        boolean sameAs(MediaSnapshot other) {
            return other != null
                    && active == other.active
                    && playing == other.playing
                    && artwork == other.artwork
                    && themeColor == other.themeColor
                    && durationMs == other.durationMs
                    && canPrev == other.canPrev
                    && canNext == other.canNext
                    && canPlay == other.canPlay
                    && canPause == other.canPause
                    && title.equals(other.title)
                    && artist.equals(other.artist)
                    && lyric.equals(other.lyric);
        }
    }

    private static MediaDisplayService instance;
    private static volatile MediaSnapshot snapshot = MediaSnapshot.inactive();
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService shell = Executors.newSingleThreadExecutor();
    private MediaSessionManager sessionManager;
    private volatile MediaController controller;
    private ITaskService taskService;
    private boolean sessionsRegistered;
    private volatile boolean uiWanted;
    /** 用户滑走媒体界面后，背屏应显示原来的桌面 */
    private volatile boolean uiCollapsed;
    /** 收起期间在后台读背屏触摸事件，等待拉出手势 */
    private volatile Thread edgeWatcher;
    private boolean inactivePending;

    private String artworkKey = "";
    private Bitmap artwork;
    private int artworkColor = DEFAULT_THEME;
    private String loadingUri;

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
                if (!uiWanted) {
                    return;
                }
                if (uiCollapsed) {
                    keepCollapsed();
                } else {
                    launchUi();
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
            refreshState();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            refreshState();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    /** 部分播放器不回调元数据变化（例如把歌词写进标题），所以定时补拉一次 */
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshSessions();
            handler.postDelayed(this, POLL_MS);
        }
    };

    /** 小米背屏桌面会自己拉起并盖住界面，定时确认背屏顶部仍是本界面 */
    private final Runnable keeperRunnable = new Runnable() {
        @Override
        public void run() {
            if (!uiWanted) {
                return;
            }
            if (uiCollapsed) {
                keepCollapsed();
            } else {
                keepUiOnTop();
            }
            handler.postDelayed(this, KEEPER_MS);
        }
    };

    private final Runnable inactiveRunnable = () -> {
        inactivePending = false;
        publish(MediaSnapshot.inactive());
        stopUi();
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

    /** 按上次上报的位置和流逝时间推算当前进度 */
    public static long currentPosition() {
        MediaDisplayService service = instance;
        MediaController current = service == null ? null : service.controller;
        if (current == null) {
            return 0;
        }
        PlaybackState state = current.getPlaybackState();
        if (state == null) {
            return 0;
        }
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            float speed = state.getPlaybackSpeed() > 0 ? state.getPlaybackSpeed() : 1f;
            if (elapsed > 0) {
                position += (long) (elapsed * speed);
            }
        }
        return Math.max(0, position);
    }

    public static void skipToPrevious() {
        transport(controls -> controls.skipToPrevious());
    }

    public static void skipToNext() {
        transport(controls -> controls.skipToNext());
    }

    public static void playPause() {
        transport(controls -> {
            if (snapshot.playing) {
                controls.pause();
            } else {
                controls.play();
            }
        });
    }

    public static void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public static boolean isCollapsed() {
        MediaDisplayService service = instance;
        return service != null && service.uiCollapsed;
    }

    /** 收起时恢复背屏桌面；展开时关掉桌面并把媒体界面拉回背屏 */
    public static void setCollapsed(boolean collapsed) {
        MediaDisplayService service = instance;
        if (service == null) {
            return;
        }
        service.uiCollapsed = collapsed;
        service.handler.post(service::applyCollapsedWork);
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
        MediaController current = service == null ? null : service.controller;
        if (current == null) {
            return;
        }
        try {
            action.run(current.getTransportControls());
        } catch (Exception e) {
            Log.w(TAG, "发送播放控制失败", e);
        }
        service.handler.postDelayed(service::refreshState, 300);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
        sessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        bindTaskService();
        registerSessions();
        handler.postDelayed(pollRunnable, POLL_MS);
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
        handler.removeCallbacksAndMessages(null);
        unregisterSessions();
        if (controller != null) {
            controller.unregisterCallback(controllerCallback);
            controller = null;
        }
        publish(MediaSnapshot.inactive());
        stopUi();
        shell.execute(this::unbindTaskService);
        shell.shutdown();
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

    private void refreshSessions() {
        if (sessionManager == null) {
            return;
        }
        try {
            onSessionsChanged(sessionManager.getActiveSessions(
                    new ComponentName(this, NotificationService.class)));
        } catch (SecurityException e) {
            Log.w(TAG, "读取媒体会话失败", e);
        }
    }

    private void onSessionsChanged(List<MediaController> controllers) {
        MediaController next = pick(controllers == null ? new ArrayList<>() : controllers);
        if (!sameSession(controller, next)) {
            if (controller != null) {
                controller.unregisterCallback(controllerCallback);
            }
            controller = next;
            if (controller != null) {
                controller.registerCallback(controllerCallback, handler);
            }
        }
        refreshState();
    }

    private MediaController pick(List<MediaController> controllers) {
        MediaController paused = null;
        for (MediaController item : controllers) {
            PlaybackState state = item.getPlaybackState();
            if (state == null) {
                continue;
            }
            if (isPlayingState(state.getState())) {
                return item;
            }
            if (paused == null && isActiveState(state.getState()) && item.getMetadata() != null) {
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

    private void refreshState() {
        MediaController current = controller;
        PlaybackState state = current == null ? null : current.getPlaybackState();
        MediaMetadata metadata = current == null ? null : current.getMetadata();
        if (state == null || metadata == null || !isActiveState(state.getState())) {
            if (snapshot.active && !inactivePending) {
                inactivePending = true;
                handler.postDelayed(inactiveRunnable, INACTIVE_GRACE_MS);
            }
            return;
        }
        inactivePending = false;
        handler.removeCallbacks(inactiveRunnable);

        String title = text(metadata, MediaMetadata.METADATA_KEY_TITLE);
        String artist = text(metadata, MediaMetadata.METADATA_KEY_ARTIST);
        if (artist.isEmpty()) {
            artist = text(metadata, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        String album = text(metadata, MediaMetadata.METADATA_KEY_ALBUM);
        String lyric = currentLyric(metadata, state, title, artist, album);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
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
        boolean playing = isPlayingState(state.getState());
        updateArtwork(current.getPackageName(), metadata);

        publish(new MediaSnapshot(true, playing, title, artist, lyric, artwork, artworkColor, duration,
                canPrev, canNext, canPlay, canPause));
        if (!uiWanted) {
            startUi();
        }
    }

    /** 专辑图按内容取样做键，同一张图不重复缩放和取色 */
    private void updateArtwork(String packageName, MediaMetadata metadata) {
        Bitmap source = artworkBitmap(metadata);
        if (source != null) {
            String key = packageName + "|bmp|" + bitmapKey(source);
            if (key.equals(artworkKey)) {
                return;
            }
            artworkKey = key;
            artwork = scaleArtwork(source);
            artworkColor = themeColor(artwork);
            return;
        }
        String uri = artworkUri(metadata);
        if (uri == null) {
            String key = packageName + "|none|" + text(metadata, MediaMetadata.METADATA_KEY_ALBUM);
            if (!key.equals(artworkKey)) {
                artworkKey = key;
                artwork = null;
                artworkColor = DEFAULT_THEME;
            }
            return;
        }
        String key = packageName + "|uri|" + uri;
        if (key.equals(artworkKey) || uri.equals(loadingUri)) {
            return;
        }
        loadingUri = uri;
        new Thread(() -> {
            Bitmap scaled = scaleArtwork(loadArtwork(uri));
            int color = themeColor(scaled);
            handler.post(() -> {
                if (!uri.equals(loadingUri)) {
                    return;
                }
                loadingUri = null;
                artworkKey = key;
                artwork = scaled;
                artworkColor = color;
                refreshState();
            });
        }, "media-art").start();
    }

    private static String bitmapKey(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        long hash = width * 31L + height;
        for (int i = 1; i <= 4; i++) {
            for (int j = 1; j <= 4; j++) {
                hash = hash * 31 + bitmap.getPixel(width * i / 5, height * j / 5);
            }
        }
        return width + "x" + height + ":" + hash;
    }

    private static boolean isPlayingState(int state) {
        return state == PlaybackState.STATE_PLAYING
                || state == PlaybackState.STATE_BUFFERING
                || state == PlaybackState.STATE_CONNECTING
                || state == PlaybackState.STATE_FAST_FORWARDING
                || state == PlaybackState.STATE_REWINDING
                || state == PlaybackState.STATE_SKIPPING_TO_NEXT
                || state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS
                || state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
    }

    private static boolean isActiveState(int state) {
        return isPlayingState(state) || state == PlaybackState.STATE_PAUSED;
    }

    private boolean hasAction(long actions, long flag) {
        return (actions & flag) != 0;
    }

    /** 当前一句歌词。播放器可能写在元数据或播放状态的附加字段里 */
    private String currentLyric(MediaMetadata metadata, PlaybackState state, String title, String artist,
            String album) {
        String fromState = lyricInBundle(state == null ? null : state.getExtras(), title, artist, album);
        if (!fromState.isEmpty()) {
            return fromState;
        }
        if (metadata != null) {
            for (String key : metadata.keySet()) {
                if (key == null || !key.toLowerCase(Locale.US).contains("lyric")) {
                    continue;
                }
                String value = usableLyric(text(metadata, key), title, artist, album);
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        String description = usableLyric(text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
                title, artist, album);
        if (!description.isEmpty()) {
            return description;
        }
        return usableLyric(text(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE), title, artist, album);
    }

    private static String lyricInBundle(Bundle bundle, String title, String artist, String album) {
        if (bundle == null) {
            return "";
        }
        for (String key : bundle.keySet()) {
            if (key == null || !key.toLowerCase(Locale.US).contains("lyric")) {
                continue;
            }
            Object value = bundle.get(key);
            if (!(value instanceof CharSequence)) {
                continue;
            }
            String lyric = usableLyric(value.toString(), title, artist, album);
            if (!lyric.isEmpty()) {
                return lyric;
            }
        }
        return "";
    }

    private static String usableLyric(String value, String title, String artist, String album) {
        if (value == null) {
            return "";
        }
        String lyric = value.trim();
        if (lyric.isEmpty() || lyric.equals(title) || lyric.equals(artist) || lyric.equals(album)) {
            return "";
        }
        return lyric;
    }

    private String text(MediaMetadata metadata, String key) {
        if (metadata == null) {
            return "";
        }
        CharSequence value = metadata.getText(key);
        return value == null ? "" : value.toString();
    }

    private Bitmap artworkBitmap(MediaMetadata metadata) {
        try {
            Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (art == null && metadata.getDescription() != null) {
                art = metadata.getDescription().getIconBitmap();
            }
            return art;
        } catch (Exception e) {
            Log.w(TAG, "读取专辑图失败", e);
            return null;
        }
    }

    private String artworkUri(MediaMetadata metadata) {
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
        if (next.sameAs(snapshot)) {
            return;
        }
        snapshot = next;
        for (Listener listener : listeners) {
            listener.onMediaChanged(next);
        }
    }

    private void startUi() {
        uiCollapsed = false;
        uiWanted = true;
        launchUi();
        handler.removeCallbacks(keeperRunnable);
        handler.postDelayed(keeperRunnable, KEEPER_MS);
    }

    private void stopUi() {
        boolean wasWanted = uiWanted;
        uiWanted = false;
        uiCollapsed = false;
        stopEdgeWatch();
        handler.removeCallbacks(keeperRunnable);
        sendBroadcast(new Intent(ACTION_FINISH).setPackage(getPackageName()));
        if (!wasWanted) {
            return;
        }
        ITaskService service = taskService;
        if (service == null || shell.isShutdown()) {
            return;
        }
        shell.execute(() -> {
            try {
                service.enableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "恢复背屏桌面失败", e);
            }
        });
    }

    private void launchUi() {
        ITaskService service = taskService;
        if (service == null) {
            bindTaskService();
            return;
        }
        if (shell.isShutdown()) {
            return;
        }
        shell.execute(() -> {
            if (!uiWanted || uiCollapsed) {
                return;
            }
            try {
                service.disableSubScreenLauncher();
                if (!uiWanted || uiCollapsed) {
                    return;
                }
                service.executeShellCommand(launchCommand());
                SystemClock.sleep(600);
                if (!uiWanted || uiCollapsed) {
                    return;
                }
                moveUiToRearIfNeeded(service);
            } catch (Exception e) {
                Log.e(TAG, "启动背屏媒体界面失败", e);
            }
        });
    }

    private void applyCollapsedWork() {
        if (taskService == null) {
            bindTaskService();
            return;
        }
        if (uiCollapsed) {
            showLauncher();
            startEdgeWatch();
        } else {
            stopEdgeWatch();
            if (uiWanted) {
                restoreMediaFront();
            }
        }
    }

    /** 收起后只启动背屏桌面。背屏上不留 MRSS 的窗口，桌面手势完全不受影响 */
    private void showLauncher() {
        if (shell.isShutdown()) {
            return;
        }
        shell.execute(() -> {
            if (!uiCollapsed || !uiWanted) {
                return;
            }
            ITaskService service = taskService;
            if (service == null) {
                return;
            }
            try {
                service.enableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "收起后恢复背屏桌面失败", e);
            }
        });
    }

    /** 后台读背屏触摸事件。检测到从右边缘向左滑，就拉出媒体界面 */
    private void startEdgeWatch() {
        if (edgeWatcher != null) {
            return;
        }
        Thread[] self = new Thread[1];
        Thread watcher = new Thread(() -> {
            while (uiWanted && uiCollapsed && edgeWatcher == self[0]) {
                ITaskService service = taskService;
                if (service == null) {
                    SystemClock.sleep(500);
                    continue;
                }
                long started = SystemClock.elapsedRealtime();
                boolean swiped;
                try {
                    swiped = service.waitRearEdgeSwipe(rearRotation(), 15_000L);
                } catch (Exception e) {
                    Log.w(TAG, "监听背屏手势失败", e);
                    swiped = false;
                }
                if (swiped && uiWanted && uiCollapsed && edgeWatcher == self[0]) {
                    Log.d(TAG, "rear edge swipe: expand media ui");
                    handler.post(() -> {
                        if (uiWanted && uiCollapsed) {
                            setCollapsed(false);
                        }
                    });
                    break;
                }
                if (SystemClock.elapsedRealtime() - started < 1000) {
                    SystemClock.sleep(1000);
                }
            }
            handler.post(() -> {
                if (edgeWatcher == self[0]) {
                    edgeWatcher = null;
                }
            });
        }, "MediaEdgeWatch");
        self[0] = watcher;
        edgeWatcher = watcher;
        watcher.start();
    }

    private void stopEdgeWatch() {
        if (edgeWatcher == null) {
            return;
        }
        edgeWatcher = null;
        ITaskService service = taskService;
        if (service == null) {
            return;
        }
        new Thread(() -> {
            try {
                service.cancelRearEdgeWatch();
            } catch (Exception e) {
                Log.w(TAG, "停止背屏手势监听失败", e);
            }
        }, "MediaEdgeCancel").start();
    }

    private int rearRotation() {
        DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(1);
        return display == null ? 0 : display.getRotation();
    }

    private void restoreMediaFront() {
        if (shell.isShutdown()) {
            return;
        }
        shell.execute(() -> {
            if (uiCollapsed || !uiWanted) {
                return;
            }
            ITaskService service = taskService;
            if (service == null) {
                return;
            }
            try {
                service.disableSubScreenLauncher();
                if (uiCollapsed || !uiWanted) {
                    return;
                }
                service.executeShellCommand(launchCommand());
            } catch (Exception e) {
                Log.w(TAG, "展开媒体界面失败", e);
            }
        });
    }

    /** 收起期间背屏为空时补启动桌面；用户在背屏打开的其他应用不动 */
    private void keepCollapsed() {
        startEdgeWatch();
        if (shell.isShutdown()) {
            return;
        }
        shell.execute(() -> {
            if (!uiWanted || !uiCollapsed) {
                return;
            }
            ITaskService service = taskService;
            if (service == null) {
                return;
            }
            try {
                if (service.getForegroundAppOnDisplay(1) != null) {
                    return;
                }
                service.enableSubScreenLauncher();
            } catch (Exception e) {
                Log.w(TAG, "收起后保持背屏桌面失败", e);
            }
        });
    }

    private void keepUiOnTop() {
        ITaskService service = taskService;
        if (service == null) {
            bindTaskService();
            return;
        }
        if (shell.isShutdown()) {
            return;
        }
        shell.execute(() -> {
            if (!uiWanted || uiCollapsed) {
                return;
            }
            try {
                String front = service.getForegroundAppOnDisplay(1);
                if (front != null && front.startsWith(getPackageName() + ":")) {
                    return;
                }
                if (!uiWanted || uiCollapsed) {
                    return;
                }
                service.disableSubScreenLauncher();
                if (!uiWanted || uiCollapsed) {
                    return;
                }
                service.executeShellCommand(launchCommand());
                SystemClock.sleep(600);
                if (!uiWanted || uiCollapsed) {
                    return;
                }
                moveUiToRearIfNeeded(service);
            } catch (Exception e) {
                Log.w(TAG, "确认背屏媒体界面失败", e);
            }
        });
    }

    /** am start --display 1 偶尔会落到主屏，确认后纠正 */
    private void moveUiToRearIfNeeded(ITaskService service) {
        try {
            String front = service.getForegroundAppOnDisplay(1);
            if (front != null && front.startsWith(getPackageName() + ":")) {
                return;
            }
            String list = service.executeShellCommandWithResult(
                    "am stack list | grep " + getPackageName() + "/.MediaDisplayActivity");
            if (list == null || list.isEmpty()) {
                return;
            }
            Matcher matcher = Pattern.compile("taskId=(\\d+)").matcher(list);
            if (matcher.find()) {
                service.moveTaskToDisplay(Integer.parseInt(matcher.group(1)), 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "纠正背屏落屏失败", e);
        }
    }

    private String launchCommand() {
        return "am start --display 1 -n " + getPackageName() + "/.MediaDisplayActivity";
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
