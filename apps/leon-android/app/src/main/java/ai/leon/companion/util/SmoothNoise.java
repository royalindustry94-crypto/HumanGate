package ai.leon.companion.util;

import java.util.Random;

/**
 * Deterministic band-limited noise built from summed sines with randomised phases. Drives idle
 * drift (head, torso, hood) so Leon never repeats an identical loop, while staying cheap and
 * reproducible for tests when constructed with a fixed seed.
 */
public final class SmoothNoise {
    private final float[] frequency;
    private final float[] phase;
    private final float[] amplitude;
    private float time;

    public SmoothNoise(long seed, float baseFrequencyHz, int octaves) {
        if (octaves < 1) octaves = 1;
        Random rnd = new Random(seed);
        frequency = new float[octaves];
        phase = new float[octaves];
        amplitude = new float[octaves];
        float amp = 1f;
        float total = 0f;
        for (int i = 0; i < octaves; i++) {
            frequency[i] = baseFrequencyHz * (float) Math.pow(2.0, i) * (0.85f + rnd.nextFloat() * 0.3f);
            phase[i] = rnd.nextFloat() * (float) (Math.PI * 2.0);
            amplitude[i] = amp;
            total += amp;
            amp *= 0.5f;
        }
        for (int i = 0; i < octaves; i++) {
            amplitude[i] /= total;
        }
    }

    /** Advances the internal clock and returns the new value in roughly [-1, 1]. */
    public float update(float dt) {
        time += dt;
        return valueAt(time);
    }

    public float valueAt(float t) {
        float sum = 0f;
        for (int i = 0; i < frequency.length; i++) {
            sum += amplitude[i] * (float) Math.sin(t * frequency[i] * (float) (Math.PI * 2.0) + phase[i]);
        }
        return Mathx.clamp(sum, -1f, 1f);
    }

    public void reset() {
        time = 0f;
    }
}
