package ai.leon.companion.rig;

/**
 * The canonical Leon skeleton and layer stack, authored in a 512x768 design space with the origin
 * at the top-left and +y pointing down (Canvas convention). The root sits at ground level so a
 * weight shift or the minimised scale pivots at Leon's feet rather than his middle.
 *
 * <p>This class defines <em>structure only</em> — no artwork. Bitmaps are resolved later from
 * {@link RigPart#artKey}, which is how the placeholder development rig and finished production art
 * can share one skeleton, one binder and one state machine.
 *
 * <p>Character spec encoded here: bald head with a tattoo wrapping the side and back of the skull,
 * dark sunglasses, neck and chest tattoos, arm tattoos, an earring, a multicolour gemstone
 * necklace, a black hoodie worn with the hood <em>down</em> behind the shoulders, black pants and
 * white sneakers.
 */
public final class LeonRig {
    public static final float DESIGN_W = 512f;
    public static final float DESIGN_H = 768f;

    // Vertical landmarks in design space.
    private static final float GROUND_Y = 762f;
    private static final float HIP_Y = 470f;
    private static final float CHEST_Y = 372f;
    private static final float NECK_Y = 280f;
    private static final float HEAD_Y = 238f;

    private static final float CENTRE_X = 256f;

    private LeonRig() {}

