package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.Viseme;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

import org.junit.Before;
import org.junit.Test;

/**
 * Whole-system behaviour: Leon must be visibly alive with no interaction, must blink, must breathe,
 * and must not loop identically.
 */
public class LeonAnimationControllerTest {
    private static final float FRAME = 1f / 60f;

    private Rig rig;
    private LeonStateController states;
    private LeonAnimationController controller;

    @Before
    public void setUp() {
        rig = LeonRig.build();
        states = new LeonStateController();
        controller = new LeonAnimationController(rig, states, 20260922L);
    }

    private void advance(float seconds) {
        int frames = Math.max(1, Math.round(seconds / FRAME));
        for (int i = 0; i < frames; i++) controller.update(FRAME);
    }

    @Test
    public void theRigIsSolvedBeforeTheFirstUpdate() {
        // The constructor primes a pose, so the very first frame is never a blank or broken rig.
        assertTrue(rig.part(LeonRig.Parts.HEAD_BASE).isVisible());
        assertEquals(1f, rig.bone(LeonRig.Bones.ROOT).scaleX, 0.0001f);
    }

    @Test
    public void leonBreathesWhileIdleWithNoInteraction() {
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < 60 * 12; i++) {
            controller.update(FRAME);
            float breath = controller.pose().get(LeonChannel.CHEST_BREATH);
            min = Math.min(min, breath);
            max = Math.max(max, breath);
        }
        assertTrue("an idle Leon must breathe through a full cycle", max - min > 0.5f);
    }

    @Test
    public void leonBlinksWithoutBeingAsked() {
        int blinks = 0;
        boolean closed = false;
        for (int i = 0; i < 60 * 40; i++) {
            controller.update(FRAME);
            boolean nowClosed = controller.pose().get(LeonChannel.BLINK_L) > 0.6f;
            if (nowClosed && !closed) blinks++;
            closed = nowClosed;
        }
        assertTrue("an idle Leon must blink within 40 seconds, saw " + blinks, blinks >= 3);
    }

    @Test
    public void theHeadAndEyesMoveIndependentlyOfEachOther() {
        float headRange = 0f;
        float gazeRange = 0f;
        float headMin = Float.MAX_VALUE, headMax = -Float.MAX_VALUE;
        float gazeMin = Float.MAX_VALUE, gazeMax = -Float.MAX_VALUE;
        for (int i = 0; i < 60 * 25; i++) {
            controller.update(FRAME);
            float head = controller.pose().get(LeonChannel.HEAD_YAW);
            float gaze = controller.pose().get(LeonChannel.EYE_LOOK_X);
            headMin = Math.min(headMin, head);
            headMax = Math.max(headMax, head);
            gazeMin = Math.min(gazeMin, gaze);
            gazeMax = Math.max(gazeMax, gaze);
        }
        headRange = headMax - headMin;
        gazeRange = gazeMax - gazeMin;
        assertTrue("the head must drift, range was " + headRange, headRange > 0.08f);
        assertTrue("the eyes must saccade, range was " + gazeRange, gazeRange > 0.2f);
    }

    @Test
    public void idleMotionDoesNotRepeatIdentically() {
        float[] first = sampleWindow(6f);
        float[] second = sampleWindow(6f);
        boolean identical = true;
        for (int i = 0; i < first.length; i++) {
            if (Math.abs(first[i] - second[i]) > 0.0005f) {
                identical = false;
                break;
            }
        }
        assertFalse("idle motion must not be a fixed loop", identical);
    }

    private float[] sampleWindow(float seconds) {
        int frames = Math.round(seconds / FRAME);
        float[] samples = new float[frames];
        for (int i = 0; i < frames; i++) {
            controller.update(FRAME);
            samples[i] = controller.pose().get(LeonChannel.HEAD_YAW)
                    + controller.pose().get(LeonChannel.WEIGHT_SHIFT) * 3f;
        }
        return samples;
    }

    @Test
    public void aStateChangeCrossFadesRatherThanSnapping() {
        advance(1f);
        states.request(LeonState.SERIOUS);
        assertTrue(controller.isInTransition());
        controller.update(FRAME);
        float early = controller.pose().get(LeonChannel.BROW_L_LOWER);
        advance(2f);
        assertFalse("the transition must finish", controller.isInTransition());
        float settled = controller.pose().get(LeonChannel.BROW_L_LOWER);
        assertTrue("the serious brow must be lowered once settled", settled > 0.5f);
        assertTrue("the brow must not arrive instantly", early < settled - 0.15f);
    }

    @Test
    public void thinkingTiltsTheHeadAndRaisesAHandTowardsTheChin() {
        states.request(LeonState.THINKING);
        advance(2.5f);
        assertTrue("thinking must tilt the head",
                Math.abs(controller.pose().get(LeonChannel.HEAD_ROLL)) > 0.15f);
        assertTrue("thinking must hold a hand up",
                controller.pose().get(LeonChannel.HAND_R_RAISE) > 0.3f);
    }

    @Test
    public void listeningQuietensTheBodyComparedToSpeaking() {
        states.request(LeonState.LISTENING);
        advance(3f);
        float listeningMotion = motionOver(12f);

        states.request(LeonState.SPEAKING);
        advance(3f);
        float speakingMotion = motionOver(12f);

        assertTrue("speaking must move more than listening: " + listeningMotion + " vs "
                + speakingMotion, speakingMotion > listeningMotion);
    }

    private float motionOver(float seconds) {
        int frames = Math.round(seconds / FRAME);
        float previous = controller.pose().get(LeonChannel.HEAD_YAW);
        float total = 0f;
        for (int i = 0; i < frames; i++) {
            controller.update(FRAME);
            float now = controller.pose().get(LeonChannel.HEAD_YAW);
            total += Math.abs(now - previous);
            previous = now;
        }
        return total;
    }

    @Test
    public void speakingAnimatesTheMouth() {
        states.request(LeonState.SPEAKING);
        float jawMin = Float.MAX_VALUE;
        float jawMax = -Float.MAX_VALUE;
        for (int i = 0; i < 60 * 6; i++) {
            controller.update(FRAME);
            float jaw = controller.pose().get(LeonChannel.JAW_DROP);
            jawMin = Math.min(jawMin, jaw);
            jawMax = Math.max(jawMax, jaw);
        }
        assertTrue("the mouth must open and close while speaking", jawMax - jawMin > 0.3f);
        assertTrue("speaking must use the lip-sync path", controller.lipSync().isSpeaking());
    }

    @Test
    public void leavingSpeakingReleasesTheMouthBackToTheExpression() {
        states.request(LeonState.SPEAKING);
        advance(2f);
        states.request(LeonState.IDLE);
        advance(2f);
        assertFalse("the lip-sync driver must stop with the state",
                controller.lipSync().hasSyntheticDriver());
        assertEquals("the mouth must be handed back", 0f, controller.lipSync().authority(), 0.02f);
    }

    @Test
    public void externalVisemeEventsDriveTheMouthDirectly() {
        states.request(LeonState.IDLE);
        advance(1f);
        controller.lipSync().onViseme(Viseme.A, 1f, 200);
        advance(0.15f);
        assertTrue("an injected viseme must open the jaw",
                controller.pose().get(LeonChannel.JAW_DROP) > 0.3f);
    }

    @Test
    public void tappingLeonMakesHimReact() {
        advance(1f);
        float beforeGaze = controller.pose().get(LeonChannel.EYE_LOOK_X);
        controller.onTouched(0.9f, -0.5f);
        advance(0.2f);
        assertNotEquals("a tap must move the gaze", beforeGaze,
                controller.pose().get(LeonChannel.EYE_LOOK_X));
        assertTrue("a tap must raise the frame rate", controller.wantsHighFrameRate());
    }

    @Test
    public void anIdleLeonDropsToTheLowFrameRate() {
        states.request(LeonState.IDLE);
        advance(3f);
        // Step past any in-flight blink or gesture so the loop can settle.
        boolean sawIdleFrame = false;
        for (int i = 0; i < 60 * 8; i++) {
            controller.update(FRAME);
            if (!controller.wantsHighFrameRate()) {
                sawIdleFrame = true;
                break;
            }
        }
        assertTrue("an idle Leon must allow the render loop to slow down", sawIdleFrame);
    }

    @Test
    public void minimisedLeonIsStillAnimated() {
        states.request(LeonState.MINIMISED);
        controller.setFormFactorScale(1f);
        advance(2f);
        float breathMin = Float.MAX_VALUE;
        float breathMax = -Float.MAX_VALUE;
        for (int i = 0; i < 60 * 12; i++) {
            controller.update(FRAME);
            float breath = controller.pose().get(LeonChannel.CHEST_BREATH);
            breathMin = Math.min(breathMin, breath);
            breathMax = Math.max(breathMax, breath);
        }
        assertTrue("a minimised Leon must still breathe", breathMax - breathMin > 0.4f);
    }

    @Test
    public void aLongStalledFrameCannotJoltTheRig() {
        advance(1f);
        float before = controller.pose().get(LeonChannel.CHEST_BREATH);
        // Simulate the render loop being starved for two seconds.
        controller.update(2f);
        float after = controller.pose().get(LeonChannel.CHEST_BREATH);
        assertTrue("a stalled frame must be clamped, jumped by " + Math.abs(after - before),
                Math.abs(after - before) < 0.35f);
    }

    @Test
    public void everyStateCanBeEnteredAndSteppedWithoutError() {
        for (LeonState state : LeonState.values()) {
            if (state.isTransient()) continue;
            states.request(state);
            advance(1.5f);
            assertEquals(state, states.state());
        }
    }

    @Test
    public void onlyIdleEverWandersOffOnItsOwn() {
        states.request(LeonState.SPEAKING);
        // Longer than IDLE's shortest auto-walk interval, so if walking ever leaked into another
        // state this would catch it rather than just being too short to notice.
        advance(60f);
        assertFalse("Leon must not wander while speaking", controller.isWalking());
    }

    @Test
    public void touchingLeonCancelsAWalkAlreadyUnderway() {
        states.request(LeonState.IDLE);
        boolean walked = false;
        for (int i = 0; i < 60 * 115 && !walked; i++) {
            controller.update(FRAME);
            walked = controller.isWalking();
        }
        assertTrue("an idle Leon must eventually start wandering on its own", walked);

        controller.onTouched(0.5f, 0f);
        advance(2f);
        assertFalse("grabbing Leon must stop a walk in progress", controller.isWalking());
    }

    @Test
    public void theFormFactorScaleShrinksTheRigRootOnly() {
        controller.setFormFactorScale(0.5f);
        advance(0.2f);
        assertEquals(0.5f, rig.bone(LeonRig.Bones.ROOT).scaleX, 0.01f);
        assertEquals("shrinking must not squash the chest independently", 1f,
                rig.bone(LeonRig.Bones.CHEST).scaleX, 0.1f);
    }
}
