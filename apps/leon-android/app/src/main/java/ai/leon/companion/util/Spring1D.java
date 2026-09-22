package ai.leon.companion.util;

/**
 * Critically-damped spring over a single scalar. Used for head turns, weight shifts and gesture
 * limbs so motion overshoots and settles like a body instead of snapping like a tween.
 */
public final class Spring1D {
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

    /** Integrates towards {@code target}, sub-stepping so a long frame cannot make it explode. */
    public float update(float target, float dt) {
        if (dt <= 0f) return value;
        int steps = (int) Math.ceil(dt / 0.008f);
        if (steps > 16) steps = 16;
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            float accel = (target - value) * stiffness - velocity * damping;
            velocity += accel * h;
            value += velocity * h;
        }
        return value;
    }
}
