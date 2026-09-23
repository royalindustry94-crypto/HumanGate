package ai.leon.companion.anim;

/**
 * The rig's control surface. Everything that can move Leon goes through one of these channels:
 * behaviours, state base poses, expressions and lip sync all write here, and
 * {@code LeonRigBinder} is the only code that knows how a channel reaches a bone or a part.
 *
 * <p>That indirection is what lets the visual asset be replaced — a production Leon rig only has to
 * satisfy the same channels, not the same bitmap layout.
 *
 * <p>Unless noted, the usable range is 0..1 for "amount" channels and -1..1 for signed ones.
 */
public enum LeonChannel {
    // ---- head (signed) ----
    /** Head turn left/right. */
    HEAD_YAW,
    /** Head nod up/down. */
    HEAD_PITCH,
    /** Head tilt. */
    HEAD_ROLL,
    /** Neck counter-rotation, keeps the turn from looking like a rigid stick. */
    NECK_YAW,

    // ---- eyes ----
    /** Signed gaze direction. */
    EYE_LOOK_X,
    EYE_LOOK_Y,
    /** 0 open, 1 fully closed. Independent per eye so winks and offset blinks are possible. */
    BLINK_L,
    BLINK_R,

    // ---- brows ----
    BROW_L_RAISE,
    BROW_R_RAISE,
    BROW_L_LOWER,
    BROW_R_LOWER,

    // ---- mouth / visemes ----
    /** Vertical mouth aperture. */
    JAW_DROP,
    /** Rounded lip aperture (O/U). */
    MOUTH_ROUND,
    /** Horizontal stretch (E/I). */
    MOUTH_WIDE,
    /** Lips pressed together (M/B/P). */
    MOUTH_PRESS,
    /** Lower lip to teeth (F/V). */
    MOUTH_TEETH,
    MOUTH_SMILE,
    MOUTH_FROWN,

    // ---- torso ----
    /** Breath cycle, 0 exhaled to 1 inhaled. */
    CHEST_BREATH,
    TORSO_LEAN_X,
    TORSO_LEAN_Y,
    TORSO_TWIST,
    /** Signed hip/weight shift. */
    WEIGHT_SHIFT,
    SHOULDER_L,
    SHOULDER_R,

    // ---- arms ----
    ARM_L_SWING,
    ARM_R_SWING,
    ELBOW_L,
    ELBOW_R,
    HAND_L_RAISE,
    HAND_R_RAISE,

    // ---- legs (walking) ----
    /** Forward/back thigh swing, signed: positive is stepping forward. */
    THIGH_L_SWING,
    THIGH_R_SWING,
    /** Knee bend, 0..1: only bends forward, so this is never signed. */
    SHIN_L_SWING,
    SHIN_R_SWING,
    /** Small vertical hip bob synced to the gait, 0..1. */
    WALK_BOB,

    // ---- secondary motion ----
    HOOD_SWAY,
    NECKLACE_SWAY,

    // ---- expression blend weights (drive brow/mouth art swaps) ----
    EXPR_NEUTRAL,
    EXPR_SMILE,
    EXPR_SERIOUS,
    EXPR_THINK,
    EXPR_LISTEN,
    EXPR_SLEEPY,

    // ---- presentation ----
    /** State feedback ring intensity behind Leon; never a substitute for character animation. */
    AURA,
    /** Whole-rig scale, used only for the minimised form factor. */
    RIG_SCALE;

    public static final int COUNT = values().length;

    private static final LeonChannel[] VALUES = values();

    public static LeonChannel at(int ordinal) {
        return VALUES[ordinal];
    }
}
