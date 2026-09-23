package ai.leon.companion.anim;

import ai.leon.companion.state.LeonState;
import ai.leon.companion.util.Mathx;

/**
 * Everything a state changes about how Leon moves: the pose he settles into and how each behaviour
 * is tuned. Profiles interpolate field by field, so a state change re-tunes breathing, blink rate,
 * drift and gesture frequency gradually instead of switching them at one frame boundary.
 */
public final class LeonStateProfile {
    public final LeonPose basePose = new LeonPose();

    /** Seconds to cross-fade into this profile. */
    public float transitionSeconds = 0.55f;

    public float breathRate = 13f;
    public float breathDepth = 1f;
    public float breathWeight = 1f;

    public float blinkWeight = 1f;
    public float blinkMin = 2.6f;
    public float blinkMax = 6.5f;

    public float driftWeight = 1f;
    public float driftAmount = 1f;
    public float glanceMin = 4.5f;
    public float glanceMax = 11f;
    public float focusYaw;
    public float focusPitch;

    public float gazeWeight = 1f;
    public float gazeRange = 1f;
    public float gazeHoldMin = 0.7f;
    public float gazeHoldMax = 2.6f;
    public float gazeFocusX;
    public float gazeFocusY;

    public float postureWeight = 1f;
    public float postureAmount = 1f;
    public float postureShiftMin = 7f;
    public float postureShiftMax = 16f;

    public float gestureWeight = 1f;
    public float gestureAuto;
    public float gestureMin = 4f;
    public float gestureMax = 9f;

    public float nodWeight = 1f;
    public float nodAuto;
    public float nodMin = 5f;
    public float nodMax = 12f;

    public float walkWeight = 1f;
    public float walkAuto;
    public float walkMin = 45f;
    public float walkMax = 100f;

    /** {@code out = lerp(a, b, t)} across the base pose and every tuning field. */
    public static void lerp(LeonStateProfile a, LeonStateProfile b, float t, LeonStateProfile out) {
        float k = Mathx.clamp01(t);
        LeonPose.lerp(a.basePose, b.basePose, k, out.basePose);
        out.transitionSeconds = Mathx.lerp(a.transitionSeconds, b.transitionSeconds, k);
        out.breathRate = Mathx.lerp(a.breathRate, b.breathRate, k);
        out.breathDepth = Mathx.lerp(a.breathDepth, b.breathDepth, k);
        out.breathWeight = Mathx.lerp(a.breathWeight, b.breathWeight, k);
        out.blinkWeight = Mathx.lerp(a.blinkWeight, b.blinkWeight, k);
        out.blinkMin = Mathx.lerp(a.blinkMin, b.blinkMin, k);
        out.blinkMax = Mathx.lerp(a.blinkMax, b.blinkMax, k);
        out.driftWeight = Mathx.lerp(a.driftWeight, b.driftWeight, k);
        out.driftAmount = Mathx.lerp(a.driftAmount, b.driftAmount, k);
        out.glanceMin = Mathx.lerp(a.glanceMin, b.glanceMin, k);
        out.glanceMax = Mathx.lerp(a.glanceMax, b.glanceMax, k);
        out.focusYaw = Mathx.lerp(a.focusYaw, b.focusYaw, k);
        out.focusPitch = Mathx.lerp(a.focusPitch, b.focusPitch, k);
        out.gazeWeight = Mathx.lerp(a.gazeWeight, b.gazeWeight, k);
        out.gazeRange = Mathx.lerp(a.gazeRange, b.gazeRange, k);
        out.gazeHoldMin = Mathx.lerp(a.gazeHoldMin, b.gazeHoldMin, k);
        out.gazeHoldMax = Mathx.lerp(a.gazeHoldMax, b.gazeHoldMax, k);
        out.gazeFocusX = Mathx.lerp(a.gazeFocusX, b.gazeFocusX, k);
        out.gazeFocusY = Mathx.lerp(a.gazeFocusY, b.gazeFocusY, k);
        out.postureWeight = Mathx.lerp(a.postureWeight, b.postureWeight, k);
        out.postureAmount = Mathx.lerp(a.postureAmount, b.postureAmount, k);
        out.postureShiftMin = Mathx.lerp(a.postureShiftMin, b.postureShiftMin, k);
        out.postureShiftMax = Mathx.lerp(a.postureShiftMax, b.postureShiftMax, k);
        out.gestureWeight = Mathx.lerp(a.gestureWeight, b.gestureWeight, k);
        out.gestureAuto = Mathx.lerp(a.gestureAuto, b.gestureAuto, k);
        out.gestureMin = Mathx.lerp(a.gestureMin, b.gestureMin, k);
        out.gestureMax = Mathx.lerp(a.gestureMax, b.gestureMax, k);
        out.nodWeight = Mathx.lerp(a.nodWeight, b.nodWeight, k);
        out.nodAuto = Mathx.lerp(a.nodAuto, b.nodAuto, k);
        out.nodMin = Mathx.lerp(a.nodMin, b.nodMin, k);
        out.nodMax = Mathx.lerp(a.nodMax, b.nodMax, k);
        out.walkWeight = Mathx.lerp(a.walkWeight, b.walkWeight, k);
        out.walkAuto = Mathx.lerp(a.walkAuto, b.walkAuto, k);
        out.walkMin = Mathx.lerp(a.walkMin, b.walkMin, k);
        out.walkMax = Mathx.lerp(a.walkMax, b.walkMax, k);
    }

