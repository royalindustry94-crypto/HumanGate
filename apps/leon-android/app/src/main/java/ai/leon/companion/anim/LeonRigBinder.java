package ai.leon.companion.anim;

import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;
import ai.leon.companion.util.Mathx;

import java.util.ArrayList;
import java.util.List;

/**
 * The only class that knows how a {@link LeonChannel} reaches a bone or a layer. Everything above it
 * works in channel space, which is what keeps the state machine independent of the artwork.
 *
 * <p>All references are resolved once in the constructor, so {@link #apply(LeonPose)} does no
 * lookups and no allocation and is safe to call every frame.
 *
 * <p>Head yaw and pitch are deliberately implemented as a 2.5D solve — the head bone shifts and
 * squashes while the face layers counter-shift inside it — rather than as a rotation of the whole
 * head bitmap, which is what makes a turn read as a turn instead of a tilt.
 */
public final class LeonRigBinder {
    // Motion limits in design-space units / degrees. Tuned to stay inside the rig's silhouette.
    private static final float HEAD_YAW_SHIFT = 11f;
    private static final float HEAD_YAW_SQUASH = 0.07f;
    private static final float HEAD_PITCH_SHIFT = 9f;
    private static final float HEAD_ROLL_DEG = 11f;
    private static final float FACE_PARALLAX_X = 7f;
    private static final float FACE_PARALLAX_Y = 5f;
    private static final float NECK_YAW_DEG = 4f;
    private static final float GAZE_X = 5f;
    private static final float GAZE_Y = 3f;
    private static final float LID_OPEN_SCALE = 0.05f;
    private static final float BROW_RAISE_Y = 7f;
    private static final float BROW_LOWER_Y = 4f;
    private static final float BROW_ANGLE_DEG = 9f;
    private static final float JAW_DEG = 13f;
    private static final float BREATH_CHEST_Y = 0.038f;
    private static final float BREATH_CHEST_X = 0.022f;
    private static final float BREATH_LIFT = 3.5f;
    private static final float SHOULDER_DEG = 8f;
    private static final float SHOULDER_LIFT = 5f;
    private static final float SPINE_LEAN_DEG = 4.5f;
    private static final float TORSO_TWIST_SQUASH = 0.05f;
    private static final float WEIGHT_SHIFT_X = 8f;
    private static final float WEIGHT_SHIFT_DEG = 2.2f;
    private static final float ARM_SWING_DEG = 26f;
    /** A real elbow flexes past 140 degrees; 46 was far too little for a hand-to-chin pose. */
    private static final float ELBOW_DEG = 105f;
    private static final float HAND_RAISE_DEG = 30f;
    private static final float HOOD_SWAY_DEG = 6f;
    private static final float NECKLACE_SWAY_DEG = 9f;

    private final Rig rig;

    private final Bone root;
    private final Bone hips;
    private final Bone spine;
    private final Bone chest;
    private final Bone neck;
    private final Bone head;
    private final Bone jaw;
    private final Bone browL;
    private final Bone browR;
    private final Bone hood;
    private final Bone necklaceBone;
    private final Bone shoulderL;
    private final Bone shoulderR;
    private final Bone armL;
    private final Bone armR;
    private final Bone forearmL;
    private final Bone forearmR;
    private final Bone handL;
    private final Bone handR;
    private final Bone thighL;
    private final Bone thighR;

    private final RigPart torso;
    private final RigPart lidL;
    private final RigPart lidR;
    private final RigPart irisL;
    private final RigPart irisR;
    private final RigPart browPartL;
    private final RigPart browPartR;
    private final RigPart aura;

    /** Face layers that counter-shift inside the head to sell a yaw/pitch turn. */
    private final RigPart[] faceLayers;
    /** Mouth swap group in {@link MouthShapeResolver#SHAPE_PARTS} order. */
    private final RigPart[] mouthShapes;

    private final MouthShapeResolver mouthResolver = new MouthShapeResolver();

    public LeonRigBinder(Rig rig) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        this.rig = rig;

