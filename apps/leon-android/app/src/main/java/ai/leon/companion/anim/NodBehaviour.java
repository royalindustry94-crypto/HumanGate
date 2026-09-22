package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

import java.util.Random;

/**
 * Acknowledgement nods. A nod is a decaying oscillation on head pitch rather than a single dip, so
 * it settles the way a real nod does. LISTENING fires them periodically; ATTENTION fires one on tap.
 */
public final class NodBehaviour implements LeonBehaviour {
    private final Random random;

    private float amplitude = 0.4f;
    private float frequencyHz = 2.1f;
    private float duration = 0.85f;
    private float elapsed = -1f;

    private boolean autoEnabled;
    private float autoIntervalMin = 5f;
    private float autoIntervalMax = 12f;
    private float timeToAuto;

    public NodBehaviour(long seed) {
        random = new Random(seed);
        scheduleAuto();
    }

    public void setAutoNod(boolean enabled, float minSeconds, float maxSeconds) {
        this.autoEnabled = enabled;
        this.autoIntervalMin = Math.max(0.8f, minSeconds);
        this.autoIntervalMax = Math.max(this.autoIntervalMin + 0.5f, maxSeconds);
    }

    public void trigger(float amplitude) {
        this.amplitude = Mathx.clamp(amplitude, 0.05f, 1f);
        this.elapsed = 0f;
    }

    public boolean isNodding() {
        return elapsed >= 0f;
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        if (elapsed < 0f) {
            if (!autoEnabled) return;
            timeToAuto -= dt;
            if (timeToAuto > 0f) return;
            trigger(0.24f + random.nextFloat() * 0.22f);
            scheduleAuto();
        }

        elapsed += dt;
        if (elapsed > duration) {
            elapsed = -1f;
            return;
        }
        if (weight <= 0f) return;

        float t = elapsed / duration;
        float decay = 1f - Mathx.smoothstep(t);
        float swing = (float) Math.sin(elapsed * frequencyHz * Math.PI * 2.0);
        pose.add(LeonChannel.HEAD_PITCH, swing * decay * amplitude * weight);
        pose.add(LeonChannel.NECK_YAW, swing * decay * amplitude * 0.15f * weight);
    }

    private void scheduleAuto() {
        timeToAuto = autoIntervalMin + random.nextFloat() * (autoIntervalMax - autoIntervalMin);
    }

    @Override
    public void reset() {
        elapsed = -1f;
        scheduleAuto();
    }
}