    public LeonStateProfile copyFrom(LeonStateProfile other) {
        lerp(other, other, 0f, this);
        return this;
    }

    /** Builds the profile for a state. One place to tune how each state feels. */
    public static LeonStateProfile forState(LeonState state) {
        LeonStateProfile p = new LeonStateProfile();
        LeonPose pose = p.basePose;
        switch (state) {
            case IDLE:
                // Only IDLE wanders -- Leon should stay put and attentive in every other state.
                p.walkAuto = 1f;
                p.walkMin = 18f;
                p.walkMax = 35f;
                pose.set(LeonChannel.AURA, 0.18f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 1f);
                pose.set(LeonChannel.ELBOW_L, 0.12f);
                pose.set(LeonChannel.ELBOW_R, 0.12f);
                break;

            case LISTENING:
                // Squared up to the user, quieter body, eyes held near centre, periodic nods.
                p.transitionSeconds = 0.45f;
                p.breathRate = 11.5f;
                p.breathDepth = 0.85f;
                p.blinkMin = 3.4f;
                p.blinkMax = 7.5f;
                p.driftAmount = 0.35f;
                p.glanceMin = 9f;
                p.glanceMax = 18f;
                p.gazeRange = 0.25f;
                p.gazeHoldMin = 1.4f;
                p.gazeHoldMax = 3.4f;
                p.postureAmount = 0.4f;
                p.postureShiftMin = 12f;
                p.postureShiftMax = 22f;
                p.nodAuto = 1f;
                p.nodMin = 3.2f;
                p.nodMax = 7.5f;
                p.gestureAuto = 0f;
                pose.set(LeonChannel.AURA, 0.55f);
                pose.set(LeonChannel.EXPR_LISTEN, 1f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0f);
                pose.set(LeonChannel.HEAD_PITCH, 0.08f);
                pose.set(LeonChannel.BROW_L_RAISE, 0.28f);
                pose.set(LeonChannel.BROW_R_RAISE, 0.28f);
                pose.set(LeonChannel.TORSO_LEAN_Y, -0.2f);
                pose.set(LeonChannel.ELBOW_L, 0.15f);
                pose.set(LeonChannel.ELBOW_R, 0.15f);
                break;

            case THINKING:
                // Head tilted off-axis, gaze up and away, hand held near the chin.
                p.transitionSeconds = 0.6f;
                p.breathRate = 10.5f;
                p.blinkMin = 4.2f;
                p.blinkMax = 9f;
                p.driftAmount = 0.5f;
                p.glanceMin = 3f;
                p.glanceMax = 7f;
                p.gazeRange = 0.55f;
                p.gazeHoldMin = 0.9f;
                p.gazeHoldMax = 2.2f;
                p.gazeFocusX = 0.35f;
                p.gazeFocusY = -0.5f;
                p.postureAmount = 0.35f;
                p.nodAuto = 0f;
                p.gestureAuto = 0f;
                pose.set(LeonChannel.AURA, 0.42f);
                pose.set(LeonChannel.EXPR_THINK, 1f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0f);
                pose.set(LeonChannel.HEAD_ROLL, 0.34f);
                pose.set(LeonChannel.HEAD_PITCH, -0.14f);
                pose.set(LeonChannel.BROW_L_RAISE, 0.55f);
                pose.set(LeonChannel.BROW_R_LOWER, 0.35f);
                pose.set(LeonChannel.MOUTH_PRESS, 0.3f);
                pose.set(LeonChannel.TORSO_TWIST, -0.18f);
                break;

            case SPEAKING:
                // Livelier head and hands; the mouth itself is driven by the lip-sync controller.
                p.transitionSeconds = 0.35f;
                p.breathRate = 16f;
                p.breathDepth = 0.75f;
                p.blinkMin = 2.2f;
                p.blinkMax = 5.2f;
                p.driftAmount = 0.8f;
                p.glanceMin = 2.2f;
                p.glanceMax = 5.5f;
                p.gazeRange = 0.45f;
                p.gazeHoldMin = 0.5f;
                p.gazeHoldMax = 1.6f;
                p.postureAmount = 0.6f;
                p.postureShiftMin = 5f;
                p.postureShiftMax = 11f;
                p.gestureAuto = 1f;
                p.gestureMin = 2.4f;
                p.gestureMax = 5.5f;
                p.nodAuto = 1f;
                p.nodMin = 4.5f;
                p.nodMax = 10f;
                pose.set(LeonChannel.AURA, 0.7f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0.4f);
                pose.set(LeonChannel.BROW_L_RAISE, 0.2f);
                pose.set(LeonChannel.BROW_R_RAISE, 0.2f);
                break;

            case HAPPY:
                p.transitionSeconds = 0.3f;
                p.breathRate = 15f;
                p.blinkMin = 2f;
                p.blinkMax = 4.8f;
                p.driftAmount = 1.1f;
                p.gazeRange = 0.7f;
                p.postureAmount = 0.9f;
                p.gestureAuto = 1f;
                p.gestureMin = 3f;
                p.gestureMax = 7f;
                p.nodAuto = 1f;
                p.nodMin = 3.5f;
                p.nodMax = 8f;
                pose.set(LeonChannel.AURA, 0.75f);
                pose.set(LeonChannel.EXPR_SMILE, 1f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0f);
                pose.set(LeonChannel.MOUTH_SMILE, 0.9f);
                pose.set(LeonChannel.BROW_L_RAISE, 0.5f);
                pose.set(LeonChannel.BROW_R_RAISE, 0.5f);
                pose.set(LeonChannel.SHOULDER_L, 0.15f);
                pose.set(LeonChannel.SHOULDER_R, 0.15f);
                pose.set(LeonChannel.TORSO_LEAN_Y, -0.25f);
                break;

            case SERIOUS:
                p.transitionSeconds = 0.5f;
                p.breathRate = 10f;
                p.breathDepth = 0.9f;
                p.blinkMin = 4.5f;
                p.blinkMax = 10f;
                p.driftAmount = 0.28f;
                p.glanceMin = 11f;
                p.glanceMax = 22f;
                p.gazeRange = 0.18f;
                p.gazeHoldMin = 1.8f;
                p.gazeHoldMax = 4.5f;
                p.postureAmount = 0.3f;
                p.gestureAuto = 0f;
                p.nodAuto = 0f;
                pose.set(LeonChannel.AURA, 0.3f);
                pose.set(LeonChannel.EXPR_SERIOUS, 1f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0f);
                pose.set(LeonChannel.BROW_L_LOWER, 0.75f);
                pose.set(LeonChannel.BROW_R_LOWER, 0.75f);
                pose.set(LeonChannel.MOUTH_FROWN, 0.35f);
                pose.set(LeonChannel.MOUTH_PRESS, 0.25f);
                pose.set(LeonChannel.SHOULDER_L, -0.1f);
                pose.set(LeonChannel.SHOULDER_R, -0.1f);
                break;

            case SLEEPY:
                p.transitionSeconds = 0.9f;
                p.breathRate = 7.5f;
                p.breathDepth = 1.15f;
                p.blinkMin = 1.6f;
                p.blinkMax = 4f;
                p.driftAmount = 0.22f;
                p.glanceMin = 14f;
                p.glanceMax = 26f;
                p.gazeRange = 0.2f;
                p.gazeHoldMin = 2.5f;
                p.gazeHoldMax = 6f;
                p.postureAmount = 0.2f;
                p.postureShiftMin = 16f;
                p.postureShiftMax = 30f;
                p.gestureAuto = 0f;
                p.nodAuto = 0f;
                pose.set(LeonChannel.AURA, 0.08f);
                pose.set(LeonChannel.EXPR_SLEEPY, 1f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0f);
                pose.set(LeonChannel.HEAD_PITCH, 0.3f);
                pose.set(LeonChannel.HEAD_ROLL, -0.16f);
                pose.set(LeonChannel.BROW_L_RAISE, 0.12f);
                pose.set(LeonChannel.BROW_R_RAISE, 0.12f);
                pose.set(LeonChannel.TORSO_LEAN_Y, 0.3f);
                pose.set(LeonChannel.SHOULDER_L, -0.2f);
                pose.set(LeonChannel.SHOULDER_R, -0.2f);
                break;

            case ATTENTION:
                // Snaps round to the user; the nod and point are triggered by the controller.
                p.transitionSeconds = 0.16f;
                p.breathRate = 17f;
                p.blinkMin = 1.8f;
                p.blinkMax = 4f;
                p.driftAmount = 0.4f;
                p.gazeRange = 0.1f;
                p.gazeHoldMin = 0.8f;
                p.gazeHoldMax = 1.8f;
                p.postureAmount = 0.5f;
                p.nodAuto = 0f;
                p.gestureAuto = 0f;
                pose.set(LeonChannel.AURA, 0.95f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 0.3f);
                pose.set(LeonChannel.EXPR_SMILE, 0.6f);
                pose.set(LeonChannel.BROW_L_RAISE, 0.85f);
                pose.set(LeonChannel.BROW_R_RAISE, 0.85f);
                pose.set(LeonChannel.MOUTH_SMILE, 0.45f);
                pose.set(LeonChannel.JAW_DROP, 0.18f);
                pose.set(LeonChannel.TORSO_LEAN_Y, -0.4f);
                pose.set(LeonChannel.SHOULDER_L, 0.25f);
                pose.set(LeonChannel.SHOULDER_R, 0.25f);
                break;

            case MINIMISED:
                // Smaller and calmer, but still breathing, blinking and drifting — never frozen.
                p.transitionSeconds = 0.45f;
                p.breathRate = 11f;
                p.breathDepth = 1.1f;
                p.blinkMin = 3f;
                p.blinkMax = 7f;
                p.driftAmount = 0.45f;
                p.glanceMin = 6f;
                p.glanceMax = 14f;
                p.gazeRange = 0.5f;
                p.postureAmount = 0.3f;
                p.gestureAuto = 0f;
                p.nodAuto = 0f;
                pose.set(LeonChannel.RIG_SCALE, 1f);
                pose.set(LeonChannel.AURA, 0.25f);
                pose.set(LeonChannel.EXPR_NEUTRAL, 1f);
                break;

            default:
                throw new IllegalArgumentException("no profile for state " + state);
        }
        return p;
    }
}
