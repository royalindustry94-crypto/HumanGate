package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;
import ai.leon.companion.util.SmoothNoise;
import ai.leon.companion.util.Spring1D;

import java.util.Random;

/**
 * Slow whole-body settling: weight moves from one leg to the other every several seconds, the torso
 * leans a little, and the shoulders drift independently of each other. Runs on a much longer clock
 * than breathing so the two never beat against each other.
 */
public final class PostureBehaviour implements LeonBehaviour {
    private final Random random;
    private final SmoothNoise leanNoise;
    private final SmoothNoise shoulderNoiseL;
    private final SmoothNoise shoulderNoiseR;

    private final Spring1D weightSpring = new Spring1D(0f, 9f, 5.2f);
    private final Spring1D twistSpring = new Spring1D(0f, 11f, 5.6f);

    // Scales only the three channels that drive LeonMeshRig's hip/hand-fade seam -- WEIGHT_SHIFT,
    // SHOULDER_L/R, and the two ARM_*_SWING terms -- not TORSO_TWIST/TORSO_LEAN_X/NECKLACE_SWAY,
    // which don't touch that seam. Measured against the full real amplitude range combined with
    // IDLE's own always-on base pose (ELBOW_L/R=0.12, see LeonStateProfile): at the previous full
    // amplitude that seam reached 65x; at 0.2 it's 5.7x. That's the best amplitude alone can do --
    // IDLE's base elbow bend, on its own, with zero sway at all, already stretches this same seam
    // to ~3.1x, a static floor this scale can't reach past no matter how low it goes. Closing the
    // remaining gap needs either finer mesh resolution at that seam or reducing the base elbow
    // bend itself, neither of which is an animation-amplitude change.
    private static final float IDLE_SWAY_SCALE = 0.2f;

    private float amount = 1f;
    private float shiftIntervalMin = 7f;
    private float shiftIntervalMax = 16f;
    private float timeToShift;
    private float weightTarget;
    private float twistTarget;

    public PostureBehaviour(long seed) {
        random = new Random(seed);
        leanNoise = new SmoothNoise(seed + 71, 0.03f, 2);
        shoulderNoiseL = new SmoothNoise(seed + 97, 0.041f, 2);
        shoulderNoiseR = new SmoothNoise(seed + 131, 0.037f, 2);
        scheduleShift();
    }

    public void setAmount(float amount) {
        this.amount = Mathx.clamp(amount, 0f, 1.5f);
    }

    public void setShiftInterval(float minSeconds, float maxSeconds) {
        this.shiftIntervalMin = Math.max(1f, minSeconds);
        this.shiftIntervalMax = Math.max(this.shiftIntervalMin + 1f, maxSeconds);
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        timeToShift -= dt;
        if (timeToShift <= 0f) {
            // Always shift away from the current side, so weight actually travels.
            float side = weightTarget >= 0f ? -1f : 1f;
            weightTarget = side * (0.35f + random.nextFloat() * 0.5f) * IDLE_SWAY_SCALE;
            twistTarget = (random.nextFloat() * 2f - 1f) * 0.3f;
            scheduleShift();
        }

        float weightValue = weightSpring.update(weightTarget * amount, dt);
        float twistValue = twistSpring.update(twistTarget * amount, dt);
        float lean = leanNoise.update(dt) * 0.3f * amount;
        float shL = shoulderNoiseL.update(dt);
        float shR = shoulderNoiseR.update(dt);

        if (weight <= 0f) return;
        pose.add(LeonChannel.WEIGHT_SHIFT, weightValue * weight);
        pose.add(LeonChannel.TORSO_TWIST, twistValue * weight);
        pose.add(LeonChannel.TORSO_LEAN_X, lean * weight);
        pose.add(LeonChannel.SHOULDER_L, shL * 0.17f * IDLE_SWAY_SCALE * amount * weight);
        pose.add(LeonChannel.SHOULDER_R, shR * 0.17f * IDLE_SWAY_SCALE * amount * weight);
        // Arms hang from the shoulders, so they inherit part of the sway (see IDLE_SWAY_SCALE's own
        // comment for why this and the shoulder lines above are scaled down -- LeonMeshRig's
        // hip/hand-fade mesh seam can't otherwise absorb how far the hand swings from the hip).
        pose.add(LeonChannel.ARM_L_SWING, (weightValue * 0.10f + shL * 0.09f * IDLE_SWAY_SCALE) * weight);
        pose.add(LeonChannel.ARM_R_SWING, (weightValue * 0.10f + shR * 0.09f * IDLE_SWAY_SCALE) * weight);
        pose.add(LeonChannel.NECKLACE_SWAY, -twistValue * 0.4f * weight);
    }

    private void scheduleShift() {
        timeToShift = shiftIntervalMin + random.nextFloat() * (shiftIntervalMax - shiftIntervalMin);
    }

    @Override
    public void reset() {
        weightSpring.snapTo(0f);
        twistSpring.snapTo(0f);
        leanNoise.reset();
        shoulderNoiseL.reset();
        shoulderNoiseR.reset();
        weightTarget = 0f;
        twistTarget = 0f;
        scheduleShift();
    }
}
