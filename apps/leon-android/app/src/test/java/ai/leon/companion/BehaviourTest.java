package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.BlinkBehaviour;
import ai.leon.companion.anim.BreathBehaviour;
import ai.leon.companion.anim.GestureBehaviour;
import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.NodBehaviour;
import ai.leon.companion.anim.PostureBehaviour;
import ai.leon.companion.util.Mathx;
import ai.leon.companion.util.SmoothNoise;
import ai.leon.companion.util.Spring1D;

import org.junit.Test;

/** Each behaviour on its own, so a regression can be located rather than just observed. */
public class BehaviourTest {
    private static final float FRAME = 1f / 60f;

    @Test
    public void breathCompletesAFullCycleAtTheConfiguredRate() {
        BreathBehaviour breath = new BreathBehaviour();
        breath.setRate(30f); // two seconds per breath
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < 120; i++) {
            LeonPose pose = new LeonPose().clear();
            breath.update(FRAME, 1f, pose);
            float v = pose.get(LeonChannel.CHEST_BREATH);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue("a two-second breath must complete in two seconds", max > 0.9f);
        assertTrue(min < 0.1f);
    }

    @Test
    public void theInhaleIsQuickerThanTheExhale() {
        BreathBehaviour breath = new BreathBehaviour();
        breath.setRate(60f); // one second per breath
        float peakAt = -1f;
        for (int i = 0; i < 60; i++) {
            LeonPose pose = new LeonPose().clear();
            breath.update(FRAME, 1f, pose);
            if (pose.get(LeonChannel.CHEST_BREATH) > 0.99f && peakAt < 0f) peakAt = i / 60f;
        }
        assertTrue("the inhale must take under half the cycle, peaked at " + peakAt,
                peakAt > 0f && peakAt < 0.5f);
    }

    @Test
    public void breathIsFrameRateIndependent() {
        BreathBehaviour fast = new BreathBehaviour();
        BreathBehaviour slow = new BreathBehaviour();
        fast.setRate(30f);
        slow.setRate(30f);
        for (int i = 0; i < 120; i++) fast.update(1f / 60f, 1f, new LeonPose());
        for (int i = 0; i < 40; i++) slow.update(1f / 20f, 1f, new LeonPose());
        assertEquals("60fps and 20fps must reach the same phase after two seconds",
                fast.phase(), slow.phase(), 0.02f);
    }

    @Test
    public void theBlinkCurveClosesThenOpens() {
        assertEquals(0f, BlinkBehaviour.curve(0f), 0.0001f);
        assertTrue("the lid must be shut mid-blink", BlinkBehaviour.curve(0.07f) > 0.9f);
        assertEquals("the lid must be open again by the end", 0f, BlinkBehaviour.curve(0.2f), 0.0001f);
    }

    @Test
    public void blinksHappenInsideTheConfiguredInterval() {
        BlinkBehaviour blink = new BlinkBehaviour(7L);
        blink.setIntervalRange(1f, 2f);
        int blinks = 0;
        boolean closed = false;
        for (int i = 0; i < 60 * 20; i++) {
            LeonPose pose = new LeonPose().clear();
            blink.update(FRAME, 1f, pose);
            boolean nowClosed = pose.get(LeonChannel.BLINK_L) > 0.6f;
            if (nowClosed && !closed) blinks++;
            closed = nowClosed;
        }
        assertTrue("a 1-2s interval over 20s should give several blinks, saw " + blinks,
                blinks >= 8 && blinks <= 24);
    }

    @Test
    public void theTwoLidsAreOffsetFromEachOther() {
        BlinkBehaviour blink = new BlinkBehaviour(11L);
        blink.triggerBlink();
        boolean sawDifference = false;
        for (int i = 0; i < 20; i++) {
            LeonPose pose = new LeonPose().clear();
            blink.update(FRAME, 1f, pose);
            if (Math.abs(pose.get(LeonChannel.BLINK_L) - pose.get(LeonChannel.BLINK_R)) > 0.05f) {
                sawDifference = true;
            }
        }
        assertTrue("the lids must not move in perfect lockstep", sawDifference);
    }

    @Test
    public void aTriggeredBlinkFiresImmediately() {
        BlinkBehaviour blink = new BlinkBehaviour(3L);
        blink.setIntervalRange(30f, 40f);
        blink.triggerBlink();
        boolean closed = false;
        for (int i = 0; i < 12; i++) {
            LeonPose pose = new LeonPose().clear();
            blink.update(FRAME, 1f, pose);
            if (pose.get(LeonChannel.BLINK_L) > 0.8f) closed = true;
        }
        assertTrue("a triggered blink must not wait for the interval", closed);
    }