    public static Rig build() {
        Rig.Builder b = new Rig.Builder().design(DESIGN_W, DESIGN_H);

        // ---------------- skeleton (parent before child) ----------------
        b.root(Bones.ROOT, CENTRE_X, GROUND_Y);
        b.bone(Bones.HIPS, Bones.ROOT, 0f, HIP_Y - GROUND_Y);
        b.bone(Bones.SPINE, Bones.HIPS, 0f, -52f);
        b.bone(Bones.CHEST, Bones.SPINE, 0f, CHEST_Y - HIP_Y + 52f);
        b.bone(Bones.NECK, Bones.CHEST, 0f, NECK_Y - CHEST_Y);
        b.bone(Bones.HEAD, Bones.NECK, 0f, HEAD_Y - NECK_Y);

        // Head sub-joints. Everything here inherits the head turn for free.
        b.bone(Bones.JAW, Bones.HEAD, 0f, 46f);
        b.bone(Bones.BROW_L, Bones.HEAD, -36f, -22f);
        b.bone(Bones.BROW_R, Bones.HEAD, 36f, -22f);
        b.bone(Bones.EYE_L, Bones.HEAD, -33f, 2f);
        b.bone(Bones.EYE_R, Bones.HEAD, 33f, 2f);

        // Hood sits on the chest, behind everything, because it is worn DOWN.
        b.bone(Bones.HOOD, Bones.CHEST, 0f, -34f);
        b.bone(Bones.NECKLACE, Bones.CHEST, 0f, -40f);

        // Arms. Screen-left arm is layered behind the torso, screen-right in front, so both read.
        b.bone(Bones.SHOULDER_L, Bones.CHEST, -82f, -14f);
        b.bone(Bones.ARM_L, Bones.SHOULDER_L, 0f, 6f);
        b.bone(Bones.FOREARM_L, Bones.ARM_L, 0f, 96f);
        b.bone(Bones.HAND_L, Bones.FOREARM_L, 0f, 86f);

        b.bone(Bones.SHOULDER_R, Bones.CHEST, 82f, -14f);
        b.bone(Bones.ARM_R, Bones.SHOULDER_R, 0f, 6f);
        b.bone(Bones.FOREARM_R, Bones.ARM_R, 0f, 96f);
        b.bone(Bones.HAND_R, Bones.FOREARM_R, 0f, 86f);

        // Legs.
        b.bone(Bones.THIGH_L, Bones.HIPS, -44f, 14f);
        b.bone(Bones.SHIN_L, Bones.THIGH_L, 0f, 132f);
        b.bone(Bones.FOOT_L, Bones.SHIN_L, 0f, 128f);
        b.bone(Bones.THIGH_R, Bones.HIPS, 44f, 14f);
        b.bone(Bones.SHIN_R, Bones.THIGH_R, 0f, 132f);
        b.bone(Bones.FOOT_R, Bones.SHIN_R, 0f, 128f);

        // ---------------- layers, back to front ----------------
        // Presentation-only state ring. Never carries character animation.
        b.part(Parts.AURA, Art.AURA, Bones.CHEST, 0f, 30f, 420f, 420f, 0.5f, 0.5f, 0f, 0, null);

        b.part(Parts.HOOD_DOWN, Art.HOOD_DOWN, Bones.HOOD, 0f, 26f, 190f, 128f, 0.5f, 0.12f, 0f, 5, null);

        // Legs and sneakers.
        b.part(Parts.THIGH_L, Art.PANT_LEG, Bones.THIGH_L, 0f, 66f, 60f, 148f, 0.5f, 0.5f, 0f, 10, null);
        b.part(Parts.THIGH_R, Art.PANT_LEG, Bones.THIGH_R, 0f, 66f, 60f, 148f, 0.5f, 0.5f, 0f, 10, null);
        b.part(Parts.SHIN_L, Art.PANT_SHIN, Bones.SHIN_L, 0f, 62f, 52f, 140f, 0.5f, 0.5f, 0f, 11, null);
        b.part(Parts.SHIN_R, Art.PANT_SHIN, Bones.SHIN_R, 0f, 62f, 52f, 140f, 0.5f, 0.5f, 0f, 11, null);
        b.part(Parts.SHOE_L, Art.SNEAKER_L, Bones.FOOT_L, -4f, 14f, 84f, 44f, 0.5f, 0.5f, 0f, 12, null);
        b.part(Parts.SHOE_R, Art.SNEAKER_R, Bones.FOOT_R, 4f, 14f, 84f, 44f, 0.5f, 0.5f, 0f, 12, null);

        // Screen-left arm, behind the torso.
        b.part(Parts.ARM_L_UPPER, Art.SLEEVE_UPPER, Bones.ARM_L, 0f, 48f, 56f, 112f, 0.5f, 0.42f, 0f, 14, null);
        b.part(Parts.ARM_L_FORE, Art.FOREARM_TATTOO_L, Bones.FOREARM_L, 0f, 43f, 46f, 100f, 0.5f, 0.42f, 0f, 15, null);
        b.part(Parts.HAND_L, Art.HAND_L, Bones.HAND_L, 0f, 20f, 46f, 52f, 0.5f, 0.3f, 0f, 16, null);

        // Torso and skin.
        b.part(Parts.TORSO, Art.HOODIE_BODY, Bones.CHEST, 0f, 74f, 190f, 232f, 0.5f, 0.28f, 0f, 20, null);
        b.part(Parts.HOODIE_POCKET, Art.HOODIE_POCKET, Bones.CHEST, 0f, 156f, 136f, 58f, 0.5f, 0.5f, 0f, 21, null);
        b.part(Parts.NECK, Art.NECK_SKIN, Bones.NECK, 0f, 12f, 62f, 74f, 0.5f, 0.5f, 0f, 22, null);
        b.part(Parts.NECK_TATTOO, Art.NECK_TATTOO, Bones.NECK, 0f, 14f, 62f, 70f, 0.5f, 0.5f, 0f, 23, null);
        b.part(Parts.CHEST_TATTOO, Art.CHEST_TATTOO, Bones.CHEST, 0f, 18f, 104f, 54f, 0.5f, 0.5f, 0f, 24, null);
        b.part(Parts.NECKLACE, Art.NECKLACE, Bones.NECKLACE, 0f, 44f, 116f, 96f, 0.5f, 0.08f, 0f, 25, null);

        // Screen-right arm, in front of the torso.
        b.part(Parts.ARM_R_UPPER, Art.SLEEVE_UPPER, Bones.ARM_R, 0f, 48f, 56f, 112f, 0.5f, 0.42f, 0f, 30, null);
        b.part(Parts.ARM_R_FORE, Art.FOREARM_TATTOO_R, Bones.FOREARM_R, 0f, 43f, 46f, 100f, 0.5f, 0.42f, 0f, 31, null);
        b.part(Parts.HAND_R, Art.HAND_R, Bones.HAND_R, 0f, 20f, 46f, 52f, 0.5f, 0.3f, 0f, 32, null);

        // Head stack.
        b.part(Parts.HEAD_BASE, Art.HEAD_BALD, Bones.HEAD, 0f, 0f, 136f, 168f, 0.5f, 0.5f, 0f, 35, null);
        b.part(Parts.HEAD_TATTOO, Art.HEAD_TATTOO_WRAP, Bones.HEAD, 0f, -14f, 136f, 120f, 0.5f, 0.5f, 0f, 36, null);
        b.part(Parts.EAR_L, Art.EAR_L, Bones.HEAD, -64f, 8f, 26f, 44f, 0.5f, 0.5f, 0f, 37, null);
        b.part(Parts.EAR_R, Art.EAR_R, Bones.HEAD, 64f, 8f, 26f, 44f, 0.5f, 0.5f, 0f, 37, null);

        // Eyes. Lids are separate parts pivoted at their top edge so a blink is a real closing lid.
        b.part(Parts.EYE_L_WHITE, Art.EYE_WHITE, Bones.EYE_L, 0f, 0f, 38f, 22f, 0.5f, 0.5f, 0f, 40, null);
        b.part(Parts.EYE_R_WHITE, Art.EYE_WHITE, Bones.EYE_R, 0f, 0f, 38f, 22f, 0.5f, 0.5f, 0f, 40, null);
        b.part(Parts.IRIS_L, Art.IRIS, Bones.EYE_L, 0f, 0f, 17f, 17f, 0.5f, 0.5f, 0f, 41, null);
        b.part(Parts.IRIS_R, Art.IRIS, Bones.EYE_R, 0f, 0f, 17f, 17f, 0.5f, 0.5f, 0f, 41, null);
        b.part(Parts.LID_L, Art.EYELID, Bones.EYE_L, 0f, -12f, 40f, 26f, 0.5f, 0f, 0f, 42, null);
        b.part(Parts.LID_R, Art.EYELID, Bones.EYE_R, 0f, -12f, 40f, 26f, 0.5f, 0f, 0f, 42, null);

        // Brows on their own bones — raise, lower and angle independently per side.
        b.part(Parts.BROW_L, Art.BROW_L, Bones.BROW_L, 0f, 0f, 40f, 14f, 0.5f, 0.5f, 0f, 43, null);
        b.part(Parts.BROW_R, Art.BROW_R, Bones.BROW_R, 0f, 0f, 40f, 14f, 0.5f, 0.5f, 0f, 43, null);

        b.part(Parts.NOSE, Art.NOSE, Bones.HEAD, 0f, 28f, 22f, 30f, 0.5f, 0.4f, 0f, 45, null);

        // Mouth swap group — the viseme set. Exactly the shapes the lip-sync contract promises.
        mouth(b, Parts.MOUTH_CLOSED, Art.MOUTH_CLOSED, 46);
        mouth(b, Parts.MOUTH_NEUTRAL_OPEN, Art.MOUTH_NEUTRAL_OPEN, 46);
        mouth(b, Parts.MOUTH_A, Art.MOUTH_A, 46);
        mouth(b, Parts.MOUTH_E, Art.MOUTH_E, 46);
        mouth(b, Parts.MOUTH_O, Art.MOUTH_O, 46);
        mouth(b, Parts.MOUTH_U, Art.MOUTH_U, 46);
        mouth(b, Parts.MOUTH_MBP, Art.MOUTH_MBP, 46);
        mouth(b, Parts.MOUTH_FV, Art.MOUTH_FV, 46);
        mouth(b, Parts.MOUTH_SMILE, Art.MOUTH_SMILE, 46);
        mouth(b, Parts.MOUTH_FROWN, Art.MOUTH_FROWN, 46);

        // Sunglasses are deliberately translucent so the blink underneath still reads on a phone.
        b.part(Parts.SUNGLASSES, Art.SUNGLASSES, Bones.HEAD, 0f, 0f, 146f, 52f, 0.5f, 0.5f, 0f, 50, null);
        b.part(Parts.EARRING, Art.EARRING, Bones.HEAD, -66f, 26f, 14f, 22f, 0.5f, 0.2f, 0f, 51, null);

        return b.build();
    }

