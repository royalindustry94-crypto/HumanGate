package ai.leon.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.LeonRigBinder;
import ai.leon.companion.anim.PostureBehaviour;
import ai.leon.companion.render.LeonMeshRig;
import ai.leon.companion.render.LeonMeshRig.Layer;
import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

import org.junit.Test;

public class LeonMeshRigTest {
    /** Both production layers on one shared rig, exactly as LeonPuppetRenderer builds them. */
    static final class Leon {
        final Rig rig = LeonRig.build();
        final LeonMeshRig body = LeonMeshRig.createForTest(
                rig, LeonMeshRig.PRODUCTION_COLS, LeonMeshRig.PRODUCTION_ROWS, Layer.BODY);
        final LeonMeshRig arms = LeonMeshRig.createForTest(
                rig, LeonMeshRig.PRODUCTION_COLS, LeonMeshRig.PRODUCTION_ROWS, Layer.ARMS);

        void update() {
            body.updateFromSolvedRig();
            arms.updateFromSolvedRig();
        }

        float worst(LeonTextureMask mask, float yLo, float yHi) {
            return Math.max(mask.worstOpaqueEdgeRatio(body, yLo, yHi),
                    mask.worstOpaqueEdgeRatio(arms, yLo, yHi));
        }
    }

    private static Leon posed(String... channelValues) {
        Leon leon = new Leon();
        LeonPose pose = new LeonPose();
        for (int i = 0; i < channelValues.length; i += 2) {
            pose.set(LeonChannel.valueOf(channelValues[i]), Float.parseFloat(channelValues[i + 1]));
        }
        new LeonRigBinder(leon.rig).apply(pose);
        leon.update();
        return leon;
    }

    @Test
    public void everyVertexWeightSumsToOneInBothLayers() {
        Leon leon = new Leon();
        for (LeonMeshRig mesh : new LeonMeshRig[]{leon.body, leon.arms}) {
            for (LeonMeshRig.VertexWeights weights : mesh.weights()) {
                assertEquals(1f, weights.totalWeight(), 0.001f);
                assertTrue(weights.allFinite());
            }
        }
    }

    @Test
    public void restPoseVerticesAreCanonical() {
        Leon leon = new Leon();
        for (LeonMeshRig mesh : new LeonMeshRig[]{leon.body, leon.arms}) {
            assertArrayEquals(mesh.canonicalVertices(), mesh.deformIdentityForTest(), 0.0001f);
        }
    }

    @Test
    public void fullMeshHasExpectedVertexCount() {
        Leon leon = new Leon();
        assertEquals((LeonMeshRig.PRODUCTION_COLS + 1) * (LeonMeshRig.PRODUCTION_ROWS + 1) * 2,
                leon.body.canonicalVertices().length);
    }

    @Test
    public void theTwoLayersTileThePhotoWithoutGaps() throws Exception {
        // Every visible pixel must be drawn by at least one layer, and both layers may draw the
        // same pixel only in the thin overlap band at the sleeve cut.
        LeonTextureMask mask = LeonTextureMask.load();
        for (float y = 0f; y < 768f; y += 0.5f) {
            for (float x = 0f; x < 384f; x += 0.5f) {
                if (!mask.opaqueAtDesign(x, y)) continue;
                boolean body = mask.layerDraws(Layer.BODY, x, y);
                boolean arms = mask.layerDraws(Layer.ARMS, x, y);
                assertTrue("visible pixel (" + x + ", " + y + ") is in no layer", body || arms);
                if (body && arms) {
                    assertTrue("layers overlap outside the cut band at y=" + y,
                            y >= LeonMeshRig.ARM_LAYER_TOP_Y - LeonMeshRig.ARM_LAYER_OVERLAP - 1f && y < LeonMeshRig.ARM_LAYER_TOP_Y);
                }
            }
        }
    }

    @Test
    public void everyVisiblePixelBelowTheHandsIsBody() throws Exception {
        // The calves reach +-95, wider than the last body-edge row (+-90); a boundary-line split
        // put the outer calf in the arm layer, where it rode along with the hand as a sliver.
        LeonTextureMask mask = LeonTextureMask.load();
        int legPixels = 0;
        for (float y = 456f; y < 768f; y += 0.5f) {
            for (float x = 0f; x < 384f; x += 0.5f) {
                if (!mask.opaqueAtDesign(x, y)) continue;
                legPixels++;
                // Nothing just below the limit is a hand (thighs stay within +-91 there; the feet
                // further down are wider), so the limit never clips a fingertip...
                if (y < 560f) {
                    assertTrue("a hand pixel lies below the arm layer at (" + (x - 192f) + ", " + y
                            + ")", Math.abs(x - 192f) < 95f);
                }
                // ...and every visible pixel here moves with the legs alone.
                assertFalse("leg pixel (" + (x - 192f) + ", " + y + ") is in the arm layer",
                        mask.layerDraws(Layer.ARMS, x, y));
                assertTrue(mask.layerDraws(Layer.BODY, x, y));
            }
        }
        assertTrue(legPixels > 10000);
    }

