package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;
import ai.leon.companion.util.SmoothNoise;
import ai.leon.companion.util.Spring1D;

import java.util.Random;

/**
 * Idle head motion: continuous low-amplitude drift from band-limited noise, plus occasional
 * deliberate "glances" that spring to a target, hold, and drift back. The two layers together are
 * what stop the head looking like it is on a timer.
 */
public final class HeadDriftBehaviour implements LeonBehaviour {
    private final SmoothNoise yawNoise;
    private final SmoothNoise pitchNoise;
    private final SmoothNoise rollNoise;
    private final Random random;

    private final Spring1D yawSpring = new Spring1D(0f, 62f, 12f);
    private final Spring1D pitchSpring = new Spring1D(0f, 62f, 12f);

    private float driftAmount = 1f;
    private float glanceIntervalMin = 4.5f;
    private float glanceIntervalMax = 11f;
    private float timeToGlance;
    private float glanceHold;
    private float glanceYawTarget;
    private float glancePitchTarget;

    /** Gaze bias the state wants Leon to hold, e.g. LISTENING looks towards the user. */
    private float focusYaw;
    private float focusPitch;

    public HeadDriftBehaviour(long seed) {
        random = new Random(seed);
        yawNoise = new SmoothNoise(seed + 11, 0.055f, 3);
        pitchNoise = new SmoothNoise(seed + 29, 0.047f, 3);
        rollNoise = new SmoothNoise(seed + 53, 0.036f, 2);
        scheduleGlance();
    }

    public void setDriftAmount(float amount) {
        this.driftAmount = Mathx.clamp(amount, 0f, 1.5f);
    }

    public void setGlanceInterval(float minSeconds, float maxSeconds) {
        this.glanceIntervalMin = Math.max(0.5f, minSeconds);
        this.glanceIntervalMax = Math.max(this.glanceIntervalMin + 0.5f, maxSeconds);
    }

    /** Where the head should settle when it is not glancing. */
    public void setFocus(float yaw, float pitch) {
        this.focusYaw = Mathx.clamp(yaw, -1f, 1f);
        this.focusPitch = Mathx.clamp(pitch, -1f, 1f);
    }

    /** Aims the head at a point, used when Leon is tapped. */
    public void glanceAt(float yaw, float pitch, float holdSeconds) {
        glanceYawTarget = Mathx.clamp(yaw, -1f, 1f);
        glancePitchTarget = Mathx.clamp(pitch, -1f, 1f);
        glanceHold = Math.max(0.2f, holdSeconds);
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        float yawDrift = yawNoise.update(dt);
        float pitchDrift = pitchNoise.update(dt);
        float rollDrift = rollNoise.update(dt);

        if (glanceHold > 0f) {
            glanceHold -= dt;
        } else {
            timeToGlance -= dt;
            if (timeToGlance <= 0f) {
                // Glances favour small movements; large ones are rare, like a real person's.
                float magnitude = (float) Math.pow(random.nextFloat(), 1.8);
                glanceYawTarget = (random.nextBoolean() ? 1f : -1f) * magnitude * 0.7f;
                glancePitchTarget = (random.nextFloat() - 0.5f) * 0.45f;
                glanceHold = 0.5f + random.nextFloat() * 1.6f;
                scheduleGlance();
            } else {
                glanceYawTarget = 0f;
                glancePitchTarget = 0f;
            }
        }

        float yawTarget = focusYaw + glanceYawTarget + yawDrift * 0.34f * driftAmount;
        float pitchTarget = focusPitch + glancePitchTarget + pitchDrift * 0.22f * driftAmount;
        float yaw = yawSpring.update(yawTarget, dt);
        float pitch = pitchSpring.update(pitchTarget, dt);

        if (weight <= 0f) return;
        pose.add(LeonChannel.HEAD_YAW, yaw * weight);
        pose.add(LeonChannel.HEAD_PITCH, pitch * weight);
        pose.add(LeonChannel.HEAD_ROLL, rollDrift * 0.2f * driftAmount * weight);
        // The neck follows a fraction of a beat behind the head.
        pose.add(LeonChannel.NECK_YAW, yaw * 0.4f * weight);
        pose.add(LeonChannel.HOOD_SWAY, -yaw * 0.32f * weight);
    }

    private void scheduleGlance() {
        timeToGlance = glanceIntervalMin + random.nextFloat() * (glanceIntervalMax - glanceIntervalMin);
    }

    @Override
    public void reset() {
        yawNoise.reset();
        pitchNoise.reset();
        rollNoise.reset();
        yawSpring.snapTo(0f);
        pitchSpring.snapTo(0f);
        glanceHold = 0f;
        scheduleGlance();
    }
}
