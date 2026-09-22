package ai.leon.companion;

import android.content.Intent;

import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.state.LeonState;

/** Debug-only deterministic hooks used by the GitHub Android-emulator visual gate. */
public final class DebugTestHooks {
    public static final String EXTRA_TEST_POSE = "ai.leon.companion.TEST_POSE";
    public static final String EXTRA_DISABLE_RANDOM = "ai.leon.companion.DISABLE_RANDOM";
    public static final String EXTRA_VISUAL_HOST = "ai.leon.companion.VISUAL_HOST";
    public static final String EXTRA_OVERLAY_HOST = "ai.leon.companion.OVERLAY_HOST";

    private DebugTestHooks() {}

    public static void apply(Intent intent, LeonAnimationController controller) {
        if (!BuildConfig.DEBUG || intent == null || controller == null) return;
        if (intent.getBooleanExtra(EXTRA_DISABLE_RANDOM, false)) {
            // Start every CI screenshot from the same behaviour phase. The visual validator does
            // not rely on exact pixel identity after this point; it checks complete body coverage.
            controller.resetBehaviours();
            controller.update(0f);
        }
    }

    public static LeonState requestedState(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) return null;
        String requested = intent.getStringExtra(EXTRA_TEST_POSE);
        if (requested == null || requested.trim().isEmpty()) return null;
        String key = requested.trim();
        for (LeonState state : LeonState.values()) {
            if (state.name().equalsIgnoreCase(key) || state.label.equalsIgnoreCase(key)) {
                return state;
            }
        }
        return null;
    }

    public static boolean isVisualHost(Intent intent) {
        return BuildConfig.DEBUG && intent != null
                && intent.getBooleanExtra(EXTRA_VISUAL_HOST, false);
    }

    public static boolean isOverlayHost(Intent intent) {
        return BuildConfig.DEBUG && intent != null
                && intent.getBooleanExtra(EXTRA_OVERLAY_HOST, false);
    }
}
