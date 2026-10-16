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

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/**
 * 控制中心开关：背屏媒体显示。
 */
public class MediaDisplayTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile(MediaDisplayService.isModeEnabled(this));
    }

    @Override
    public void onClick() {
        super.onClick();
        unlockAndRun(() -> {
            if (MediaDisplayService.isModeEnabled(this)) {
                MediaDisplayService.setModeEnabled(this, false);
                stopService(new Intent(this, MediaDisplayService.class));
                updateTile(false);
                MediaDisplayService.refreshTiles(this);
                return;
            }
            if (!isNotificationListenerEnabled()) {
                closePanel();
                Toast.makeText(this, R.string.toast_need_notification_listener, Toast.LENGTH_LONG).show();
                Intent settings = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(settings);
                return;
            }
            if (RearScreenBroadcastReceiver.hasActiveTask()) {
                closePanel();
                Toast.makeText(this, castBlockMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            MediaDisplayService.setModeEnabled(this, true);
            Intent service = new Intent(this, MediaDisplayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
            updateTile(true);
            MediaDisplayService.refreshTiles(this);
        });
    }

    private void updateTile(boolean enabled) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private String castBlockMessage() {
        String last = SwitchToRearTileService.getLastMovedTask();
        if (last != null && last.contains(":")) {
            String packageName = last.split(":")[0];
            return getString(R.string.toast_please_switch_back, appName(packageName));
        }
        return getString(R.string.toast_cast_blocks_media);
    }

    private String appName(String packageName) {
        try {
            PackageManager manager = getPackageManager();
            CharSequence label = manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0));
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Exception ignored) {
        }
        return packageName;
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) {
            return false;
        }
        String packageName = getPackageName();
        for (String name : flat.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(name);
            if (component != null && packageName.equals(component.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    private void closePanel() {
        try {
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {
        }
    }
}
