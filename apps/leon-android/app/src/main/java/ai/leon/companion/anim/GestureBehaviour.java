package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

import java.util.Random;

/**
 * Conversational hand gestures on the screen-right arm, which the rig layers in front of the torso.
 * A gesture is an envelope (rise, hold with a small secondary beat, fall) driving the arm, elbow and
 * hand channels, plus a matching head accent — so the hand and the head move together the way they
 * do in speech.
 */
public final class GestureBehaviour implements LeonBehaviour {
    /** The shapes a gesture can take. Which one fires is chosen per gesture. */
    public enum Kind {
        /** Small open-hand beat, the default while speaking. */
        BEAT(0.55f, 0.45f, 0.35f, 1.15f),
        /** Broader emphasis, used sparingly. */
        EMPHASIS(0.85f, 0.7f, 0.6f, 1.5f),
        /** Hand towards the chin. Held much longer; THINKING uses it as a pose. */
        CHIN(0.95f, 0.95f, 0.15f, 3.2f),
        /** Point/acknowledge, used by ATTENTION and HAPPY. */
        POINT(0.7f, 0.25f, 0.9f, 1.0f);

        final float raise;
        final float elbow;
        final float extend;
        final float duration;

        Kind(float raise, float elbow, float extend, float duration) {
            this.raise = raise;
            this.elbow = elbow;
            this.extend = extend;
            this.duration = duration;
        }
    }

    private final Random random;

    private float autoIntervalMin = 4f;
    private float autoIntervalMax = 9f;
    private boolean autoEnabled;
    private float timeToAuto;

    private Kind kind = Kind.BEAT;
    private float elapsed = -1f;
    private float duration;
    private boolean holdIndefinitely;

    public GestureBehaviour(long seed) {
        random = new Random(seed);
        scheduleAuto();
    }

    /** Enables spontaneous gestures, e.g. while SPEAKING. */
    public void setAutoGesture(boolean enabled, float minSeconds, float maxSeconds) {
        this.autoEnabled = enabled;
        this.autoIntervalMin = Math.max(0.6f, minSeconds);
        this.autoIntervalMax = Math.max(this.autoIntervalMin + 0.5f, maxSeconds);
        if (enabled && timeToAuto <= 0f) scheduleAuto();
    }

    /** Fires a gesture now. */
    public void trigger(Kind kind) {
        trigger(kind, false);
    }

    /**
     * @param hold when true the gesture stays at full extension until {@link #release()}, which is
     *             how THINKING holds a hand near the chin for as long as it is thinking.
     */
    public void trigger(Kind kind, boolean hold) {
        this.kind = kind == null ? Kind.BEAT : kind;
        this.duration = this.kind.duration;
        this.holdIndefinitely = hold;
        this.elapsed = 0f;
    }

    /** Ends a held gesture, letting it fall back to rest. */
    public void release() {
        if (holdIndefinitely && elapsed >= 0f) {
            holdIndefinitely = false;
            // Resume at the start of the fall-off so the arm lowers instead of snapping.
            elapsed = duration * 0.62f;
        }
    }

    public boolean isGesturing() {
        return elapsed >= 0f;
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        if (elapsed < 0f) {
            if (!autoEnabled) return;
            timeToAuto -= dt;
            if (timeToAuto > 0f) return;
            trigger(random.nextFloat() < 0.25f ? Kind.EMPHASIS : Kind.BEAT);
            scheduleAuto();
        }

        if (holdIndefinitely) {
            elapsed = Math.min(elapsed + dt, duration * 0.6f);
        } else {
            elapsed += dt;
            if (elapsed > duration) {
                elapsed = -1f;
                return;
            }
        }

        float envelope = envelope(elapsed / duration);
        if (weight <= 0f) return;

        float w = envelope * weight;
        pose.add(LeonChannel.HAND_R_RAISE, kind.raise * w);
        pose.add(LeonChannel.ELBOW_R, kind.elbow * w);
        pose.add(LeonChannel.ARM_R_SWING, kind.extend * w * 0.5f);
        pose.add(LeonChannel.SHOULDER_R, kind.raise * w * 0.4f);
        // Gestures pull the torso and head with them, which is what makes them read as intentional.
        pose.add(LeonChannel.TORSO_TWIST, -kind.extend * w * 0.22f);
        if (kind == Kind.CHIN) {
            pose.add(LeonChannel.HEAD_PITCH, w * 0.18f);
            pose.add(LeonChannel.HEAD_ROLL, w * 0.16f);
        } else {
            pose.add(LeonChannel.HEAD_PITCH, -w * 0.1f);
            pose.add(LeonChannel.HEAD_YAW, -kind.extend * w * 0.14f);
        }
    }

    /** Gesture extension over its normalised duration: rise, hold with a secondary beat, fall. */
    public static float envelope(float t) {
        float x = Mathx.clamp01(t);
        if (x < 0.22f) return Mathx.easeOut(x / 0.22f);
        if (x < 0.62f) {
            float hold = (x - 0.22f) / 0.4f;
            return 0.88f + 0.12f * (float) Math.cos(hold * Math.PI * 2.0);
        }
        return 1f - Mathx.smoothstep((x - 0.62f) / 0.38f);
    }

    private void scheduleAuto() {
        timeToAuto = autoIntervalMin + random.nextFloat() * (autoIntervalMax - autoIntervalMin);
    }

    @Override
    public void reset() {
        elapsed = -1f;
        holdIndefinitely = false;
        scheduleAuto();
    }
}