        root = requireBone(LeonRig.Bones.ROOT);
        hips = requireBone(LeonRig.Bones.HIPS);
        spine = requireBone(LeonRig.Bones.SPINE);
        chest = requireBone(LeonRig.Bones.CHEST);
        neck = requireBone(LeonRig.Bones.NECK);
        head = requireBone(LeonRig.Bones.HEAD);
        jaw = requireBone(LeonRig.Bones.JAW);
        browL = requireBone(LeonRig.Bones.BROW_L);
        browR = requireBone(LeonRig.Bones.BROW_R);
        hood = requireBone(LeonRig.Bones.HOOD);
        necklaceBone = requireBone(LeonRig.Bones.NECKLACE);
        shoulderL = requireBone(LeonRig.Bones.SHOULDER_L);
        shoulderR = requireBone(LeonRig.Bones.SHOULDER_R);
        armL = requireBone(LeonRig.Bones.ARM_L);
        armR = requireBone(LeonRig.Bones.ARM_R);
        forearmL = requireBone(LeonRig.Bones.FOREARM_L);
        forearmR = requireBone(LeonRig.Bones.FOREARM_R);
        handL = requireBone(LeonRig.Bones.HAND_L);
        handR = requireBone(LeonRig.Bones.HAND_R);
        thighL = requireBone(LeonRig.Bones.THIGH_L);
        thighR = requireBone(LeonRig.Bones.THIGH_R);

        torso = requirePart(LeonRig.Parts.TORSO);
        lidL = requirePart(LeonRig.Parts.LID_L);
        lidR = requirePart(LeonRig.Parts.LID_R);
        irisL = requirePart(LeonRig.Parts.IRIS_L);
        irisR = requirePart(LeonRig.Parts.IRIS_R);
        browPartL = requirePart(LeonRig.Parts.BROW_L);
        browPartR = requirePart(LeonRig.Parts.BROW_R);
        aura = requirePart(LeonRig.Parts.AURA);

        List<RigPart> face = new ArrayList<>();
        addIfPresent(face, LeonRig.Parts.SUNGLASSES);
        addIfPresent(face, LeonRig.Parts.NOSE);
        addIfPresent(face, LeonRig.Parts.HEAD_TATTOO);
        addIfPresent(face, LeonRig.Parts.EYE_L_WHITE);
        addIfPresent(face, LeonRig.Parts.EYE_R_WHITE);
        faceLayers = face.toArray(new RigPart[0]);

