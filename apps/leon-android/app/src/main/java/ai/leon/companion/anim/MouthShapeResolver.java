package ai.leon.companion.anim;

import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.util.Mathx;

/**
 * Turns the continuous mouth channels back into a weight per mouth layer, so the mouth swap group
 * can be driven by the same channels whether the source is an expression, a manual test button or
 * future speech audio. Pure logic, no Android or rig mutation, so it is directly unit-testable.
 *
 * <p>Weights are normalised to sum to 1 across the shapes in {@link #SHAPE_PARTS}.
 */
public final class MouthShapeResolver {
    /** Mouth part names in the order their weights are returned. */
    public static final String[] SHAPE_PARTS = {
            LeonRig.Parts.MOUTH_CLOSED,
            LeonRig.Parts.MOUTH_NEUTRAL_OPEN,
            LeonRig.Parts.MOUTH_A,
            LeonRig.Parts.MOUTH_E,
            LeonRig.Parts.MOUTH_O,
            LeonRig.Parts.MOUTH_U,
            LeonRig.Parts.MOUTH_MBP,
            LeonRig.Parts.MOUTH_FV,
            LeonRig.Parts.MOUTH_SMILE,
            LeonRig.Parts.MOUTH_FROWN,
    };

    public static final int CLOSED = 0;
    public static final int NEUTRAL_OPEN = 1;
    public static final int A = 2;
    public static final int E = 3;
    public static final int O = 4;
    public static final int U = 5;
    public static final int MBP = 6;
    public static final int FV = 7;
    public static final int SMILE = 8;
    public static final int FROWN = 9;

    /** Jaw value at which the lips begin to part. Below this the mouth reads as shut. */
    private static final float LIPS_PART_AT = 0.06f;
    /** Jaw value at which the mouth reads as fully open, well before the jaw is at its limit. */
    private static final float FULLY_OPEN_AT = 0.30f;
    /** Jaw values bracketing the O (open) to U (closed) rounded-vowel crossover. */
    private static final float ROUND_OPEN_AT = 0.55f;
    private static final float ROUND_CLOSED_AT = 0.15f;
    /** Jaw values bracketing the neutral-open to A crossover for unrounded, unstretched vowels. */
    private static final float PLAIN_MIN_AT = 0.35f;
    private static final float PLAIN_MAX_AT = 0.85f;

    private final float[] weights = new float[SHAPE_PARTS.length];

    /**
     * Resolves {@code pose}'s mouth channels into normalised per-shape weights.
     *
     * <p>Order of precedence: the consonant shapes claim their weight first, since they are the
     * visually distinctive ones; whatever is left is split between the open vowels and a shut mouth
     * according to how far the jaw is down. The lips part at a small aperture and the mouth reads as
     * open well before the jaw is fully dropped, so {@link #LIPS_PART_AT} / {@link #FULLY_OPEN_AT}
     * shape that crossover rather than scaling the closed weight linearly against the jaw.
     */
    public float[] resolve(LeonPose pose) {
        float jaw = Mathx.clamp01(pose.get(LeonChannel.JAW_DROP));
        float wide = Mathx.clamp01(pose.get(LeonChannel.MOUTH_WIDE));
        float round = Mathx.clamp01(pose.get(LeonChannel.MOUTH_ROUND));
        float press = Mathx.clamp01(pose.get(LeonChannel.MOUTH_PRESS));
        float teeth = Mathx.clamp01(pose.get(LeonChannel.MOUTH_TEETH));
        float smile = Mathx.clamp01(pose.get(LeonChannel.MOUTH_SMILE));
        float frown = Mathx.clamp01(pose.get(LeonChannel.MOUTH_FROWN));

        java.util.Arrays.fill(weights, 0f);

        weights[FV] = teeth;
        weights[MBP] = press * (1f - teeth);
        float consonant = Mathx.clamp01(weights[FV] + weights[MBP]);
        float remaining = 1f - consonant;
        if (remaining <= 0f) {
            normalise();
            return weights;
        }

        float openness = Mathx.clamp01(Mathx.inverseLerp(LIPS_PART_AT, FULLY_OPEN_AT, jaw));
        float openBudget = remaining * openness;
        float shutBudget = remaining * (1f - openness);

        if (openBudget > 0f) {
            // Shape shares describe which open vowel this is; they do not encode the aperture again.
            float roundShare = round;
            float wideShare = wide;
            float plainShare = Math.max(0f, 1f - Math.max(roundShare, wideShare));

            // O is the open rounded vowel, U the closed one.
            float uShare = Mathx.clamp01(Mathx.inverseLerp(ROUND_OPEN_AT, ROUND_CLOSED_AT, jaw));
            float u = roundShare * uShare;
            float o = roundShare * (1f - uShare);
            // A is the widest plain vowel; a shallower jaw reads as a generic open mouth.
            float aShare = Mathx.clamp01(Mathx.inverseLerp(PLAIN_MIN_AT, PLAIN_MAX_AT, jaw));
            float a = plainShare * aShare;
            float neutral = plainShare * (1f - aShare);

            float total = u + o + wideShare + a + neutral;
            if (total > 0f) {
                float k = openBudget / total;
                weights[U] = u * k;
                weights[O] = o * k;
                weights[E] = wideShare * k;
                weights[A] = a * k;
                weights[NEUTRAL_OPEN] = neutral * k;
            } else {
                weights[NEUTRAL_OPEN] = openBudget;
            }
        }

        if (shutBudget > 0f) {
            float expressive = Math.min(1f, smile + frown);
            if (expressive > 0f) {
                float smileShare = smile / (smile + frown);
                weights[SMILE] = shutBudget * expressive * smileShare;
                weights[FROWN] = shutBudget * expressive * (1f - smileShare);
            }
            weights[CLOSED] = shutBudget * (1f - expressive);
        }

        normalise();
        return weights;
    }

    private void normalise() {
        float total = 0f;
        for (float w : weights) total += w;
        if (total <= 1e-5f) {
            java.util.Arrays.fill(weights, 0f);
            weights[CLOSED] = 1f;
            return;
        }
        for (int i = 0; i < weights.length; i++) weights[i] /= total;
    }

    /** Index of the currently dominant shape. */
    public int dominant() {
        int best = 0;
        for (int i = 1; i < weights.length; i++) {
            if (weights[i] > weights[best]) best = i;
        }
        return best;
    }

    public float[] weights() {
        return weights;
    }
}