    private static void mouth(Rig.Builder b, String part, String art, int z) {
        // All mouth shapes share one slot on the jaw bone; alpha decides which one is showing.
        b.part(part, art, Bones.JAW, 0f, 16f, 60f, 44f, 0.5f, 0.25f, 0f, z, Groups.MOUTH);
    }

    /** Bone names. Referenced by the binder and by any externally authored rig spec. */
    public static final class Bones {
        public static final String ROOT = "root";
        public static final String HIPS = "hips";
        public static final String SPINE = "spine";
        public static final String CHEST = "chest";
        public static final String NECK = "neck";
        public static final String HEAD = "head";
        public static final String JAW = "jaw";
        public static final String BROW_L = "brow_l";
        public static final String BROW_R = "brow_r";
        public static final String EYE_L = "eye_l";
        public static final String EYE_R = "eye_r";
        public static final String HOOD = "hood";
        public static final String NECKLACE = "necklace";
        public static final String SHOULDER_L = "shoulder_l";
        public static final String ARM_L = "arm_l";
        public static final String FOREARM_L = "forearm_l";
        public static final String HAND_L = "hand_l";
        public static final String SHOULDER_R = "shoulder_r";
        public static final String ARM_R = "arm_r";
        public static final String FOREARM_R = "forearm_r";
        public static final String HAND_R = "hand_r";
        public static final String THIGH_L = "thigh_l";
        public static final String SHIN_L = "shin_l";
        public static final String FOOT_L = "foot_l";
        public static final String THIGH_R = "thigh_r";
        public static final String SHIN_R = "shin_r";
        public static final String FOOT_R = "foot_r";

        private Bones() {}
    }

