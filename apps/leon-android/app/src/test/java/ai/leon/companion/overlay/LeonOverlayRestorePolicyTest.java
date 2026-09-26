package ai.leon.companion.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LeonOverlayRestorePolicyTest {
    private static final long NOW = 1_726_000_000_000L;

    @Test
    public void overlayRestoresWhenEnabledVisibleAndUnlocked() {
        assertTrue(LeonOverlayRestorePolicy.shouldRestoreOverlay(
                true, true, true, false, 0L, NOW));
    }

    @Test
    public void disabledLeonDoesNotRestoreOnRestartOrUnlock() {
        assertFalse(LeonOverlayRestorePolicy.shouldRestoreOverlay(
                false, true, true, false, 0L, NOW));
    }

    @Test
    public void temporaryHideWinsAcrossUnlock() {
        assertTrue(LeonOverlayRestorePolicy.isTemporarilyHidden(NOW + 5_000L, NOW));
        assertFalse(LeonOverlayRestorePolicy.shouldRestoreOverlay(
                true, true, true, false, NOW + 5_000L, NOW));
    }

    @Test
    public void lockedOrSleepingDeviceDefersRestoreUntilUsable() {
        assertFalse(LeonOverlayRestorePolicy.shouldRestoreOverlay(
                true, true, false, false, 0L, NOW));
        assertFalse(LeonOverlayRestorePolicy.shouldRestoreOverlay(
                true, true, true, true, 0L, NOW));
    }

    @Test
    public void overlayPermissionStillRequiredAfterStickyRestart() {
        assertFalse(LeonOverlayRestorePolicy.shouldRestoreOverlay(
                true, false, true, false, 0L, NOW));
    }
}
