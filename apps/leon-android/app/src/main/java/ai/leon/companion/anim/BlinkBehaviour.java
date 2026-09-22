package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

import java.util.Random;

/**
 * Natural blinking. Intervals are randomised inside a configurable band, the two lids are offset by
 * a few milliseconds so it never looks mechanical, and roughly one blink in six is a double blink.
 *
 * <p>Both lids are driven through {@link LeonPose#raise} so a blink can never be cancelled out by
 * another behaviour adding a negative contribution.
 */
public final class BlinkBehaviour implements LeonBehaviour {
    private static final float CLOSE_SECONDS = 0.055f;
    private static final float HOLD_SECONDS = 0.035f;
    private static final float OPEN_SECONDS = 0.085f;
    private static final float TOTAL_SECONDS = CLOSE_SECONDS + HOLD_SECONDS + OPEN_SECONDS;
    /** The right lid trails the left by this much, which is what stops it reading as a shutter. */
    private static final float RIGHT_LAG_SECONDS = 0.014f;
    private static final float DOUBLE_BLINK_CHANCE = 0.17f;

    private final Random random;

    private float minInterval = 2.4f;
    private float maxInterval = 6.5f;
    private float timeToNext;
    private float blinkElapsed = -1f;
    private int queuedBlinks;

    public BlinkBehaviour(long seed) {
        random = new Random(seed);
        scheduleNext();
    }

    public void setIntervalRange(float minSeconds, float maxSeconds) {
        this.minInterval = Math.max(0.35f, minSeconds);
        this.maxInterval = Math.max(this.minInterval + 0.2f, maxSeconds);
    }

    /** Forces a blink now, e.g. as part of an ATTENTION reaction. */
    public void triggerBlink() {
        if (blinkElapsed < 0f) blinkElapsed = 0f;
        else queuedBlinks = Math.max(queuedBlinks, 1);
    }

    public boolean isBlinking() {
        return blinkElapsed >= 0f;
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        if (blinkElapsed < 0f) {
            timeToNext -= dt;
            if (timeToNext <= 0f) {
                blinkElapsed = 0f;
                if (random.nextFloat() < DOUBLE_BLINK_CHANCE) queuedBlinks = 1;
            }
        }

        if (blinkElapsed >= 0f) {
            blinkElapsed += dt;
            if (blinkElapsed > TOTAL_SECONDS + RIGHT_LAG_SECONDS) {
                blinkElapsed = -1f;
                if (queuedBlinks > 0) {
                    queuedBlinks--;
                    // Chain the second blink of a double without a full interval in between.
                    timeToNext = 0.09f;
                } else {
                    scheduleNext();
                }
            }
        }

        if (weight <= 0f || blinkElapsed < 0f) return;
        pose.raise(LeonChannel.BLINK_L, curve(blinkElapsed) * weight);
        pose.raise(LeonChannel.BLINK_R, curve(blinkElapsed - RIGHT_LAG_SECONDS) * weight);
    }

    /** Lid closure over time: fast down, brief hold, slightly slower up. */
    static float curve(float t) {
        if (t <= 0f || t >= TOTAL_SECONDS) return 0f;
        if (t < CLOSE_SECONDS) {
            return Mathx.smoothstep(t / CLOSE_SECONDS);
        }
        if (t < CLOSE_SECONDS + HOLD_SECONDS) {
            return 1f;
        }
        return 1f - Mathx.smoothstep((t - CLOSE_SECONDS - HOLD_SECONDS) / OPEN_SECONDS);
    }

    private void scheduleNext() {
        timeToNext = minInterval + random.nextFloat() * (maxInterval - minInterval);
    }

    @Override
    public void reset() {
        blinkElapsed = -1f;
        queuedBlinks = 0;
        scheduleNext();
    }
}