    @Test
    public void everyThumbAndFingerTexelMovesWithItsHand() throws Exception {
        // The stray dot: faint thumb fringe (alpha ~23) 1 design unit from the hip was left in the
        // body layer by the old boundary-line split and stayed behind when the hand moved. With
        // the connectivity split, the body layer holds nothing outside the body between the cut
        // and the hand bottom, down to the faintest texel.
        LeonTextureMask mask = LeonTextureMask.load();
        for (float y = LeonMeshRig.ARM_LAYER_TOP_Y; y < 452f; y += 0.25f) {
            float edge = LeonMeshRig.bodyEdgeHalfWidth(y);
            for (float side = edge + 4f; side < 140f; side += 0.25f) {
                for (float sign : new float[]{-1f, 1f}) {
                    float x = 192f + sign * side;
                    int t = mask.texelAt(x, y);
                    if (t < 0) continue;
                    assertFalse("hand texel at (" + (sign * side) + ", " + y + ") is in the body layer",
                            mask.anyAlpha(t) && mask.layerDraws(Layer.BODY, x, y));
                }
            }
        }
    }

    @Test
    public void theLayersNeverSeparateAtTheSleeveCut() {
        // Above the shared-skin line both layers must place every vertex identically, whatever the
        // pose, or the sleeve would open a crack where the arm layer begins.
        String[][] poses = {
                {"ARM_R_SWING", "0.95", "ARM_L_SWING", "-0.95"},
                {"ELBOW_R", "0.95", "HAND_R_RAISE", "0.95", "SHOULDER_R", "1"},
                {"WEIGHT_SHIFT", "1", "TORSO_TWIST", "1", "TORSO_LEAN_X", "-1"}};
        for (String[] pose : poses) {
            Leon leon = posed(pose);
            float[] canon = leon.body.canonicalVertices();
            float[] body = leon.body.deformedVertices();
            float[] arms = leon.arms.deformedVertices();
            for (int i = 0; i < canon.length / 2; i++) {
                if (canon[i * 2 + 1] > LeonMeshRig.ARM_LAYER_TOP_Y + 1f) continue;
                assertEquals(String.join(" ", pose), body[i * 2], arms[i * 2], 0.001f);
                assertEquals(String.join(" ", pose), body[i * 2 + 1], arms[i * 2 + 1], 0.001f);
            }
        }
    }

    @Test
    public void bodyEdgeLiesInTheMeasuredGapBetweenBodyAndArms() throws Exception {
        // The layer split is only correct if it runs through transparent space: an edge inside the
        // hip would move hip pixels with the hand, one inside the hand would leave hand pixels
        // behind on the hip. Re-measured against the generated texture on every half row.
        LeonTextureMask mask = LeonTextureMask.load();
        int rowsChecked = 0;
        for (float y = 274f; y <= 446f; y += 0.5f) {
            float edge = LeonMeshRig.bodyEdgeHalfWidth(y);
            for (float sign : new float[]{-1f, 1f}) {
                assertFalse("body edge at y=" + y + " (" + (sign * edge) + ") lands on a visible pixel",
                        mask.opaqueAtDesign(192f + sign * edge, y));
            }
            rowsChecked++;
        }
        assertTrue(rowsChecked > 300);
        assertTrue(mask.opaqueAtDesign(192f + 60f, 300f));
        assertTrue(mask.opaqueAtDesign(192f - 78f, 420f));
        assertTrue(mask.opaqueAtDesign(192f + 78f, 420f));
    }

    @Test
    public void idleNeverCompressesTheHipsOrUpperThighs() throws Exception {
        // The reported defect: at idle the pelvis and upper thighs looked squeezed. Hands hang
        // 3-12 units from the hips, closer than one mesh cell, so in a single mesh the cells across
        // that gap tied hand vertices to hip vertices and every arm motion squeezed the outer hip.
        // With the body in its own layer, no hip or thigh pixel follows a hand. Replays real seeded
        // idle trajectories (IDLE's base elbow bend included, at full sway amplitude).
        LeonTextureMask mask = LeonTextureMask.load();
        float worst = 1f;
        for (long seed : new long[]{0L, 7L, 17L, 42L, 91L}) {
            PostureBehaviour posture = new PostureBehaviour(seed);
            Leon leon = new Leon();
            LeonRigBinder binder = new LeonRigBinder(leon.rig);
            for (int i = 0; i < 60 * 45; i++) {
                LeonPose pose = new LeonPose();
                pose.set(LeonChannel.ELBOW_L, 0.12f);
                pose.set(LeonChannel.ELBOW_R, 0.12f);
                posture.update(1f / 60f, 1f, pose);
                binder.apply(pose);
                if (i % 5 != 0) continue;
                leon.body.updateFromSolvedRig();
                worst = Math.max(worst, mask.worstOpaqueEdgeRatio(leon.body, 340f, 560f));
            }
        }
        assertTrue("idle must not squeeze the hips/upper thighs, worst edge ratio " + worst,
                worst < 1.05f);
    }

