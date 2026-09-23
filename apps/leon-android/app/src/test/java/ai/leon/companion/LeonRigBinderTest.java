package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.LeonRigBinder;
import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;

import org.junit.Before;
import org.junit.Test;

/**
 * Proves each control channel reaches its own part of the rig. This is the test that distinguishes a
 * real character rig from a bitmap being transformed as a whole: a blink must move only the lids, a
 * breath must move only the torso, and neither may displace the rig root.
 */
public class LeonRigBinderTest {
    private Rig rig;
    private LeonRigBinder binder;
    private LeonPose pose;

    @Before
    public void setUp() {
        rig = LeonRig.build();
        binder = new LeonRigBinder(rig);
        pose = new LeonPose();
    }

    @Test
    public void aRestPoseLeavesEveryBoneAtItsRestTransform() {
        binder.apply(pose);
        for (Bone bone : rig.bones()) {
            assertEquals(bone.name + " rotation", 0f, bone.rotationDeg, 0.0001f);
            assertEquals(bone.name + " scaleX", 1f, bone.scaleX, 0.0001f);
        }
    }

    @Test
    public void blinkingClosesTheLidsAndTouchesNothingElse() {
        binder.apply(pose);
        float openL = rig.part(LeonRig.Parts.LID_L).scaleY;
        float headX = rig.bone(LeonRig.Bones.HEAD).world().mapX(0f, 0f);
        float chestScale = rig.bone(LeonRig.Bones.CHEST).scaleY;

        pose.set(LeonChannel.BLINK_L, 1f).set(LeonChannel.BLINK_R, 1f);
        binder.apply(pose);

        assertTrue("a closed lid must be taller than an open one",
                rig.part(LeonRig.Parts.LID_L).scaleY > openL + 0.5f);
        assertEquals(1f, rig.part(LeonRig.Parts.LID_L).scaleY, 0.0001f);
        assertEquals("a blink must not move the head", headX,
                rig.bone(LeonRig.Bones.HEAD).world().mapX(0f, 0f), 0.0001f);
        assertEquals("a blink must not move the chest", chestScale,
                rig.bone(LeonRig.Bones.CHEST).scaleY, 0.0001f);
    }

    @Test
    public void winkingClosesOneLidOnly() {
        pose.set(LeonChannel.BLINK_L, 1f);
        binder.apply(pose);
        assertTrue(rig.part(LeonRig.Parts.LID_L).scaleY > rig.part(LeonRig.Parts.LID_R).scaleY + 0.5f);
    }

    @Test
    public void breathingScalesTheChestWithoutScalingTheWholeRig() {
        binder.apply(pose);
        float rootScale = rig.bone(LeonRig.Bones.ROOT).scaleY;

        pose.set(LeonChannel.CHEST_BREATH, 1f);
        binder.apply(pose);

        assertTrue("the chest must expand", rig.bone(LeonRig.Bones.CHEST).scaleY > 1.02f);
        assertEquals("breathing must never scale the rig root", rootScale,
                rig.bone(LeonRig.Bones.ROOT).scaleY, 0.0001f);
        assertTrue("the shoulders should rise with the breath",
                rig.bone(LeonRig.Bones.SHOULDER_L).offsetY < 0f);
    }

    @Test
    public void breathingMustNotChangeTheHeadsOrArmsSizeOnScreen() {
        // Chest is the parent of neck/head and of both shoulders. An uncorrected chest breathing
        // scale multiplies through the whole chain, so a real device shows the head and arms
        // visibly swelling and shrinking with every breath -- exactly the "unnatural, getting
        // bigger and smaller" defect a person on the phone actually saw. World-space scale
        // (not the bone's own local scale) is what the mesh renderer and a viewer's eye see.
        binder.apply(pose);
        float restHeadScale = worldScaleY(rig.bone(LeonRig.Bones.HEAD));
        float restArmScale = worldScaleY(rig.bone(LeonRig.Bones.SHOULDER_L));

        pose.set(LeonChannel.CHEST_BREATH, 1f);
        binder.apply(pose);

        assertEquals("a full breath must not change the head's rendered size", restHeadScale,
                worldScaleY(rig.bone(LeonRig.Bones.HEAD)), 0.001f);
        assertEquals("a full breath must not change the shoulder/arm rendered size", restArmScale,
                worldScaleY(rig.bone(LeonRig.Bones.SHOULDER_L)), 0.001f);
    }

    /** World-space Y scale magnitude of a solved bone, independent of its own local scale field. */
    private static float worldScaleY(ai.leon.companion.rig.Bone bone) {
        ai.leon.companion.rig.Mat2D w = bone.world();
        return (float) Math.hypot(w.c, w.d);
    }

