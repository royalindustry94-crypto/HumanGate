package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** State ownership: transitions, the transient ATTENTION state, and minimise/restore memory. */
public class LeonStateControllerTest {
    private LeonStateController states;
    private final List<String> events = new ArrayList<>();

    @Before
    public void setUp() {
        events.clear();
        states = new LeonStateController();
        states.addListener(new LeonStateController.Listener() {
            @Override
            public void onLeonStateChanged(LeonState previous, LeonState current) {
                events.add(previous + "->" + current);
            }
        });
    }

    @Test
    public void startsIdle() {
        assertEquals(LeonState.IDLE, states.state());
        assertFalse(states.isMinimised());
    }

    @Test
    public void requestingAStateNotifiesListenersOnce() {
        states.request(LeonState.LISTENING);
        assertEquals(LeonState.LISTENING, states.state());
        assertEquals(1, events.size());
        assertEquals("IDLE->LISTENING", events.get(0));
    }

    @Test
    public void requestingTheCurrentStateIsANoOp() {
        states.request(LeonState.IDLE);
        assertTrue(events.isEmpty());
    }

    @Test
    public void attentionReturnsToTheStateItInterrupted() {
        states.request(LeonState.THINKING);
        states.poke();
        assertEquals(LeonState.ATTENTION, states.state());

        states.update(LeonStateController.ATTENTION_HOLD_SECONDS * 0.5f);
        assertEquals("attention must hold for its full duration",
                LeonState.ATTENTION, states.state());

        states.update(LeonStateController.ATTENTION_HOLD_SECONDS);
        assertEquals(LeonState.THINKING, states.state());
    }

    @Test
    public void minimisingRemembersTheExpandedState() {
        states.request(LeonState.SERIOUS);
        states.toggleMinimised();
        assertTrue(states.isMinimised());
        assertEquals(LeonState.MINIMISED, states.state());

        states.toggleMinimised();
        assertEquals(LeonState.SERIOUS, states.state());
    }

    @Test
    public void restoringFromMinimisedNeverLandsOnATransientState() {
        states.poke();
        assertEquals(LeonState.ATTENTION, states.state());
        states.request(LeonState.MINIMISED);
        states.restore();
        assertFalse(states.state().isTransient());
        assertEquals(LeonState.IDLE, states.state());
    }

    @Test
    public void pokingWhileMinimisedAcknowledgesWithoutExpanding() {
        states.request(LeonState.MINIMISED);
        states.poke();
        assertEquals("a minimised Leon must not expand itself", LeonState.MINIMISED, states.state());
        assertTrue(states.isAcknowledging());

        states.update(LeonStateController.ATTENTION_HOLD_SECONDS + 0.1f);
        assertFalse(states.isAcknowledging());
        assertEquals(LeonState.MINIMISED, states.state());
    }

    @Test
    public void persistedStateIsAdoptedWithoutRunningTransientLogic() {
        states.adoptPersisted(LeonState.MINIMISED, LeonState.HAPPY);
        assertEquals(LeonState.MINIMISED, states.state());
        states.restore();
        assertEquals(LeonState.HAPPY, states.state());
    }

    @Test
    public void aPersistedTransientStateIsIgnored() {
        states.adoptPersisted(LeonState.ATTENTION, LeonState.IDLE);
        assertEquals("ATTENTION must never be restored from storage",
                LeonState.IDLE, states.state());
    }

    @Test
    public void everyStateHasATransitionProfile() {
        for (LeonState state : LeonState.values()) {
            // Throws if a state is added without giving it a profile.
            ai.leon.companion.anim.LeonStateProfile profile =
                    ai.leon.companion.anim.LeonStateProfile.forState(state);
            assertTrue(state + " needs a positive transition time", profile.transitionSeconds > 0f);
        }
    }

    @Test
    public void twoRenderSurfacesDoNotMakeTransientStatesExpireTwiceAsFast() throws Exception {
        // The overlay and the control centre's preview each tick the shared controller once per
        // frame. Elapsed time comes from a monotonic clock, so the hold must not be halved.
        states.poke();
        assertEquals(LeonState.ATTENTION, states.state());
        states.tick();

        long deadline = System.nanoTime()
                + (long) (LeonStateController.ATTENTION_HOLD_SECONDS * 0.4f * 1_000_000_000L);
        while (System.nanoTime() < deadline) {
            states.tick();
            states.tick();
            Thread.sleep(2L);
        }
        assertEquals("two surfaces ticking must not halve the hold",
                LeonState.ATTENTION, states.state());
    }

    @Test
    public void aLongGapWithTheScreenOffDoesNotSkipATransientState() {
        states.poke();
        // A single enormous delta, as if the clock had jumped. The state must still be entered and
        // then expire on the next ticks rather than being skipped in one step.
        states.update(60f);
        assertEquals(LeonState.IDLE, states.state());
    }

    @Test
    public void stateNamesRoundTripForPersistence() {
        for (LeonState state : LeonState.values()) {
            assertEquals(state, LeonState.fromName(state.name(), LeonState.IDLE));
            assertEquals(state, LeonState.fromName(state.name().toLowerCase(java.util.Locale.US),
                    LeonState.IDLE));
        }
        assertEquals(LeonState.IDLE, LeonState.fromName("nonsense", LeonState.IDLE));
        assertEquals(LeonState.IDLE, LeonState.fromName(null, LeonState.IDLE));
    }
}
