package ai.leon.companion.util;

/**
 * Critically-damped spring over a single scalar. Used for head turns, weight shifts and gesture
 * limbs so motion overshoots and settles like a body instead of snapping like a tween.
 */
public final class Spring1D {
    /** Largest stable integration step for the stiffnesses this rig uses. */
    private static final float MAX_STEP = 0.008f;
    private static final int MAX_STEPS = 16;

    private float value;
    private float velocity;
    private float stiffness;
    private float damping;

    public Spring1D(float initial, float stiffness, float damping) {
        this.value = initial;
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public void configure(float stiffness, float damping) {
        this.stiffness = stiffness;
        this.damping = damping;
    }

    public float value() {
        return value;
    }

    public float velocity() {
        return velocity;
    }

    public void snapTo(float v) {
        value = v;
        velocity = 0f;
    }

    public void nudge(float impulse) {
        velocity += impulse;
    }

    /**
     * Integrates towards {@code target}, sub-stepping so a long frame cannot make it diverge.
     *
     * <p>The substep <em>size</em> is capped, not just the substep count: capping only the count
     * would hand a stalled frame enormous steps and the spring would explode. When a frame is longer
     * than {@code MAX_STEP * MAX_STEPS} the remainder is dropped, which for a render stall is the
     * correct outcome — the spring resumes from where it was rather than lurching.
     */
    public float update(float target, float dt) {
        if (dt <= 0f) return value;
        int steps = (int) Math.ceil(dt / MAX_STEP);
        if (steps > MAX_STEPS) steps = MAX_STEPS;
        float h = Math.min(dt / steps, MAX_STEP);
        for (int i = 0; i < steps; i++) {
            float accel = (target - value) * stiffness - velocity * damping;
            velocity += accel * h;
            value += velocity * h;
        }
        return value;
    }
}