    @Test
    public void armMotionNeverMovesAnyHipOrLegPixel() throws Exception {
        // The hips must be completely indifferent to what the arms do: every body-layer edge from
        // the waist down keeps its exact length under the largest arm, elbow and hand poses.
        LeonTextureMask mask = LeonTextureMask.load();
        String[][] poses = {
                {"ARM_R_SWING", "0.95"}, {"ARM_L_SWING", "-0.95"},
                {"ELBOW_R", "0.95", "HAND_R_RAISE", "0.95"},
                {"ELBOW_L", "0.95", "HAND_L_RAISE", "0.95"}};
        for (String[] pose : poses) {
            Leon leon = posed(pose);
            float worst = mask.worstOpaqueEdgeRatio(leon.body, 300f, 740f);
            assertTrue(String.join(" ", pose) + " moved hip/leg pixels by " + worst, worst < 1.001f);
        }
    }

    @Test
    public void weightShiftMovesThePelvisWithoutSqueezingIt() throws Exception {
        LeonTextureMask mask = LeonTextureMask.load();
        for (String shift : new String[]{"1", "-1"}) {
            Leon leon = posed("WEIGHT_SHIFT", shift, "ELBOW_L", "0.12", "ELBOW_R", "0.12");
            float worst = mask.worstOpaqueEdgeRatio(leon.body, 340f, 560f);
            assertTrue("WEIGHT_SHIFT=" + shift + " worst hip edge ratio " + worst, worst < 1.05f);
        }
    }

    @Test
    public void handsStayRigidInEveryState() throws Exception {
        // The failure the single mesh produced when bound the other way: hands tearing into
        // slivers across the hip. Below the wrist blend every hand pixel follows the hand bone
        // alone, so its edges never change length, in any state or while walking.
        LeonTextureMask mask = LeonTextureMask.load();
        float handTop = LeonRig.WRIST_Y + 15f;
        for (LeonState state : LeonState.values()) {
            if (state == LeonState.MINIMISED) continue;
            Leon leon = new Leon();
            LeonStateController states = new LeonStateController();
            LeonAnimationController controller = new LeonAnimationController(leon.rig, states, 5L);
            if (state != LeonState.IDLE) states.request(state);
            else controller.startWalkForDebug(1f, 20f);
            float worst = 1f;
            for (int f = 0; f < 60 * 20; f++) {
                controller.update(1f / 60f);
                if (f % 10 != 0) continue;
                leon.arms.updateFromSolvedRig();
                worst = Math.max(worst, mask.worstOpaqueEdgeRatio(leon.arms, handTop, 470f));
            }
            // 0.5%: breathing scales the chest non-uniformly and the shoulders' inverse scale does
            // not cancel exactly once the chest also rotates (~0.2 units across a 90-unit hand).
            assertTrue(state + ": hand deformed by " + worst, worst < 1.005f);
        }
    }

    @Test
    public void bendingTheElbowDoesNotStretchVisibleSkinIntoASliver() throws Exception {
        LeonTextureMask mask = LeonTextureMask.load();
        float worst = posed("ELBOW_R", "0.95", "HAND_R_RAISE", "0.95").worst(mask, 150f, 740f);
        assertTrue("a bent elbow stretched visible pixels by " + worst, worst < 2.5f);
    }

    /**
     * Known residual, not part of the hip fix: the armpit (y ~255-292), where the sleeve is still
     * one piece of cloth with the torso and the gap is only 1-4 units wide once it opens. Swinging
     * the arm stretches or bunches the cloth there (worst measured: 3.40x, HAPPY). Above y 292 the
     * body and arm layers run the unchanged single-mesh skinning, so this is not introduced by the
     * layer split; it was hidden by an old exemption in the metric. Bounded here so it
     * cannot silently get worse; everything below the armpit has the strict bounds.
     */
    private static final float ARMPIT_BOTTOM_Y = 292f;
    private static final float ARMPIT_KNOWN_RESIDUAL = 3.5f;

