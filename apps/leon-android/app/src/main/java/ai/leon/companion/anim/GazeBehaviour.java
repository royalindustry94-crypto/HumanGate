package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

import java.util.Random;

/**
 * Eye saccades. Eyes jump to a new fixation point far faster than the head moves and then hold,
 * so gaze is its own behaviour rather than a fraction of the head drift.
 */
public final class GazeBehaviour implements LeonBehaviour {
    private static final float SACCADE_SECONDS = 0.065f;

    private final Random random;

    private float holdMin = 0.7f;
    private float holdMax = 2.6f;
    private float range = 1f;
    private float focusX;
    private float focusY;

    private float fromX, fromY;
    private float toX, toY;
    private float currentX, currentY;
    private float saccadeElapsed = SACCADE_SECONDS;
    private float holdRemaining;

    public GazeBehaviour(long seed) {
        random = new Random(seed);
        holdRemaining = nextHold();
    }

    public void setHoldRange(float minSeconds, float maxSeconds) {
        this.holdMin = Math.max(0.1f, minSeconds);
        this.holdMax = Math.max(this.holdMin + 0.1f, maxSeconds);
    }

    /** 0 pins the eyes on the focus point; 1 lets them wander the full range. */
    public void setRange(float range) {
        this.range = Mathx.clamp(range, 0f, 1f);
    }

    public void setFocus(float x, float y) {
        this.focusX = Mathx.clamp(x, -1f, 1f);
        this.focusY = Mathx.clamp(y, -1f, 1f);
    }

    /** Snaps the gaze to a point immediately, used on tap. */
    public void lookAt(float x, float y, float holdSeconds) {
        fromX = currentX;
        fromY = currentY;
        toX = Mathx.clamp(x, -1f, 1f);
        toY = Mathx.clamp(y, -1f, 1f);
        saccadeElapsed = 0f;
        holdRemaining = Math.max(0.2f, holdSeconds);
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        if (saccadeElapsed < SACCADE_SECONDS) {
            saccadeElapsed += dt;
            float t = Mathx.easeOut(Mathx.clamp01(saccadeElapsed / SACCADE_SECONDS));
            currentX = Mathx.lerp(fromX, toX, t);
            currentY = Mathx.lerp(fromY, toY, t);
        } else {
            currentX = toX;
            currentY = toY;
            holdRemaining -= dt;
            if (holdRemaining <= 0f) {
                fromX = currentX;
                fromY = currentY;
                toX = focusX + (random.nextFloat() * 2f - 1f) * range * 0.8f;
                toY = focusY + (random.nextFloat() * 2f - 1f) * range * 0.45f;
                toX = Mathx.clamp(toX, -1f, 1f);
                toY = Mathx.clamp(toY, -1f, 1f);
                saccadeElapsed = 0f;
                holdRemaining = nextHold();
            }
        }

        if (weight <= 0f) return;
        pose.add(LeonChannel.EYE_LOOK_X, currentX * weight);
        pose.add(LeonChannel.EYE_LOOK_Y, currentY * weight);
    }

    private float nextHold() {
        return holdMin + random.nextFloat() * (holdMax - holdMin);
    }

    @Override
    public void reset() {
        currentX = focusX;
        currentY = focusY;
        toX = focusX;
        toY = focusY;
        saccadeElapsed = SACCADE_SECONDS;
        holdRemaining = nextHold();
    }
}
