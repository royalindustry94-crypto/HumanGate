package ai.leon.companion;

import android.content.Context;

import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.anim.LeonVisemeBus;
import ai.leon.companion.overlay.LeonPrefs;
import ai.leon.companion.state.LeonConversationController;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

/**
 * Process-wide singleton holding the one thing the overlay and the control centre must agree on:
 * which state Leon is in.
 *
 * <p>Each surface renders its own {@link ai.leon.companion.rig.Rig} and its own
 * {@link LeonAnimationController} — two independent characters on screen at once is fine — but they
 * share this {@link LeonStateController}, so the control centre's test buttons drive the real state
 * machine the overlay is animating rather than a copy of it.
 *
 * <p>Speech goes through {@link #visemeBus()}, which fans one stream out to every attached surface.
 */
public final class LeonRuntime {
    private static volatile LeonRuntime instance;

    private final LeonStateController stateController = new LeonStateController();
    private final LeonVisemeBus visemeBus = new LeonVisemeBus();
    private final LeonConversationController conversation;
    private final LeonPrefs prefs;

    private LeonRuntime(Context context) {
        prefs = new LeonPrefs(context);
        conversation = new LeonConversationController(stateController, visemeBus);
        stateController.adoptPersisted(prefs.state(), prefs.restoreState());
        stateController.addListener(new LeonStateController.Listener() {
            @Override
            public void onLeonStateChanged(LeonState previous, LeonState current) {
                prefs.saveState(current, stateController.restoreState());
            }
        });
    }

    public static LeonRuntime get(Context context) {
        LeonRuntime local = instance;
        if (local == null) {
            synchronized (LeonRuntime.class) {
                local = instance;
                if (local == null) {
                    local = new LeonRuntime(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    public LeonStateController states() {
        return stateController;
    }

    public LeonVisemeBus visemeBus() {
        return visemeBus;
    }

    public LeonConversationController conversation() {
        return conversation;
    }

    public LeonPrefs prefs() {
        return prefs;
    }

    /** Registers a surface's animation controller so shared speech reaches it. */
    public void attachSurface(LeonAnimationController controller) {
        if (controller != null) visemeBus.attach(controller.lipSync());
    }

    public void detachSurface(LeonAnimationController controller) {
        if (controller != null) visemeBus.detach(controller.lipSync());
    }
}
