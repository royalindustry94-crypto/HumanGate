package ai.leon.companion;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import ai.leon.companion.overlay.LeonOverlayService;
import ai.leon.companion.overlay.LeonPrefs;

/**
 * Brings Leon back after a reboot or an app update, but only when the user had him enabled and the
 * overlay permission is still granted. Some OEMs additionally require auto-start to be allowed by
 * hand; when that blocks the start we log it and leave the user to enable it, rather than retrying
 * around the restriction.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "LeonBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!new LeonPrefs(context).isEnabled()) return;
        if (!Settings.canDrawOverlays(context)) {
            Log.i(TAG, "Leon was enabled but the overlay permission is no longer granted.");
            return;
        }
        LeonOverlayService.start(context);
    }
}
