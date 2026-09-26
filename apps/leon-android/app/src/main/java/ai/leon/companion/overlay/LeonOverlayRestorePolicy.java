package ai.leon.companion.overlay;

/** Pure policy for when Leon's overlay may be restored after a restart or unlock event. */
final class LeonOverlayRestorePolicy {
    private LeonOverlayRestorePolicy() {
    }

    static boolean isTemporarilyHidden(long hiddenUntilMillis, long nowMillis) {
        return hiddenUntilMillis > nowMillis;
    }

    static boolean shouldRestoreOverlay(boolean enabled, boolean canDrawOverlays,
            boolean screenInteractive, boolean keyguardLocked,
            long hiddenUntilMillis, long nowMillis) {
        return enabled
                && canDrawOverlays
                && screenInteractive
                && !keyguardLocked
                && !isTemporarilyHidden(hiddenUntilMillis, nowMillis);
    }
}
