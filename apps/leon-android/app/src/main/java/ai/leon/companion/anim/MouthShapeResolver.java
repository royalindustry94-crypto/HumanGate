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

    private final float[] weights = new float[SHAPE_PARTS.length];

    /** Resolves {@code pose}'s mouth channels into normalised per-shape weights. */
    public float[] resolve(LeonPose pose) {
        float jaw = Mathx.clamp01(pose.get(LeonChannel.JAW_DROP));
        float wide = Mathx.clamp01(pose.get(LeonChannel.MOUTH_WIDE));
        float round = Mathx.clamp01(pose.get(LeonChannel.MOUTH_ROUND));
        float press = Mathx.clamp01(pose.get(LeonChannel.MOUTH_PRESS));
        float teeth = Mathx.clamp01(pose.get(LeonChannel.MOUTH_TEETH));
        float smile = Mathx.clamp01(pose.get(LeonChannel.MOUTH_SMILE));
        float frown = Mathx.clamp01(pose.get(LeonChannel.MOUTH_FROWN));

        java.util.Arrays.fill(weights, 0f);

        // Consonant shapes win outright; they are the visually distinctive ones.
        weights[FV] = teeth;
        weights[MBP] = press * (1f - teeth);

        float consonant = Mathx.clamp01(weights[FV] + weights[MBP]);
        float vowelRoom = 1f - consonant;

        if (vowelRoom > 0f) {
            // Split the open-mouth budget between the rounded, wide and plain vowels.
            float openness = jaw;
            float roundedWeight = round * openness;
            float wideWeight = wide * openness;
            float plainWeight = Math.max(0f, openness - Math.max(roundedWeight, wideWeight));

            // O is the open rounded vowel, U the closed one.
            float uShare = Mathx.clamp01(Mathx.inverseLerp(0.55f, 0.15f, jaw));
            weights[U] = roundedWeight * uShare;
            weights[O] = roundedWeight * (1f - uShare);
            weights[E] = wideWeight;
            weights[A] = plainWeight * Mathx.clamp01(Mathx.inverseLerp(0.35f, 0.8f, jaw));
            weights[NEUTRAL_OPEN] = plainWeight - weights[A];

            float vowelTotal = weights[U] + weights[O] + weights[E] + weights[A] + weights[NEUTRAL_OPEN];
            if (vowelTotal > vowelRoom && vowelTotal > 0f) {
                float k = vowelRoom / vowelTotal;
                weights[U] *= k; weights[O] *= k; weights[E] *= k;
                weights[A] *= k; weights[NEUTRAL_OPEN] *= k;
                vowelTotal = vowelRoom;
            }

            // Whatever aperture is left over is a shut mouth, expressed as smile/frown/neutral.
            float shut = Mathx.clamp01(vowelRoom - vowelTotal);
            float expressive = Math.min(1f, smile + frown);
            if (expressive > 0f) {
                float smileShare = smile / (smile + frown);
                weights[SMILE] = shut * expressive * smileShare;
                weights[FROWN] = shut * expressive * (1f - smileShare);
            }
            weights[CLOSED] = shut * (1f - expressive);
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
