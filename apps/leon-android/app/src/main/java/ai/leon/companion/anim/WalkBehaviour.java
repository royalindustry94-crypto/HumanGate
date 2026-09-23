package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

import java.util.Random;

/**
 * Leon's walk cycle. Because the production texture is one fixed front-facing photograph, there is
 * no profile "walking" pose to turn into — the character always faces the viewer. So this behaviour
 * does what most front-facing mobile companions do to read as walking rather than sliding: it drives
 * a real alternating leg-and-knee gait (opposite-phase thigh swing, a knee bend timed to each leg's
 * forward lift, a small twice-per-stride hip bob, and a lean into the direction of travel) while a
 * separate horizontal offset — read by the Android overlay, not by the rig — carries the character
 * across the screen. The legs cycling and the window sliding together is what sells it; either alone
 * looks wrong (legs alone: marching in place: window motion alone: gliding).
 *
 * <p>This class only ever produces pose channels and a normalised travel signal. It has no idea it is
 * inside a window, an overlay, or an Android app — {@code LeonOverlayService} is the only thing that
 * turns {@link #travelFraction()} into an actual on-screen movement, and it is expected to stop
 * calling {@link #cancel()} the instant the user takes hold of Leon.
 */
public final class WalkBehaviour implements LeonBehaviour {
    /** Steps per second. A slow, deliberate amble reads as more natural than a fast one at this scale. */
    private static final float CADENCE_HZ = 0.95f;

    private final Random random;

    private boolean autoEnabled;
    private float autoIntervalMin = 45f;
    private float autoIntervalMax = 100f;
    private float timeToAuto;

    private boolean active;
    private float direction = 1f;
    /** 0..1, ramps up over the first and last few tenths of a second so a walk doesn't start/stop mid-stride. */
    private float envelope;
    private float phase;
    private float remaining;

    public WalkBehaviour(long seed) {
        random = new Random(seed);
        scheduleAuto();
    }

    /** Enables spontaneous walks, e.g. only while IDLE — a listening or speaking Leon should not wander. */
    public void setAutoWalk(boolean enabled, float minSeconds, float maxSeconds) {
        boolean wasEnabled = this.autoEnabled;
        this.autoEnabled = enabled;
        this.autoIntervalMin = Math.max(8f, minSeconds);
        this.autoIntervalMax = Math.max(this.autoIntervalMin + 4f, maxSeconds);
        // Reschedule exactly when auto-walk newly turns on, against the window just given -- not
        // every frame (that would keep resetting the countdown and he'd never actually walk), and
        // not on construction, when the real caller-supplied window isn't known yet.
        if (enabled && !wasEnabled) scheduleAuto();
    }

    /** Starts a walk. {@code direction} is screen-space: +1 travels right, -1 travels left. */
    public void startWalk(float direction, float durationSeconds) {
        this.direction = direction >= 0f ? 1f : -1f;
        this.remaining = Math.max(1f, durationSeconds);
        this.active = true;
        // phase is deliberately NOT reset: resuming mid-stride after a brief pause looks continuous.
    }

    /** True while a walk is in progress, including its ramp-out. */
    public boolean isWalking() {
        return active || envelope > 0.01f;
    }

    /** Screen-space direction of the current or most recent walk: +1 right, -1 left. */
    public float direction() {
        return direction;
    }

    /**
     * Signed walking intensity, -1..1: sign is screen-space direction, magnitude is the start/stop
     * envelope (0 when not walking, ramping to 1 mid-stride and back to 0 as a walk ends or is
     * cancelled). This is not itself a speed -- the overlay has no reason to know how a screen pixel
     * relates to a stride, so it owns its own pixels-per-second walking speed and multiplies this by
     * that and by dt, then clamps the result to the screen.
     */
    public float travelFraction() {
        return direction * envelope;
    }

    /** Ends the current walk immediately, e.g. because the user grabbed Leon. Ramps the legs out cleanly. */
    public void cancel() {
        active = false;
        remaining = 0f;
        // Without this, an auto-triggered walk that gets cancelled restarts on the very next frame
        // -- the countdown that fired it was already at or below zero, so leaving it there just
        // fires again immediately. A real interruption earns a fresh, full wait before he tries
        // to wander off again.
        scheduleAuto();
    }

    @Override
    public void update(float dt, float weight, LeonPose pose) {
        if (!active && !autoEnabled) {
            envelope = Mathx.approach(envelope, 0f, 0.12f, dt);
        } else if (!active) {
            timeToAuto -= dt;
            envelope = Mathx.approach(envelope, 0f, 0.12f, dt);
            if (timeToAuto <= 0f) {
                startWalk(random.nextBoolean() ? 1f : -1f, 3.5f + random.nextFloat() * 4.5f);
            }
        }

        if (active) {
            remaining -= dt;
            envelope = Mathx.approach(envelope, 1f, 0.15f, dt);
            if (remaining <= 0f) {
                active = false;
                scheduleAuto();
            }
        } else {
            envelope = Mathx.approach(envelope, 0f, 0.18f, dt);
        }

        phase += dt * CADENCE_HZ;
        if (phase >= 1f) phase -= (float) Math.floor(phase);
        if (envelope <= 0.002f || weight <= 0f) return;

        double twoPi = Math.PI * 2.0;
        float thighL = (float) Math.sin(phase * twoPi);
        float thighR = (float) Math.sin(phase * twoPi + Math.PI);
        // Knee lift peaks a quarter-cycle ahead of that leg's forward thigh swing, then straightens
        // as the foot plants -- a real knee only bends while the leg is lifting off the ground.
        float shinL = Mathx.clamp01((float) Math.sin(phase * twoPi + Math.PI / 2.0));
        float shinR = Mathx.clamp01((float) Math.sin(phase * twoPi + Math.PI + Math.PI / 2.0));
        float bob = Math.abs((float) Math.sin(phase * twoPi));

        float w = envelope * weight;
        pose.add(LeonChannel.THIGH_L_SWING, thighL * w);
        pose.add(LeonChannel.THIGH_R_SWING, thighR * w);
        pose.add(LeonChannel.SHIN_L_SWING, shinL * w);
        pose.add(LeonChannel.SHIN_R_SWING, shinR * w);
        pose.add(LeonChannel.WALK_BOB, bob * w);
        pose.add(LeonChannel.TORSO_LEAN_X, direction * 0.22f * w);
        // A little contralateral arm swing, the way anyone's arms move when they walk.
        pose.add(LeonChannel.ARM_L_SWING, -thighL * 0.35f * w);
        pose.add(LeonChannel.ARM_R_SWING, -thighR * 0.35f * w);
    }

    private void scheduleAuto() {
        timeToAuto = autoIntervalMin + random.nextFloat() * (autoIntervalMax - autoIntervalMin);
    }

    @Override
    public void reset() {
        active = false;
        envelope = 0f;
        phase = 0f;
        remaining = 0f;
        scheduleAuto();
    }
}
