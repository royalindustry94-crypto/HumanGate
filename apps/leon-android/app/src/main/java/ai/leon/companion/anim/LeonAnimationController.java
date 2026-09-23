package ai.leon.companion.anim;

import ai.leon.companion.rig.Rig;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;
import ai.leon.companion.util.Mathx;

/**
 * Turns a {@link LeonState} into rig motion. This is the layer the milestone's acceptance test
 * exercises: it holds the behaviour set, cross-fades state profiles, lets the lip-sync controller
 * take the mouth, and hands one finished pose per frame to {@link LeonRigBinder}.
 *
 * <p>Deliberately free of Android types so the whole animation system can be stepped and asserted in
 * plain JVM unit tests.
 */
public final class LeonAnimationController implements LeonStateController.Listener {
    /** Longest frame the controller will integrate in one go, so a stall cannot jolt the rig. */
    private static final float MAX_FRAME_SECONDS = 0.1f;

    private final LeonStateController states;
    private final LeonRigBinder binder;
    private final LeonLipSyncController lipSync;

    private final BreathBehaviour breath;
    private final BlinkBehaviour blink;
    private final HeadDriftBehaviour headDrift;
    private final GazeBehaviour gaze;
    private final PostureBehaviour posture;
    private final GestureBehaviour gesture;
    private final NodBehaviour nod;
    private final WalkBehaviour walk;

    private final LeonStateProfile fromProfile = new LeonStateProfile();
    private final LeonStateProfile toProfile = new LeonStateProfile();
    private final LeonStateProfile activeProfile = new LeonStateProfile();

    private final LeonPose workingPose = new LeonPose();

    private float transitionElapsed;
    private float transitionDuration;
    private boolean inTransition;
    private boolean holdingChinGesture;

    /** Scale applied on top of the profile, used for the minimised form factor. */
    private float formFactorScale = 1f;

    public LeonAnimationController(Rig rig, LeonStateController states, long seed) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        if (states == null) throw new IllegalArgumentException("state controller required");
        this.states = states;
        this.binder = new LeonRigBinder(rig);
        this.lipSync = new LeonLipSyncController(seed + 7);

        breath = new BreathBehaviour();
        blink = new BlinkBehaviour(seed + 101);
        headDrift = new HeadDriftBehaviour(seed + 211);
        gaze = new GazeBehaviour(seed + 307);
        posture = new PostureBehaviour(seed + 401);
        gesture = new GestureBehaviour(seed + 503);
        nod = new NodBehaviour(seed + 601);
        walk = new WalkBehaviour(seed + 701);

        LeonStateProfile initial = LeonStateProfile.forState(states.state());
        fromProfile.copyFrom(initial);
        toProfile.copyFrom(initial);
        activeProfile.copyFrom(initial);
        applyProfileToBehaviours(activeProfile);