    @Test
    public void armSwingAloneDoesNotStretchTheShoulderSeam() throws Exception {
        LeonTextureMask mask = LeonTextureMask.load();
        Leon leon = posed("ARM_R_SWING", "0.95");
        float below = leon.worst(mask, ARMPIT_BOTTOM_Y, 740f);
        float armpit = leon.worst(mask, 150f, ARMPIT_BOTTOM_Y);
        assertTrue("an arm swing stretched visible pixels below the armpit by " + below, below < 1.1f);
        assertTrue("an arm swing stretched the armpit by " + armpit, armpit < ARMPIT_KNOWN_RESIDUAL);
    }

    @Test
    public void armJointPivotsSitOnThePhotographedArm() throws Exception {
        LeonTextureMask mask = LeonTextureMask.load();
        Leon leon = new Leon();
        String[] joints = {LeonRig.Bones.ARM_L, LeonRig.Bones.ARM_R, LeonRig.Bones.FOREARM_L,
                LeonRig.Bones.FOREARM_R, LeonRig.Bones.HAND_L, LeonRig.Bones.HAND_R};
        for (String name : joints) {
            Bone bone = leon.rig.findBone(name);
            float x = bone.world().mapX(0f, 0f);
            float y = bone.world().mapY(0f, 0f);
            assertTrue(name + " pivot is not on a visible pixel", mask.opaqueAtDesign(x, y));
            if (y > 268f) {
                assertTrue(name + " pivot is inside the torso",
                        Math.abs(x - 192f) > LeonMeshRig.bodyEdgeHalfWidth(y));
            }
        }
        Bone elbow = leon.rig.findBone(LeonRig.Bones.FOREARM_R);
        Bone wrist = leon.rig.findBone(LeonRig.Bones.HAND_R);
        assertEquals(LeonRig.ELBOW_X, elbow.world().mapX(0f, 0f) - 192f, 0.01f);
        assertEquals(LeonRig.ELBOW_Y, elbow.world().mapY(0f, 0f), 0.01f);
        assertEquals(LeonRig.WRIST_X, wrist.world().mapX(0f, 0f) - 192f, 0.01f);
        assertEquals(LeonRig.WRIST_Y, wrist.world().mapY(0f, 0f), 0.01f);
    }

    @Test
    public void idleElbowBendOnlyCompressesTheInnerElbow() throws Exception {
        // A 12.6 degree bend across the 30-unit blend band, 25 units from the pivot, predicts
        // ~1.37x at the inner crook of the elbow; with the pivot on the cuff it was 1.55x.
        LeonTextureMask mask = LeonTextureMask.load();
        Leon leon = posed("ELBOW_L", "0.12", "ELBOW_R", "0.12");
        float upperArm = leon.worst(mask, 205f, 268f);
        float elbow = leon.worst(mask, 268f, 340f);
        assertTrue("idle elbow bend deformed the upper arm by " + upperArm, upperArm < 1.06f);
        assertTrue("idle elbow bend deformed the elbow by " + elbow, elbow < 1.4f);
    }

    @Test
    public void raisingTheHandRotatesItAboutTheWrist() throws Exception {
        LeonTextureMask mask = LeonTextureMask.load();
        float wrist = posed("HAND_R_RAISE", "0.3").worst(mask, 340f, 452f);
        assertTrue("a hand raise deformed the wrist by " + wrist, wrist < 1.15f);
    }

    @Test
    public void everyStateKeepsVisibleDistortionBounded() throws Exception {
        // Real controller trajectories, both layers, whole body. Below the armpit what remains
        // (~2.1x) is the inner elbow at full flexion.
        LeonTextureMask mask = LeonTextureMask.load();
        for (LeonState state : new LeonState[]{LeonState.IDLE, LeonState.HAPPY, LeonState.THINKING,
                LeonState.SPEAKING}) {
            Leon leon = new Leon();
            LeonStateController states = new LeonStateController();
            LeonAnimationController controller = new LeonAnimationController(leon.rig, states, 11L);
            if (state != LeonState.IDLE) states.request(state);
            float below = 1f;
            float armpit = 1f;
            for (int f = 0; f < 60 * 40; f++) {
                controller.update(1f / 60f);
                if (f % 6 != 0) continue;
                leon.update();
                below = Math.max(below, leon.worst(mask, ARMPIT_BOTTOM_Y, 768f));
                armpit = Math.max(armpit, leon.worst(mask, 0f, ARMPIT_BOTTOM_Y));
            }
            assertTrue(state + " deformed visible pixels below the armpit by " + below, below < 2.2f);
            assertTrue(state + " deformed the armpit by " + armpit, armpit < ARMPIT_KNOWN_RESIDUAL);
        }
    }
}