    @Test
    public void headYawShiftsTheHeadAndCounterShiftsTheFaceLayers() {
        binder.apply(pose);
        float restHeadX = rig.bone(LeonRig.Bones.HEAD).offsetX;
        float restGlassesX = rig.part(LeonRig.Parts.SUNGLASSES).animOffsetX;

        pose.set(LeonChannel.HEAD_YAW, 1f);
        binder.apply(pose);

        assertTrue("a yaw must move the head bone",
                rig.bone(LeonRig.Bones.HEAD).offsetX > restHeadX + 5f);
        assertTrue("a yaw must slide the face layers inside the head",
                rig.part(LeonRig.Parts.SUNGLASSES).animOffsetX > restGlassesX + 3f);
        assertTrue("a yaw must foreshorten the head", rig.bone(LeonRig.Bones.HEAD).scaleX < 0.98f);
        assertTrue("the neck must counter-rotate", rig.bone(LeonRig.Bones.NECK).rotationDeg < 0f);
    }

    @Test
    public void shouldersAndArmsAreDrivenIndependentlyPerSide() {
        pose.set(LeonChannel.SHOULDER_L, 1f);
        pose.set(LeonChannel.ELBOW_R, 1f);
        binder.apply(pose);

        assertTrue(Math.abs(rig.bone(LeonRig.Bones.SHOULDER_L).rotationDeg) > 5f);
        assertEquals("the right shoulder must be untouched", 0f,
                rig.bone(LeonRig.Bones.SHOULDER_R).rotationDeg, 0.0001f);
        assertTrue(Math.abs(rig.bone(LeonRig.Bones.FOREARM_R).rotationDeg) > 20f);
        assertEquals("the left forearm must be untouched", 0f,
                rig.bone(LeonRig.Bones.FOREARM_L).rotationDeg, 0.0001f);
    }

    @Test
    public void aWeightShiftMovesTheHipsAndCountersWithTheLegs() {
        pose.set(LeonChannel.WEIGHT_SHIFT, 1f);
        binder.apply(pose);
        assertTrue(rig.bone(LeonRig.Bones.HIPS).offsetX > 4f);
        assertTrue("legs must counter-rotate so the feet stay planted",
                rig.bone(LeonRig.Bones.THIGH_L).rotationDeg < 0f);
    }

    @Test
    public void jawDropOpensTheJawAndStretchesTheMouthLayer() {
        pose.set(LeonChannel.JAW_DROP, 1f);
        binder.apply(pose);
        assertTrue(rig.bone(LeonRig.Bones.JAW).rotationDeg > 8f);
        assertTrue(rig.part(LeonRig.Parts.MOUTH_A).scaleY > 1.1f);
    }

    @Test
    public void mouthLayerAlphasAlwaysSumToOne() {
        float[][] cases = {
                {0f, 0f, 0f, 0f, 0f},
                {1f, 0f, 0f, 0f, 0f},
                {0.5f, 1f, 0f, 0f, 0f},
                {0.6f, 0f, 1f, 0f, 0f},
                {0f, 0f, 0f, 1f, 0f},
                {0.2f, 0.3f, 0f, 0.3f, 1f},
        };
        for (float[] c : cases) {
            LeonPose p = new LeonPose();
            p.set(LeonChannel.JAW_DROP, c[0]);
            p.set(LeonChannel.MOUTH_WIDE, c[1]);
            p.set(LeonChannel.MOUTH_ROUND, c[2]);
            p.set(LeonChannel.MOUTH_PRESS, c[3]);
            p.set(LeonChannel.MOUTH_TEETH, c[4]);
            binder.apply(p);
            float total = 0f;
            for (RigPart part : rig.group(LeonRig.Groups.MOUTH)) total += part.alpha;
            assertEquals("mouth alphas must sum to 1", 1f, total, 0.002f);
        }
    }

    @Test
    public void theSleepyExpressionDroopsTheLidsWithoutABlink() {
        pose.set(LeonChannel.EXPR_SLEEPY, 1f);
        binder.apply(pose);
        float droop = rig.part(LeonRig.Parts.LID_L).scaleY;
        assertTrue("sleepy lids must be part-closed", droop > 0.4f);
        assertTrue("sleepy lids must not be fully shut", droop < 0.9f);
    }

    @Test
    public void rigScaleIsTheOnlyChannelThatTouchesTheRoot() {
        pose.set(LeonChannel.RIG_SCALE, 0.5f);
        binder.apply(pose);
        assertEquals(0.5f, rig.bone(LeonRig.Bones.ROOT).scaleX, 0.0001f);
        assertEquals(0.5f, rig.bone(LeonRig.Bones.ROOT).scaleY, 0.0001f);
    }

    @Test
    public void aRigMissingARequiredBoneIsRejectedAtConstruction() {
        Rig incomplete = new Rig.Builder()
                .root("root", 0f, 0f)
                .part("blob", "blob", "root", 0f, 0f, 10f, 10f, 0)
                .build();
        try {
            new LeonRigBinder(incomplete);
            org.junit.Assert.fail("expected the binder to reject an incompatible rig");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("missing required"));
        }
    }
}