    @Test
    public void aWeightShiftAlwaysMovesToTheOtherSide() {
        PostureBehaviour posture = new PostureBehaviour(17L);
        posture.setShiftInterval(1f, 1.1f);
        java.util.List<Float> extremes = new java.util.ArrayList<>();
        float previous = 0f;
        boolean rising = true;
        for (int i = 0; i < 60 * 30; i++) {
            LeonPose pose = new LeonPose().clear();
            posture.update(FRAME, 1f, pose);
            float v = pose.get(LeonChannel.WEIGHT_SHIFT);
            if (rising && v < previous) {
                extremes.add(previous);
                rising = false;
            } else if (!rising && v > previous) {
                extremes.add(previous);
                rising = true;
            }
            previous = v;
        }
        boolean sawBothSides = false;
        boolean sawPositive = false;
        boolean sawNegative = false;
        for (float v : extremes) {
            if (v > 0.1f) sawPositive = true;
            if (v < -0.1f) sawNegative = true;
        }
        sawBothSides = sawPositive && sawNegative;
        assertTrue("weight must travel to both sides, extremes " + extremes, sawBothSides);
    }

    @Test
    public void theGestureEnvelopeRisesHoldsAndFalls() {
        assertTrue(GestureBehaviour.envelope(0f) < 0.05f);
        assertTrue("a gesture must be fully out during its hold",
                GestureBehaviour.envelope(0.4f) > 0.7f);
        assertTrue(GestureBehaviour.envelope(1f) < 0.05f);
    }

    @Test
    public void aHeldGestureStaysOutUntilReleased() {
        GestureBehaviour gesture = new GestureBehaviour(23L);
        gesture.trigger(GestureBehaviour.Kind.CHIN, true);
        for (int i = 0; i < 60 * 10; i++) gesture.update(FRAME, 1f, new LeonPose());
        assertTrue("a held gesture must not time out", gesture.isGesturing());

        LeonPose pose = new LeonPose().clear();
        gesture.update(FRAME, 1f, pose);
        assertTrue("a held chin gesture must keep the hand up",
                pose.get(LeonChannel.HAND_R_RAISE) > 0.3f);

        gesture.release();
        for (int i = 0; i < 60 * 3; i++) gesture.update(FRAME, 1f, new LeonPose());
        assertFalse("a released gesture must fall away", gesture.isGesturing());
    }

    @Test
    public void aNodIsADecayingOscillationNotASingleDip() {
        NodBehaviour nod = new NodBehaviour(31L);
        nod.trigger(0.5f);
        int signChanges = 0;
        float previous = 0f;
        float peak = 0f;
        for (int i = 0; i < 60; i++) {
            LeonPose pose = new LeonPose().clear();
            nod.update(FRAME, 1f, pose);
            float v = pose.get(LeonChannel.HEAD_PITCH);
            if (previous != 0f && Math.signum(v) != Math.signum(previous)) signChanges++;
            peak = Math.max(peak, Math.abs(v));
            previous = v;
        }
        assertTrue("a nod must swing back and forth, saw " + signChanges + " reversals",
                signChanges >= 2);
        assertTrue(peak > 0.2f);
    }

    @Test
    public void aSpringSettlesOnItsTargetAndIsFrameRateIndependent() {
        Spring1D fast = new Spring1D(0f, 60f, 14f);
        Spring1D slow = new Spring1D(0f, 60f, 14f);
        for (int i = 0; i < 120; i++) fast.update(1f, 1f / 60f);
        for (int i = 0; i < 40; i++) slow.update(1f, 1f / 20f);
        assertEquals(1f, fast.value(), 0.02f);
        assertEquals("the spring must not depend on the frame rate", fast.value(), slow.value(), 0.03f);
    }

    @Test
    public void aLongSpringStepCannotExplode() {
        Spring1D spring = new Spring1D(0f, 400f, 10f);
        spring.update(1f, 5f);
        assertTrue("a five-second step must stay bounded, got " + spring.value(),
                Math.abs(spring.value()) < 10f);
    }

    @Test
    public void smoothNoiseIsBoundedAndDeterministicForASeed() {
        SmoothNoise a = new SmoothNoise(5L, 0.05f, 3);
        SmoothNoise b = new SmoothNoise(5L, 0.05f, 3);
        for (int i = 0; i < 600; i++) {
            float va = a.update(FRAME);
            float vb = b.update(FRAME);
            assertTrue("noise must stay in range, got " + va, va >= -1f && va <= 1f);
            assertEquals("the same seed must give the same sequence", va, vb, 0.0001f);
        }
    }

    @Test
    public void mathHelpersBehaveAtTheirEdges() {
        assertEquals(0f, Mathx.clamp01(-5f), 0f);
        assertEquals(1f, Mathx.clamp01(5f), 0f);
        assertEquals(0f, Mathx.inverseLerp(3f, 3f, 3f), 0f);
        assertEquals(0.5f, Mathx.easeInOut(0.5f), 0.0001f);
        assertEquals(1f, Mathx.easeInOut(2f), 0.0001f);
        assertEquals("approach with no time must not move", 0f, Mathx.approach(0f, 1f, 0.1f, 0f), 0.0001f);
        assertEquals("approach over one half-life must cover half the distance",
                0.5f, Mathx.approach(0f, 1f, 0.1f, 0.1f), 0.01f);
    }
}
