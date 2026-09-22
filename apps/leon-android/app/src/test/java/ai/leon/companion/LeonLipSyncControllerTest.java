package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.LeonLipSyncController;
import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.LeonVisemeBus;
import ai.leon.companion.anim.Viseme;

import org.junit.Before;
import org.junit.Test;

/**
 * The contract a future speech pipeline will use. These tests pin the behaviour audio-driven lip sync
 * depends on, so adding speech later cannot silently change how the mouth works.
 */
public class LeonLipSyncControllerTest {
    private static final float FRAME = 1f / 60f;

    private LeonLipSyncController lipSync;

    @Before
    public void setUp() {
        lipSync = new LeonLipSyncController(4242L);
    }

    private void advance(float seconds) {
        int frames = Math.max(1, Math.round(seconds / FRAME));
        for (int i = 0; i < frames; i++) lipSync.update(FRAME);
    }

    @Test
    public void aSilentControllerLeavesTheMouthToTheExpression() {
        advance(0.5f);
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.MOUTH_SMILE, 1f);
        lipSync.applyTo(pose);
        assertEquals("a silent mouth must keep its expression", 1f,
                pose.get(LeonChannel.MOUTH_SMILE), 0.0001f);
        assertEquals(0f, lipSync.authority(), 0.0001f);
    }

    @Test
    public void aVisemeEventTakesOverTheMouth() {
        lipSync.onViseme(Viseme.A, 1f, 250);
        advance(0.2f);
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.MOUTH_SMILE, 1f);
        lipSync.applyTo(pose);
        assertTrue("the jaw must open", pose.get(LeonChannel.JAW_DROP) > 0.4f);
        assertTrue("speech must suppress the expression's smile",
                pose.get(LeonChannel.MOUTH_SMILE) < 0.6f);
    }

    @Test
    public void visemesCrossFadeInsteadOfSnapping() {
        lipSync.onViseme(Viseme.CLOSED, 0f, 120);
        advance(0.15f);
        lipSync.onViseme(Viseme.A, 1f, 200);
        lipSync.update(FRAME);
        LeonPose pose = new LeonPose();
        lipSync.applyTo(pose);
        float firstFrame = pose.get(LeonChannel.JAW_DROP);
        advance(0.12f);
        LeonPose later = new LeonPose();
        lipSync.applyTo(later);
        assertTrue("the jaw must ease open, not jump", firstFrame < later.get(LeonChannel.JAW_DROP));
    }

    @Test
    public void theMouthReturnsToRestAfterSpeechEnds() {
        lipSync.onViseme(Viseme.A, 1f, 100);
        advance(0.12f);
        assertTrue(lipSync.isSpeaking());
        advance(1.5f);
        assertFalse("the mouth must be handed back after the queue drains", lipSync.isSpeaking());
        assertEquals(0f, lipSync.authority(), 0.02f);
    }

    @Test
    public void stopAbandonsQueuedSpeech() {
        for (int i = 0; i < 10; i++) lipSync.onViseme(Viseme.E, 1f, 200);
        advance(0.1f);
        lipSync.stop();
        advance(1.5f);
        assertFalse(lipSync.isSpeaking());
    }

    @Test
    public void speechAlsoMovesTheBrowsAndHead() {
        lipSync.onViseme(Viseme.A, 1f, 300);
        advance(0.2f);
        LeonPose pose = new LeonPose();
        lipSync.applyTo(pose);
        assertTrue("speech should lift the brows a little",
                pose.get(LeonChannel.BROW_L_RAISE) > 0.02f);
        assertTrue("speech should nod the head a little",
                pose.get(LeonChannel.HEAD_PITCH) < 0f);
    }

    @Test
    public void theQueueIsBoundedSoAProducerCannotGrowItWithoutLimit() {
        for (int i = 0; i < 500; i++) lipSync.onViseme(Viseme.O, 1f, 50);
        // Draining must terminate in a sane amount of time rather than replaying 500 events.
        advance(40f);
        assertFalse("a flooded queue must still drain", lipSync.isSpeaking());
    }

    @Test
    public void aNullVisemeIsIgnored() {
        lipSync.onViseme(null, 1f, 100);
        advance(0.3f);
        assertFalse(lipSync.isSpeaking());
    }

    @Test
    public void theSyntheticDriverProducesVaryingMouthShapes() {
        lipSync.setSyntheticDriver(true);
        java.util.Set<Viseme> seen = new java.util.HashSet<>();
        for (int i = 0; i < 60 * 8; i++) {
            lipSync.update(FRAME);
            seen.add(lipSync.activeViseme());
        }
        assertTrue("the placeholder driver must vary its shapes, saw " + seen, seen.size() >= 4);
        lipSync.setSyntheticDriver(false);
        advance(1.5f);
        assertFalse(lipSync.isSpeaking());
    }

    @Test
    public void theBusFansOneStreamOutToEverySurface() {
        LeonLipSyncController second = new LeonLipSyncController(99L);
        LeonVisemeBus bus = new LeonVisemeBus();
        bus.attach(lipSync);
        bus.attach(second);
        bus.attach(lipSync); // attaching twice must not double-deliver
        assertEquals(2, bus.attachedCount());

        bus.onViseme(Viseme.O, 1f, 200);
        advance(0.2f);
        for (int i = 0; i < 12; i++) second.update(FRAME);

        LeonPose a = new LeonPose();
        LeonPose b = new LeonPose();
        lipSync.applyTo(a);
        second.applyTo(b);
        assertTrue(a.get(LeonChannel.MOUTH_ROUND) > 0.2f);
        assertTrue(b.get(LeonChannel.MOUTH_ROUND) > 0.2f);

        bus.detach(second);
        assertEquals(1, bus.attachedCount());
    }
}
