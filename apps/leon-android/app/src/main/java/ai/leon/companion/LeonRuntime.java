package ai.leon.companion;

import android.content.Context;

import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.anim.LeonVisemeBus;
import ai.leon.companion.overlay.LeonPrefs;
import ai.leon.companion.state.LeonConversationController;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;
import ai.leon.companion.voice.LeonSpeechController;
import ai.leon.companion.voice.LeonVoiceBackend;

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
    private final LeonVoiceBackend voiceBackend;
    private final LeonPrefs prefs;
    private final Context appContext;
    private LeonSpeechController speech;

    private LeonRuntime(Context context) {
        appContext = context.getApplicationContext();
        prefs = new LeonPrefs(appContext);
        conversation = new LeonConversationController(stateController, visemeBus);
        voiceBackend = new LeonVoiceBackend(conversation, prefs, visemeBus);
        conversation.setBackend(voiceBackend);
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

    /** The concrete voice backend, for the mic push-to-talk control that {@link #conversation()}'s
     *  generic {@link LeonConversationController.Backend} interface does not expose. */
    public LeonVoiceBackend voiceBackend() {
        return voiceBackend;
    }

    public LeonPrefs prefs() {
        return prefs;
    }

    /**
     * Lazily creates the process-wide voice output. Visual-emulator tests never touch this getter,
     * so they do not need a TTS engine merely to render Leon.
     */
    public synchronized LeonSpeechController speech() {
        if (speech == null) speech = new LeonSpeechController(appContext, stateController);
        return speech;
    }

    /** Registers a surface's animation controller so shared speech reaches it. */
    public void attachSurface(LeonAnimationController controller) {
        if (controller != null) visemeBus.attach(controller.lipSync());
    }

    public void detachSurface(LeonAnimationController controller) {
        if (controller != null) visemeBus.detach(controller.lipSync());
    }
}
