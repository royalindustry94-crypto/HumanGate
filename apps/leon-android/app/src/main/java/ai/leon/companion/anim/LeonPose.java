package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

/**
 * A full set of {@link LeonChannel} values. Poses are the currency of the animation layer: states
 * define a base pose, behaviours add deltas onto the working pose, and transitions interpolate
 * between two of them.
 */
public final class LeonPose {
    private final float[] values = new float[LeonChannel.COUNT];

    public LeonPose() {
        setDefaults();
    }

    /** Rest pose: everything neutral, scale 1, neutral expression fully weighted. */
    public LeonPose setDefaults() {
        java.util.Arrays.fill(values, 0f);
        values[LeonChannel.RIG_SCALE.ordinal()] = 1f;
        values[LeonChannel.EXPR_NEUTRAL.ordinal()] = 1f;
        return this;
    }

    public LeonPose clear() {
        java.util.Arrays.fill(values, 0f);
        return this;
    }

    public float get(LeonChannel channel) {
        return values[channel.ordinal()];
    }

    public LeonPose set(LeonChannel channel, float value) {
        values[channel.ordinal()] = value;
        return this;
    }

    public LeonPose add(LeonChannel channel, float delta) {
        values[channel.ordinal()] += delta;
        return this;
    }

    /** Raises a channel to at least {@code value}; used where behaviours must not cancel out. */
    public LeonPose raise(LeonChannel channel, float value) {
        int i = channel.ordinal();
        if (value > values[i]) values[i] = value;
        return this;
    }

    public LeonPose copyFrom(LeonPose other) {
        System.arraycopy(other.values, 0, values, 0, values.length);
        return this;
    }

    /** {@code this = lerp(this, target, t)} across every channel. */
    public LeonPose lerpTowards(LeonPose target, float t) {
        float k = Mathx.clamp01(t);
        for (int i = 0; i < values.length; i++) {
            values[i] += (target.values[i] - values[i]) * k;
        }
        return this;
    }

    /** {@code out = lerp(a, b, t)}. */
    public static void lerp(LeonPose a, LeonPose b, float t, LeonPose out) {
        float k = Mathx.clamp01(t);
        for (int i = 0; i < out.values.length; i++) {
            out.values[i] = a.values[i] + (b.values[i] - a.values[i]) * k;
        }
    }

    /** Clamps every channel into the range the binder can safely consume. */
    public LeonPose clampAll() {
        for (int i = 0; i < values.length; i++) {
            values[i] = Mathx.clamp(values[i], -2f, 2f);
        }
        int scale = LeonChannel.RIG_SCALE.ordinal();
        values[scale] = Mathx.clamp(values[scale], 0.15f, 2f);
        clamp01(LeonChannel.BLINK_L);
        clamp01(LeonChannel.BLINK_R);
        clamp01(LeonChannel.JAW_DROP);
        clamp01(LeonChannel.MOUTH_ROUND);
        clamp01(LeonChannel.MOUTH_WIDE);
        clamp01(LeonChannel.MOUTH_PRESS);
        clamp01(LeonChannel.MOUTH_TEETH);
        clamp01(LeonChannel.CHEST_BREATH);
        clamp01(LeonChannel.AURA);
        return this;
    }

    private void clamp01(LeonChannel channel) {
        int i = channel.ordinal();
        values[i] = Mathx.clamp01(values[i]);
    }
}