    /** Layer names. */
    public static final class Parts {
        public static final String AURA = "aura";
        public static final String HOOD_DOWN = "hood_down";
        public static final String THIGH_L = "thigh_l";
        public static final String THIGH_R = "thigh_r";
        public static final String SHIN_L = "shin_l";
        public static final String SHIN_R = "shin_r";
        public static final String SHOE_L = "shoe_l";
        public static final String SHOE_R = "shoe_r";
        public static final String ARM_L_UPPER = "arm_l_upper";
        public static final String ARM_L_FORE = "arm_l_fore";
        public static final String HAND_L = "hand_l";
        public static final String TORSO = "torso";
        public static final String HOODIE_POCKET = "hoodie_pocket";
        public static final String NECK = "neck";
        public static final String NECK_TATTOO = "neck_tattoo";
        public static final String CHEST_TATTOO = "chest_tattoo";
        public static final String NECKLACE = "necklace";
        public static final String ARM_R_UPPER = "arm_r_upper";
        public static final String ARM_R_FORE = "arm_r_fore";
        public static final String HAND_R = "hand_r";
        public static final String HEAD_BASE = "head_base";
        public static final String HEAD_TATTOO = "head_tattoo";
        public static final String EAR_L = "ear_l";
        public static final String EAR_R = "ear_r";
        public static final String EYE_L_WHITE = "eye_l_white";
        public static final String EYE_R_WHITE = "eye_r_white";
        public static final String IRIS_L = "iris_l";
        public static final String IRIS_R = "iris_r";
        public static final String LID_L = "lid_l";
        public static final String LID_R = "lid_r";
        public static final String BROW_L = "brow_l";
        public static final String BROW_R = "brow_r";
        public static final String NOSE = "nose";
        public static final String MOUTH_CLOSED = "mouth_closed";
        public static final String MOUTH_NEUTRAL_OPEN = "mouth_neutral_open";
        public static final String MOUTH_A = "mouth_a";
        public static final String MOUTH_E = "mouth_e";
        public static final String MOUTH_O = "mouth_o";
        public static final String MOUTH_U = "mouth_u";
        public static final String MOUTH_MBP = "mouth_mbp";
        public static final String MOUTH_FV = "mouth_fv";
        public static final String MOUTH_SMILE = "mouth_smile";
        public static final String MOUTH_FROWN = "mouth_frown";
        public static final String SUNGLASSES = "sunglasses";
        public static final String EARRING = "earring";

        private Parts() {}
    }

    /** Swap group names. */
    public static final class Groups {
        public static final String MOUTH = "mouth";

        private Groups() {}
    }

    /** Logical art keys. The render layer maps these to bitmaps. */
    public static final class Art {
        public static final String AURA = "aura";
        public static final String HOOD_DOWN = "hood_down";
        public static final String PANT_LEG = "pant_leg";
        public static final String PANT_SHIN = "pant_shin";
        public static final String SNEAKER_L = "sneaker_l";
        public static final String SNEAKER_R = "sneaker_r";
        public static final String SLEEVE_UPPER = "sleeve_upper";
        public static final String FOREARM_TATTOO_L = "forearm_tattoo_l";
        public static final String FOREARM_TATTOO_R = "forearm_tattoo_r";
        public static final String HAND_L = "hand_l";
        public static final String HAND_R = "hand_r";
        public static final String HOODIE_BODY = "hoodie_body";
        public static final String HOODIE_POCKET = "hoodie_pocket";
        public static final String NECK_SKIN = "neck_skin";
        public static final String NECK_TATTOO = "neck_tattoo";
        public static final String CHEST_TATTOO = "chest_tattoo";
        public static final String NECKLACE = "necklace";
        public static final String HEAD_BALD = "head_bald";
        public static final String HEAD_TATTOO_WRAP = "head_tattoo_wrap";
        public static final String EAR_L = "ear_l";
        public static final String EAR_R = "ear_r";
        public static final String EYE_WHITE = "eye_white";
        public static final String IRIS = "iris";
        public static final String EYELID = "eyelid";
        public static final String BROW_L = "brow_l";
        public static final String BROW_R = "brow_r";
        public static final String NOSE = "nose";
        public static final String MOUTH_CLOSED = "mouth_closed";
        public static final String MOUTH_NEUTRAL_OPEN = "mouth_neutral_open";
        public static final String MOUTH_A = "mouth_a";
        public static final String MOUTH_E = "mouth_e";
        public static final String MOUTH_O = "mouth_o";
        public static final String MOUTH_U = "mouth_u";
        public static final String MOUTH_MBP = "mouth_mbp";
        public static final String MOUTH_FV = "mouth_fv";
        public static final String MOUTH_SMILE = "mouth_smile";
        public static final String MOUTH_FROWN = "mouth_frown";
        public static final String SUNGLASSES = "sunglasses";
        public static final String EARRING = "earring";

        private Art() {}
    }
}
