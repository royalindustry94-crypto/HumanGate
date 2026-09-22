package ai.leon.companion.anim;

/**
 * Anything that can consume a stream of mouth shapes. A future speech pipeline talks to this and
 * nothing else — it never needs a reference to a rig, a view or a state machine.
 *
 * <p>Implemented by {@link LeonLipSyncController} for a single character instance and by
 * {@link LeonVisemeBus} when the same speech must drive several (the overlay and the control
 * centre's preview, for example).
 */
public interface LeonVisemeSink {
    /**
     * @param viseme     the mouth shape to move to
     * @param intensity  0..1 loudness, so a quiet syllable moves the mouth less
     * @param durationMs how long this shape is held before the next event
     */
    void onViseme(Viseme viseme, float intensity, long durationMs);

    /** Abandons any pending speech and lets the mouth return to the current expression. */
    void stop();
}
