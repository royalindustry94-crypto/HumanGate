package ai.leon.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LeonOverlayLifecycleContractTest {
    @Test
    public void stickyRestartRespectsPersistedEnabledFlag() throws Exception {
        String overlay = SourceContracts.source("ai/leon/companion/overlay/LeonOverlayService.java");

        assertTrue(overlay.contains("if (!prefs.isEnabled()) {\n            stopSelf();\n            return;"));
        assertTrue(overlay.contains("if (!prefs.isEnabled()) {\n            stopSelf();\n            return START_NOT_STICKY;"));
        assertTrue(overlay.contains("restoreOverlayIfEligible(now);"));
    }

    @Test
    public void screenOffPausesWithoutTearingDownPersistentOverlayState() throws Exception {
        String overlay = SourceContracts.source("ai/leon/companion/overlay/LeonOverlayService.java");
        int screenOff = overlay.indexOf("if (Intent.ACTION_SCREEN_OFF.equals(action)) {");
        int screenOn = overlay.indexOf("if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {");

        assertTrue(screenOff >= 0);
        assertTrue(screenOn > screenOff);

        String screenOffBlock = overlay.substring(screenOff, screenOn);
        assertTrue(screenOffBlock.contains("if (characterView != null) characterView.setPaused(true);"));
        assertFalse(screenOffBlock.contains("teardownOverlay();"));
        assertFalse(screenOffBlock.contains("prefs.setEnabled(false);"));
        assertFalse(screenOffBlock.contains("prefs.savePosition("));
    }

    @Test
    public void unlockPathCanResumeOrRecreateTheOverlayHost() throws Exception {
        String overlay = SourceContracts.source("ai/leon/companion/overlay/LeonOverlayService.java");

        assertTrue(overlay.contains("if (host == null) {\n            showOverlay();\n            return;\n        }\n        host.setVisibility(View.VISIBLE);"));
        assertTrue(overlay.contains("animation.resetBehaviours();"));
        assertTrue(overlay.contains("animation.triggerBlink();"));
    }

    @Test
    public void workflowAndAuditChecklistBothMentionLockUnlockPersistence() throws Exception {
        String workflow = SourceContracts.file(".github/workflows/leon-android-apk.yml");
        String audit = SourceContracts.file("docs/LEON_ANDROID_AUDIT.md");

        assertTrue(workflow.contains("## Leon lock/unlock device checklist"));
        assertTrue(workflow.contains("verify Leon is visible automatically in the same position"));
        assertTrue(audit.contains("1. enable Leon;"));
        assertTrue(audit.contains("6. verify Leon is visible automatically in the same position."));
    }
}