        mouthShapes = new RigPart[MouthShapeResolver.SHAPE_PARTS.length];
        for (int i = 0; i < mouthShapes.length; i++) {
            mouthShapes[i] = requirePart(MouthShapeResolver.SHAPE_PARTS[i]);
        }
    }

    public Rig rig() {
        return rig;
    }

    /** Shape weights from the most recent {@link #apply(LeonPose)}. Useful for tests and debug UI. */
    public float[] lastMouthWeights() {
        return mouthResolver.weights();
    }

    /**
     * Resets the rig, writes every channel of {@code pose} onto it, then solves the hierarchy.
     * Call once per frame.
     */
    public void apply(LeonPose pose) {
        rig.resetAnimation();

        float yaw = clampSigned(pose.get(LeonChannel.HEAD_YAW));
        float pitch = clampSigned(pose.get(LeonChannel.HEAD_PITCH));
        float roll = clampSigned(pose.get(LeonChannel.HEAD_ROLL));

        // ---- whole-rig framing (minimised form factor only) ----
        float scale = Mathx.clamp(pose.get(LeonChannel.RIG_SCALE), 0.2f, 2f);
        root.scaleX = scale;
        root.scaleY = scale;

        // ---- torso ----
        float breath = Mathx.clamp01(pose.get(LeonChannel.CHEST_BREATH));
        chest.scaleY = 1f + BREATH_CHEST_Y * breath;
        chest.scaleX = 1f + BREATH_CHEST_X * breath;
        chest.offsetY = -BREATH_LIFT * breath;
        torso.scaleY = 1f + BREATH_CHEST_Y * 0.6f * breath;

        float twist = clampSigned(pose.get(LeonChannel.TORSO_TWIST));
        chest.scaleX *= 1f - TORSO_TWIST_SQUASH * Math.abs(twist);
        chest.offsetX = twist * 4f;

        float leanX = clampSigned(pose.get(LeonChannel.TORSO_LEAN_X));
        float leanY = clampSigned(pose.get(LeonChannel.TORSO_LEAN_Y));
        spine.rotationDeg = leanX * SPINE_LEAN_DEG;
        spine.offsetY = leanY * 4f;

        float weight = clampSigned(pose.get(LeonChannel.WEIGHT_SHIFT));
        hips.offsetX = weight * WEIGHT_SHIFT_X;
        hips.rotationDeg = weight * WEIGHT_SHIFT_DEG;
        // Legs counter the hip swing so the feet stay planted.
        thighL.rotationDeg = -weight * WEIGHT_SHIFT_DEG * 1.4f;
        thighR.rotationDeg = -weight * WEIGHT_SHIFT_DEG * 1.4f;

        // ---- shoulders (independent, plus a breath-driven lift) ----
        float shL = clampSigned(pose.get(LeonChannel.SHOULDER_L));
        float shR = clampSigned(pose.get(LeonChannel.SHOULDER_R));
        shoulderL.rotationDeg = shL * SHOULDER_DEG;
        shoulderL.offsetY = -shL * SHOULDER_LIFT - breath * 3f;
        shoulderR.rotationDeg = -shR * SHOULDER_DEG;
        shoulderR.offsetY = -shR * SHOULDER_LIFT - breath * 3f;

        // ---- arms ----
        armL.rotationDeg = clampSigned(pose.get(LeonChannel.ARM_L_SWING)) * ARM_SWING_DEG;
        armR.rotationDeg = -clampSigned(pose.get(LeonChannel.ARM_R_SWING)) * ARM_SWING_DEG;
        float handLRaise = Mathx.clamp01(pose.get(LeonChannel.HAND_L_RAISE));
        float handRRaise = Mathx.clamp01(pose.get(LeonChannel.HAND_R_RAISE));
        armL.rotationDeg -= handLRaise * ARM_SWING_DEG * 1.1f;
        armR.rotationDeg += handRRaise * ARM_SWING_DEG * 1.1f;
        forearmL.rotationDeg = -Mathx.clamp01(pose.get(LeonChannel.ELBOW_L)) * ELBOW_DEG - handLRaise * 30f;
        forearmR.rotationDeg = Mathx.clamp01(pose.get(LeonChannel.ELBOW_R)) * ELBOW_DEG + handRRaise * 30f;
        handL.rotationDeg = -handLRaise * HAND_RAISE_DEG;
        handR.rotationDeg = handRRaise * HAND_RAISE_DEG;

        // ---- secondary motion ----
        hood.rotationDeg = clampSigned(pose.get(LeonChannel.HOOD_SWAY)) * HOOD_SWAY_DEG;
        necklaceBone.rotationDeg = clampSigned(pose.get(LeonChannel.NECKLACE_SWAY)) * NECKLACE_SWAY_DEG;

        // ---- head: 2.5D yaw/pitch plus a real roll ----
        head.offsetX = yaw * HEAD_YAW_SHIFT;
        head.offsetY = pitch * HEAD_PITCH_SHIFT - breath * 1.5f;
        head.rotationDeg = roll * HEAD_ROLL_DEG;
        head.scaleX = 1f - HEAD_YAW_SQUASH * Math.abs(yaw);
        head.scaleY = 1f - 0.03f * Math.abs(pitch);
        neck.rotationDeg = -yaw * NECK_YAW_DEG;
        neck.offsetY = -breath * 1.5f;

        for (RigPart layer : faceLayers) {
            layer.animOffsetX = yaw * FACE_PARALLAX_X;
            layer.animOffsetY = pitch * FACE_PARALLAX_Y;
        }
        // Eye bones live on the head, so only the extra parallax is applied to their contents.
        float eyeShiftX = yaw * FACE_PARALLAX_X;
        float eyeShiftY = pitch * FACE_PARALLAX_Y;

        // ---- eyes ----
        float gazeX = clampSigned(pose.get(LeonChannel.EYE_LOOK_X));
        float gazeY = clampSigned(pose.get(LeonChannel.EYE_LOOK_Y));
        irisL.animOffsetX = eyeShiftX + gazeX * GAZE_X;
        irisL.animOffsetY = eyeShiftY + gazeY * GAZE_Y;
        irisR.animOffsetX = eyeShiftX + gazeX * GAZE_X;
        irisR.animOffsetY = eyeShiftY + gazeY * GAZE_Y;

        // A blink is a lid that scales down from its top edge until it covers the eye.
        float blinkL = Mathx.clamp01(pose.get(LeonChannel.BLINK_L));
        float blinkR = Mathx.clamp01(pose.get(LeonChannel.BLINK_R));
        lidL.scaleY = Mathx.lerp(LID_OPEN_SCALE, 1f, blinkL);
        lidR.scaleY = Mathx.lerp(LID_OPEN_SCALE, 1f, blinkR);
        lidL.animOffsetX = eyeShiftX;
        lidR.animOffsetX = eyeShiftX;
        lidL.animOffsetY = eyeShiftY;
        lidR.animOffsetY = eyeShiftY;

        // ---- brows ----
        float raiseL = Mathx.clamp01(pose.get(LeonChannel.BROW_L_RAISE));
        float raiseR = Mathx.clamp01(pose.get(LeonChannel.BROW_R_RAISE));
        float lowerL = Mathx.clamp01(pose.get(LeonChannel.BROW_L_LOWER));
        float lowerR = Mathx.clamp01(pose.get(LeonChannel.BROW_R_LOWER));
        browL.offsetY = -raiseL * BROW_RAISE_Y + lowerL * BROW_LOWER_Y;
        browR.offsetY = -raiseR * BROW_RAISE_Y + lowerR * BROW_LOWER_Y;
        browL.rotationDeg = raiseL * BROW_ANGLE_DEG * 0.4f - lowerL * BROW_ANGLE_DEG;
        browR.rotationDeg = -raiseR * BROW_ANGLE_DEG * 0.4f + lowerR * BROW_ANGLE_DEG;
        browPartL.animOffsetX = eyeShiftX;
        browPartR.animOffsetX = eyeShiftX;
        browPartL.animOffsetY = eyeShiftY;
        browPartR.animOffsetY = eyeShiftY;

        // ---- jaw and mouth ----
        float jawDrop = Mathx.clamp01(pose.get(LeonChannel.JAW_DROP));
        jaw.rotationDeg = jawDrop * JAW_DEG;
        jaw.offsetY = jawDrop * 4f;
        jaw.offsetX = eyeShiftX * 0.7f;

        float[] weights = mouthResolver.resolve(pose);
        for (int i = 0; i < mouthShapes.length; i++) {
            RigPart shape = mouthShapes[i];
            shape.alpha = weights[i];
            shape.animOffsetX = eyeShiftX * 0.3f;
            // A wider mouth stretches the layer horizontally rather than swapping in a new bitmap.
            shape.scaleX = 1f + 0.12f * Mathx.clamp01(pose.get(LeonChannel.MOUTH_WIDE))
                    - 0.1f * Mathx.clamp01(pose.get(LeonChannel.MOUTH_ROUND));
            shape.scaleY = 1f + 0.18f * jawDrop;
        }

        // ---- sleepy expression droops the lids beyond whatever the blink is doing ----
        float sleepy = Mathx.clamp01(pose.get(LeonChannel.EXPR_SLEEPY));
        if (sleepy > 0f) {
            lidL.scaleY = Math.max(lidL.scaleY, Mathx.lerp(LID_OPEN_SCALE, 0.62f, sleepy));
            lidR.scaleY = Math.max(lidR.scaleY, Mathx.lerp(LID_OPEN_SCALE, 0.62f, sleepy));
        }

        aura.alpha = Mathx.clamp01(pose.get(LeonChannel.AURA));

        rig.solve();
    }

    private static float clampSigned(float v) {
        return Mathx.clamp(v, -1f, 1f);
    }

    private Bone requireBone(String name) {
        Bone b = rig.findBone(name);
        if (b == null) throw new IllegalArgumentException("rig is missing required bone '" + name + "'");
        return b;
    }

    private RigPart requirePart(String name) {
        RigPart p = rig.findPart(name);
        if (p == null) throw new IllegalArgumentException("rig is missing required part '" + name + "'");
        return p;
    }

    private void addIfPresent(List<RigPart> out, String name) {
        RigPart p = rig.findPart(name);
        if (p != null) out.add(p);
    }
}
