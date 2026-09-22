package ai.leon.companion.state;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns which {@link LeonState} Leon is in and nothing else. It has no Android, rendering or
 * networking dependency, so the UI, the overlay service and the tests can all drive the same
 * instance and the animation layer simply observes it.
 */
public final class LeonStateController {
    /** How long ATTENTION holds before falling back to the state it interrupted. */
    public static final float ATTENTION_HOLD_SECONDS = 2.2f;

    public interface Listener {
        void onLeonStateChanged(LeonState previous, LeonState current);
    }

    private final List<Listener> listeners = new ArrayList<>(2);

    private LeonState state = LeonState.IDLE;
    /** State to return to when a transient state expires or Leon is restored from minimised. */
    private LeonState restoreState = LeonState.IDLE;
    private float transientRemaining;
    private long lastTickNanos;

    public LeonState state() {
        return state;
    }

    public LeonState restoreState() {
        return restoreState;
    }

    public boolean isMinimised() {
        return state == LeonState.MINIMISED;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Requests a state. Transient states remember what they interrupted; minimising remembers the
     * expanded state so {@link #restore()} can put it back.
     */
    public void request(LeonState next) {
        if (next == null || next == state) return;
        if (next.isTransient() || next == LeonState.MINIMISED) {
            if (!state.isTransient() && state != LeonState.MINIMISED) {
                restoreState = state;
            }
        } else {
            restoreState = next;
        }
        transientRemaining = next.isTransient() ? ATTENTION_HOLD_SECONDS : 0f;
        LeonState previous = state;
        state = next;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i).onLeonStateChanged(previous, state);
        }
    }

    /** Minimise / expand toggle used by the double-tap gesture. */
    public void toggleMinimised() {
        if (isMinimised()) restore();
        else request(LeonState.MINIMISED);
    }

    /** Returns to the remembered expanded state. */
    public void restore() {
        LeonState target = restoreState;
        if (target == LeonState.MINIMISED || target.isTransient()) target = LeonState.IDLE;
        request(target);
    }

    /** Reacts to a tap without losing the state it interrupted. */
    public void poke() {
        if (isMinimised()) {
            // A minimised Leon acknowledges in place rather than expanding behind the user's back.
            transientRemaining = ATTENTION_HOLD_SECONDS;
            return;
        }
        request(LeonState.ATTENTION);
    }

    /** True while a minimised Leon is still acknowledging a poke. */
    public boolean isAcknowledging() {
        return isMinimised() && transientRemaining > 0f;
    }

    /**
     * Advances the transient timers by real elapsed time, measured from a monotonic clock.
     *
     * <p>Every render surface calls this once per frame, and there can be more than one on screen at
     * a time (the overlay plus the control centre's preview). Taking the elapsed time from a shared
     * clock rather than from each caller's frame delta means the first caller in a frame consumes
     * the elapsed time and the others see almost none, so ATTENTION still holds for
     * {@link #ATTENTION_HOLD_SECONDS} of wall-clock time however many surfaces are rendering and
     * whatever frame rates they are running at.
     */
    public void tick() {
        long now = System.nanoTime();
        if (lastTickNanos == 0L) {
            lastTickNanos = now;
            return;
        }
        float dt = (now - lastTickNanos) / 1_000_000_000f;
        lastTickNanos = now;
        // A long gap (the screen was off) must not skip a transient state outright.
        update(Math.min(dt, 0.5f));
    }

    /** Advances transient-state timers by an explicit delta. Used by tests for determinism. */
    public void update(float dtSeconds) {
        if (transientRemaining <= 0f) return;
        transientRemaining -= dtSeconds;
        if (transientRemaining > 0f) return;
        transientRemaining = 0f;
        if (state.isTransient()) {
            LeonState target = restoreState;
            if (target.isTransient()) target = LeonState.IDLE;
            request(target);
        }
    }

    /** Restores a persisted state without firing the transient timer logic twice. */
    public void adoptPersisted(LeonState persisted, LeonState persistedRestore) {
        if (persistedRestore != null && !persistedRestore.isTransient()
                && persistedRestore != LeonState.MINIMISED) {
            restoreState = persistedRestore;
        }
        if (persisted == null || persisted.isTransient()) return;
        LeonState previous = state;
        state = persisted;
        transientRemaining = 0f;
        if (previous != state) {
            for (int i = 0; i < listeners.size(); i++) {
                listeners.get(i).onLeonStateChanged(previous, state);
            }
        }
    }
}