        states.addListener(this);
        // Put the rig into a valid solved pose immediately so the first frame is never blank.
        update(0f);
    }

    public LeonLipSyncController lipSync() {
        return lipSync;
    }

    public LeonRigBinder binder() {
        return binder;
    }

    /**
     * Signed walking intensity, -1..1: +1 is full stride to the right, -1 full stride to the left,
     * 0 when not walking. The overlay is the only thing that turns this into an actual on-screen
     * window move (it owns the pixels-per-second speed) -- the rig itself has no notion of screen
     * position.
     */
    public float walkTravelFraction() {
        return walk.travelFraction();
    }

    public boolean isWalking() {
        return walk.isWalking();
    }

    /** The pose produced by the most recent {@link #update}. */
    public LeonPose pose() {
        return workingPose;
    }

    public boolean isInTransition() {
        return inTransition;
    }

    /** Sets the whole-rig scale used to shrink Leon in the minimised overlay. */
    public void setFormFactorScale(float scale) {
        this.formFactorScale = Mathx.clamp(scale, 0.2f, 2f);
    }

    /**
     * True while something is moving fast enough to justify a high frame rate. The render loop uses
     * this to drop to an idle cadence and save battery.
     */
    public boolean wantsHighFrameRate() {
        return inTransition
                || lipSync.isSpeaking()
                || gesture.isGesturing()
                || nod.isNodding()
                || blink.isBlinking()
                || walk.isWalking()
                || states.state() == LeonState.SPEAKING
                || states.state() == LeonState.ATTENTION;
    }

    /** Advances every behaviour and writes the resulting pose onto the rig. */
    public void update(float dtSeconds) {
        float dt = Mathx.clamp(dtSeconds, 0f, MAX_FRAME_SECONDS);

        states.tick();

        if (inTransition) {
            transitionElapsed += dt;
            float t = transitionDuration <= 0f ? 1f : transitionElapsed / transitionDuration;
            if (t >= 1f) {
                t = 1f;
                inTransition = false;
                fromProfile.copyFrom(toProfile);
            }
            LeonStateProfile.lerp(fromProfile, toProfile, Mathx.easeInOut(t), activeProfile);
        } else {
            activeProfile.copyFrom(toProfile);
        }
        applyProfileToBehaviours(activeProfile);

        workingPose.copyFrom(activeProfile.basePose);

        // Independent behaviours, each adding its own contribution.
        breath.update(dt, activeProfile.breathWeight, workingPose);
        blink.update(dt, activeProfile.blinkWeight, workingPose);
        headDrift.update(dt, activeProfile.driftWeight, workingPose);
        gaze.update(dt, activeProfile.gazeWeight, workingPose);
        posture.update(dt, activeProfile.postureWeight, workingPose);
        gesture.update(dt, activeProfile.gestureWeight, workingPose);
        nod.update(dt, activeProfile.nodWeight, workingPose);
        walk.update(dt, activeProfile.walkWeight, workingPose);

        // Speech owns the mouth last, so it wins over the expression underneath it.
        lipSync.update(dt);
        lipSync.applyTo(workingPose);

        workingPose.set(LeonChannel.RIG_SCALE,
                workingPose.get(LeonChannel.RIG_SCALE) * formFactorScale);
        workingPose.clampAll();

        binder.apply(workingPose);
    }

    /**
     * Reacts to Leon being touched. {@code relX}/{@code relY} are -1..1 positions within his
     * bounds, so he looks towards where he was actually tapped.
     */
    public void onTouched(float relX, float relY) {
        headDrift.glanceAt(-relX * 0.5f, relY * 0.35f, 1.4f);
        gaze.lookAt(-relX * 0.8f, relY * 0.6f, 1.2f);
        blink.triggerBlink();
        nod.trigger(0.42f);
        walk.cancel();
        if (!states.isMinimised()) {
            gesture.trigger(GestureBehaviour.Kind.POINT);
        }
    }

    /** Forces a blink, e.g. when the overlay becomes visible again after unlock. */
    public void triggerBlink() {
        blink.triggerBlink();
    }

    /** Fires a one-off gesture, for the control centre's developer buttons. */
    public void triggerGesture(GestureBehaviour.Kind kind) {
        gesture.trigger(kind);
    }

    @Override
    public void onLeonStateChanged(LeonState previous, LeonState current) {
        fromProfile.copyFrom(activeProfile);
        toProfile.copyFrom(LeonStateProfile.forState(current));
        transitionDuration = Math.max(0.05f, toProfile.transitionSeconds);
        transitionElapsed = 0f;
        inTransition = true;

        if (previous == LeonState.THINKING && holdingChinGesture) {
            gesture.release();
            holdingChinGesture = false;
        }

        if (current != LeonState.IDLE) {
            // A walk only belongs to idle time; anything else (a new message, a touch) should pull
            // Leon's attention back and let the legs settle out rather than keep wandering.
            walk.cancel();
        }

        switch (current) {
            case THINKING:
                gesture.trigger(GestureBehaviour.Kind.CHIN, true);
                holdingChinGesture = true;
                break;
            case SPEAKING:
                // No speech pipeline exists yet, so the test driver supplies the viseme stream.
                lipSync.setSyntheticDriver(true);
                break;
            case ATTENTION:
                blink.triggerBlink();
                nod.trigger(0.5f);
                break;
            case LISTENING:
                headDrift.glanceAt(0f, 0.1f, 1.2f);
                gaze.lookAt(0f, 0.1f, 1.5f);
                break;
            default:
                break;
        }

        if (previous == LeonState.SPEAKING && current != LeonState.SPEAKING) {
            lipSync.setSyntheticDriver(false);
        }
    }

    private void applyProfileToBehaviours(LeonStateProfile p) {
        breath.setRate(p.breathRate);
        breath.setDepth(p.breathDepth);
        blink.setIntervalRange(p.blinkMin, p.blinkMax);
        headDrift.setDriftAmount(p.driftAmount);
        headDrift.setGlanceInterval(p.glanceMin, p.glanceMax);
        headDrift.setFocus(p.focusYaw, p.focusPitch);
        gaze.setRange(p.gazeRange);
        gaze.setHoldRange(p.gazeHoldMin, p.gazeHoldMax);
        gaze.setFocus(p.gazeFocusX, p.gazeFocusY);
        posture.setAmount(p.postureAmount);
        posture.setShiftInterval(p.postureShiftMin, p.postureShiftMax);
        gesture.setAutoGesture(p.gestureAuto > 0.5f, p.gestureMin, p.gestureMax);
        nod.setAutoNod(p.nodAuto > 0.5f, p.nodMin, p.nodMax);
        walk.setAutoWalk(p.walkAuto > 0.5f, p.walkMin, p.walkMax);
    }

    /**
     * Returns every behaviour to a clean start without changing state. Called when the screen comes
     * back on, so timers that expired while it was off do not all fire at once on the first frame.
     */
    public void resetBehaviours() {
        breath.reset();
        blink.reset();
        headDrift.reset();
        gaze.reset();
        posture.reset();
        gesture.reset();
        nod.reset();
        walk.reset();
        lipSync.reset();
        holdingChinGesture = false;
    }

    /** Detaches from the state controller. Call when the overlay view is destroyed. */
    public void release() {
        states.removeListener(this);
        lipSync.stop();
    }
}
