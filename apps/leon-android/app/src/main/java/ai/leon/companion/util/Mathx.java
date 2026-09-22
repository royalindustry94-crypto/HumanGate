package ai.leon.companion.util;

/** Small float math helpers shared by the rig and animation layers. Pure Java, no Android types. */
public final class Mathx {
    public static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private Mathx() {}

    public static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    public static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Fraction of the way from {@code a} to {@code b}; 0 when the range is degenerate. */
    public static float inverseLerp(float a, float b, float v) {
        float range = b - a;
        if (Math.abs(range) < 1e-6f) return 0f;
        return clamp01((v - a) / range);
    }

    public static float smoothstep(float t) {
        float x = clamp01(t);
        return x * x * (3f - 2f * x);
    }

    /** Ease-in-out used for state cross-fades so transitions never start or stop abruptly. */
    public static float easeInOut(float t) {
        float x = clamp01(t);
        return x < 0.5f ? 2f * x * x : 1f - 2f * (1f - x) * (1f - x);
    }

    /** Ease-out cubic; good for gestures that snap out then settle. */
    public static float easeOut(float t) {
        float x = 1f - clamp01(t);
        return 1f - x * x * x;
    }

    /**
     * Frame-rate independent exponential approach. {@code halfLife} is the time in seconds for the
     * remaining distance to halve, so behaviour is identical at 15fps and 60fps.
     */
    public static float approach(float current, float target, float halfLife, float dt) {
        if (halfLife <= 0f) return target;
        float factor = (float) (1.0 - Math.pow(0.5, dt / halfLife));
        return current + (target - current) * factor;
    }
}
