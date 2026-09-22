package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

/**
 * Chest breathing. A real inhale is quicker than the exhale, so the cycle is asymmetric rather than
 * a plain sine, and the motion ripples up through the shoulders, neck and head.
 */
public final class BreathBehaviour implements LeonBehaviour {
    private static final float INHALE_FRACTION = 0.38f;

    private float phase;
    private float ratePerSecond = 0.22f;
    private float depth = 1f;

    /** @param breathsPerMinute resting rate; the animation controller lowers it for SLEEPY. */
    public void setRate(float breathsPerMinute) {
        this.ratePerSecond = Math.max(0.02f, breathsPerMinute / 60f);
    }

    public void setDepth(float depth) {
        this.depth = Mathx.clamp(depth, 0f, 1.5f);
    }

    public float phase() {
        return phase;
    }

    /** Current breath amount, 0 fully exhaled to 1 fully inhaled. */
    public float amount() {
        if (phase < INHALE_FRACTION) {
            return Mathx.smoothstep(phase / INHALE_FRACTION);
        }
        return 1f - Mathx.smoothstep((phase - INHALE_FRACTION) / (1f - INHALE_FRACTION));
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        phase += dt * ratePerSecond;
        if (phase >= 1f) phase -= (float) Math.floor(phase);
        if (weight <= 0f) return;

        float amount = amount() * depth * weight;
        pose.add(LeonChannel.CHEST_BREATH, amount);
        pose.add(LeonChannel.SHOULDER_L, amount * 0.16f);
        pose.add(LeonChannel.SHOULDER_R, amount * 0.16f);
        // Head lifts very slightly on the inhale, which is what reads as "alive" at small sizes.
        pose.add(LeonChannel.HEAD_PITCH, -amount * 0.055f);
        pose.add(LeonChannel.NECKLACE_SWAY, (amount - 0.5f) * 0.12f);
    }

    @Override
    public void reset() {
        phase = 0f;
    }
}
