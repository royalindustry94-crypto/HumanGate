package ai.leon.companion.anim;

import ai.leon.companion.util.Mathx;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Drives Leon's mouth. The public contract is a stream of viseme events:
 *
 * <pre>controller.onViseme(Viseme.A, 0.8f, 90);</pre>
 *
 * <p>which is exactly what a speech pipeline emits once one exists. Nothing here knows about audio,
 * networking or UI — a future TTS layer only has to call {@link #onViseme} as samples play, and the
 * mouth will follow with no rig or state-machine changes.
 *
 * <p>Until that pipeline exists, {@link #setSyntheticDriver(boolean)} generates a plausible viseme
 * stream so the SPEAKING state has something real to animate. That driver is a test signal source,
 * not a substitute for the animation system: it feeds the same channels through the same path.
 */
public final class LeonLipSyncController {
    /** Minimum cross-fade between two visemes; below this the mouth snaps and looks synthetic. */
    private static final float MIN_BLEND_SECONDS = 0.035f;
    /** How long after the last event the mouth returns to rest. */
    private static final float TRAIL_OFF_SECONDS = 0.22f;
    private static final int MAX_QUEUED = 48;

    private static final class Event {
        Viseme viseme;
        float intensity;
        float duration;
    }

    private final Deque<Event> queue = new ArrayDeque<>();
    private final Deque<Event> pool = new ArrayDeque<>();
    private final Random random;

    private final LeonPose previous = new LeonPose();
    private final LeonPose current = new LeonPose();
    private final LeonPose blended = new LeonPose();

    private Viseme activeViseme = Viseme.CLOSED;
    private float activeIntensity;
    private float activeDuration;
    private float activeElapsed;
    private float blendSeconds = MIN_BLEND_SECONDS;

    /** 0 when the mouth is fully handed back to the state's base pose, 1 when lip sync owns it. */
    private float authority;
    private float silenceTimer;

    private boolean syntheticDriver;
    private float syntheticTimeToNext;

    public LeonLipSyncController(long seed) {
        random = new Random(seed);
        Viseme.CLOSED.applyTo(previous, 0f);
        Viseme.CLOSED.applyTo(current, 0f);
    }

    /**
     * Queues one mouth shape. Safe to call from an audio callback thread only if the caller
     * serialises with the render thread; the overlay posts these onto the render thread.
     *
     * @param viseme    target shape
     * @param intensity 0..1 loudness, so a quiet syllable moves the mouth less
     * @param durationMs how long this shape is held before the next event
     */
    public void onViseme(Viseme viseme, float intensity, long durationMs) {
        if (viseme == null) return;
        if (queue.size() >= MAX_QUEUED) {
            // Drop the oldest rather than growing without bound if a producer outruns the renderer.
            recycle(queue.pollFirst());
        }
        Event e = pool.isEmpty() ? new Event() : pool.pollFirst();
        e.viseme = viseme;
        e.intensity = Mathx.clamp01(intensity);
        e.duration = Math.max(0.02f, durationMs / 1000f);
        queue.addLast(e);
    }

    /** Clears any pending speech and lets the mouth fall back to the state's expression. */
    public void stop() {
        while (!queue.isEmpty()) recycle(queue.pollFirst());
        syntheticDriver = false;
        activeDuration = 0f;
        silenceTimer = TRAIL_OFF_SECONDS;
    }

    /** True while the mouth is being driven by speech rather than by the state's base pose. */
    public boolean isSpeaking() {
        return authority > 0.01f || !queue.isEmpty() || activeElapsed < activeDuration;
    }

    public float authority() {
        return authority;
    }

    public Viseme activeViseme() {
        return activeViseme;
    }

    public boolean hasSyntheticDriver() {
        return syntheticDriver;
    }

    /**
     * Enables the placeholder viseme generator used by the SPEAKING state and the developer test
     * buttons while no speech audio exists. Real audio should call {@link #onViseme} instead.
     */
    public void setSyntheticDriver(boolean enabled) {
        if (this.syntheticDriver == enabled) return;
        this.syntheticDriver = enabled;
        syntheticTimeToNext = enabled ? 0f : 0f;
        if (!enabled) {
            while (!queue.isEmpty()) recycle(queue.pollFirst());
            silenceTimer = TRAIL_OFF_SECONDS;
        }
    }

    public void update(float dt) {
        if (syntheticDriver) {
            syntheticTimeToNext -= dt;
            while (syntheticTimeToNext <= 0f && queue.size() < 6) {
                emitSyntheticSyllable();
            }
        }

        activeElapsed += dt;
        if (activeElapsed >= activeDuration) {
            Event next = queue.pollFirst();
            if (next != null) {
                previous.copyFrom(blended);
                activeViseme = next.viseme;
                activeIntensity = next.intensity;
                activeDuration = next.duration;
                blendSeconds = Math.max(MIN_BLEND_SECONDS, Math.min(next.duration * 0.45f, 0.09f));
                activeElapsed = 0f;
                activeViseme.applyTo(current, activeIntensity);
                recycle(next);
                silenceTimer = 0f;
            } else {
                // Nothing queued: ease the mouth shut, then hand authority back to the state pose.
                if (activeViseme != Viseme.CLOSED || activeIntensity > 0f) {
                    previous.copyFrom(blended);
                    activeViseme = Viseme.CLOSED;
                    activeIntensity = 0f;
                    activeDuration = TRAIL_OFF_SECONDS;
                    blendSeconds = TRAIL_OFF_SECONDS;
                    activeElapsed = 0f;
                    Viseme.CLOSED.applyTo(current, 0f);
                }
                silenceTimer += dt;
            }
        }

        float t = blendSeconds <= 0f ? 1f : Mathx.clamp01(activeElapsed / blendSeconds);
        LeonPose.lerp(previous, current, Mathx.smoothstep(t), blended);

        boolean active = syntheticDriver || !queue.isEmpty() || activeIntensity > 0f;
        float authorityTarget = active ? 1f : 0f;
        if (!active && silenceTimer < TRAIL_OFF_SECONDS) authorityTarget = 1f;
        authority = Mathx.approach(authority, authorityTarget, 0.05f, dt);
        if (authorityTarget == 0f && authority < 0.01f) authority = 0f;
    }

    /**
     * Blends the mouth channels of {@code pose} towards the current viseme by the controller's
     * authority, so an idle Leon keeps his expression's mouth and a speaking Leon does not.
     */
    public void applyTo(LeonPose pose) {
        if (authority <= 0f) return;
        blendChannel(pose, LeonChannel.JAW_DROP);
        blendChannel(pose, LeonChannel.MOUTH_WIDE);
        blendChannel(pose, LeonChannel.MOUTH_ROUND);
        blendChannel(pose, LeonChannel.MOUTH_PRESS);
        blendChannel(pose, LeonChannel.MOUTH_TEETH);
        // Speech pulls the smile/frown down so a viseme is not fighting an expression's mouth shape.
        pose.set(LeonChannel.MOUTH_SMILE, pose.get(LeonChannel.MOUTH_SMILE) * (1f - authority * 0.7f));
        pose.set(LeonChannel.MOUTH_FROWN, pose.get(LeonChannel.MOUTH_FROWN) * (1f - authority * 0.7f));
        // Speech also moves the brows and head a little; silent mouth flapping reads as a puppet.
        float emphasis = blended.get(LeonChannel.JAW_DROP) * authority;
        pose.add(LeonChannel.BROW_L_RAISE, emphasis * 0.22f);
        pose.add(LeonChannel.BROW_R_RAISE, emphasis * 0.22f);
        pose.add(LeonChannel.HEAD_PITCH, -emphasis * 0.07f);
    }

    private void blendChannel(LeonPose pose, LeonChannel channel) {
        pose.set(channel, Mathx.lerp(pose.get(channel), blended.get(channel), authority));
    }

    /** One plausible syllable: an optional consonant onset followed by a vowel. */
    private void emitSyntheticSyllable() {
        float loudness = 0.55f + random.nextFloat() * 0.45f;
        if (random.nextFloat() < 0.45f) {
            Viseme onset = random.nextBoolean() ? Viseme.MBP : Viseme.FV;
            long onsetMs = 45 + random.nextInt(35);
            onViseme(onset, loudness * 0.8f, onsetMs);
            syntheticTimeToNext += onsetMs / 1000f;
        }
        Viseme[] vowels = {Viseme.A, Viseme.E, Viseme.O, Viseme.U, Viseme.NEUTRAL_OPEN};
        Viseme vowel = vowels[random.nextInt(vowels.length)];
        long vowelMs = 80 + random.nextInt(120);
        onViseme(vowel, loudness, vowelMs);
        syntheticTimeToNext += vowelMs / 1000f;

        // Occasional word gap so it does not read as a continuous drone.
        if (random.nextFloat() < 0.22f) {
            long gapMs = 110 + random.nextInt(220);
            onViseme(Viseme.CLOSED, 0f, gapMs);
            syntheticTimeToNext += gapMs / 1000f;
        }
    }

    private void recycle(Event e) {
        if (e == null) return;
        e.viseme = null;
        if (pool.size() < MAX_QUEUED) pool.addLast(e);
    }

    public void reset() {
        stop();
        authority = 0f;
        activeViseme = Viseme.CLOSED;
        activeIntensity = 0f;
        activeDuration = 0f;
        activeElapsed = 0f;
        Viseme.CLOSED.applyTo(previous, 0f);
        Viseme.CLOSED.applyTo(current, 0f);
        Viseme.CLOSED.applyTo(blended, 0f);
    }
}
