package ai.leon.companion.state;

/**
 * Leon's behavioural states. Each one owns a base pose and a behaviour profile; the controller
 * cross-fades between them so there is never a visible snap.
 */
public enum LeonState {
    IDLE("Idle"),
    LISTENING("Listening"),
    THINKING("Thinking"),
    SPEAKING("Speaking"),
    HAPPY("Happy"),
    SERIOUS("Serious"),
    SLEEPY("Sleepy"),
    /** Short reaction to being tapped; returns to the previous state on its own. */
    ATTENTION("Attention"),
    /** Compact form factor. Still breathes, blinks and drifts, just smaller and calmer. */
    MINIMISED("Minimised");

    public final String label;

    LeonState(String label) {
        this.label = label;
    }

    /** True for states that end by themselves rather than waiting for a new request. */
    public boolean isTransient() {
        return this == ATTENTION;
    }

    public static LeonState fromName(String name, LeonState fallback) {
        if (name == null) return fallback;
        for (LeonState s : values()) {
            if (s.name().equalsIgnoreCase(name.trim())) return s;
        }
        return fallback;
    }
}
