package ai.leon.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.LeonRigBinder;
import ai.leon.companion.anim.PostureBehaviour;
import ai.leon.companion.render.LeonMeshRig;

import org.junit.Test;

public class LeonMeshRigTest {
    @Test
    public void everyVertexWeightSumsToOne() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(LeonMeshRig.PRODUCTION_COLS, LeonMeshRig.PRODUCTION_ROWS);
        for (LeonMeshRig.VertexWeights weights : mesh.weights()) {
            assertEquals(1f, weights.totalWeight(), 0.001f);
            assertTrue(weights.allFinite());
        }
    }

    @Test
    public void restPoseVerticesAreCanonical() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(LeonMeshRig.PRODUCTION_COLS, LeonMeshRig.PRODUCTION_ROWS);
        assertArrayEquals(mesh.canonicalVertices(), mesh.deformIdentityForTest(), 0.0001f);
    }

    @Test
    public void fullMeshHasExpectedVertexCount() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(LeonMeshRig.PRODUCTION_COLS, LeonMeshRig.PRODUCTION_ROWS);
        assertEquals((LeonMeshRig.PRODUCTION_COLS + 1) * (LeonMeshRig.PRODUCTION_ROWS + 1) * 2, mesh.canonicalVertices().length);
    }

    private static LeonMeshRig productionMesh() {
        return LeonMeshRig.createForTest(LeonMeshRig.PRODUCTION_COLS, LeonMeshRig.PRODUCTION_ROWS);
    }

    private static LeonMeshRig posed(String... channelValues) {
        LeonMeshRig mesh = productionMesh();
        LeonPose pose = new LeonPose();
        for (int i = 0; i < channelValues.length; i += 2) {
            pose.set(LeonChannel.valueOf(channelValues[i]), Float.parseFloat(channelValues[i + 1]));
        }
        new LeonRigBinder(mesh.rig()).apply(pose);
        mesh.updateFromSolvedRig();
        return mesh;
    }

    @Test
    public void bodyEdgeLiesInTheMeasuredGapBetweenBodyAndArms() throws Exception {
        // The skinning boundary is only correct if it runs through transparent space: a boundary
        // inside the hip binds hip pixels to the hand (the idle hip compression), and one inside the
        // hand binds hand pixels to the hip. Re-measured against the generated production texture on
        // every design row where body and arms are separate, on both sides, so replacement art that
        // moves the gap fails here instead of silently mis-binding.
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
        // ...and the torso and hip really are on the inside of it: the edge is a gap, not a guess.
        assertTrue(mask.opaqueAtDesign(192f + 60f, 300f));
        assertTrue(mask.opaqueAtDesign(192f - 78f, 420f));
        assertTrue(mask.opaqueAtDesign(192f + 78f, 420f));
    }

    @Test
    public void idleNeverCompressesTheHips() throws Exception {
        // Real-device defect: at plain IDLE the pelvis looked squeezed and the legs loosely
        // attached. Root cause: the pelvis and thighs reach +-80..93 design units from the centre,
        // but everything beyond +-72 below y=395 was skinned to hand->root, and at 16 columns a
        // single cell spanned both the outer hip and the hand. PostureBehaviour's weight shift moves
        // HIPS by up to ~7 units while the outer hip strip followed the hand and the static root, so
        // one hip rendered at ~0.35x its width. This replays real seeded idle trajectories (IDLE's
        // base elbow bend included) and requires every mesh edge that runs through visible hip,
        // thigh or lower-torso pixels to keep its length.
        LeonTextureMask mask = LeonTextureMask.load();
        float worst = 1f;
        for (long seed : new long[]{0L, 7L, 17L, 42L, 91L}) {
            PostureBehaviour posture = new PostureBehaviour(seed);
            LeonMeshRig mesh = productionMesh();
            LeonRigBinder binder = new LeonRigBinder(mesh.rig());
            float dt = 1f / 60f;
            for (int i = 0; i < 60 * 45; i++) {
                LeonPose pose = new LeonPose();
                pose.set(LeonChannel.ELBOW_L, 0.12f);
                pose.set(LeonChannel.ELBOW_R, 0.12f);
                posture.update(dt, 1f, pose);
                binder.apply(pose);
                if (i % 5 != 0) continue;
                mesh.updateFromSolvedRig();
                worst = Math.max(worst, mask.worstOpaqueEdgeRatio(mesh, 340f, 560f));
            }
        }
        // Measured 1.004 with anatomical skinning; the pre-fix mesh measures 15.6 at plain IDLE.
        assertTrue("idle must not squeeze or stretch the hips/thighs, worst opaque edge ratio "
                + worst, worst < 1.05f);
    }

    @Test
    public void idleWeightShiftExtremesKeepTheHipsRigid() throws Exception {
        // The trajectory test above samples what PostureBehaviour happens to do; this sweeps every
        // contributing channel to its extreme independently (weight shift, shoulder noise, torso
        // twist and lean, each with both signs), since they evolve on independent schedules and the
        // worst case is when they align.
        LeonTextureMask mask = LeonTextureMask.load();
        // Full idle amplitude. An earlier workaround shrank idle sway to 0.2x to hide this defect;
        // the skinning fix makes that unnecessary, so the sweep covers the real, undamped range.
        final float sway = 1f;
        float worst = 1f;
        for (float weightShift : new float[]{0.85f, -0.85f, 0.5f, -0.5f}) {
            for (float shoulder : new float[]{1f, -1f, 0f}) {
                for (float twist : new float[]{1f, -1f, 0f}) {
                    for (float lean : new float[]{1f, -1f, 0f}) {
                        LeonMeshRig mesh = posed(
                                "ELBOW_L", "0.12", "ELBOW_R", "0.12",
                                "WEIGHT_SHIFT", String.valueOf(weightShift * sway),
                                "SHOULDER_L", String.valueOf(shoulder * 0.17f * sway),
                                "SHOULDER_R", String.valueOf(shoulder * 0.17f * sway),
                                "ARM_L_SWING", String.valueOf((weightShift * 0.10f + shoulder * 0.09f) * sway),
                                "ARM_R_SWING", String.valueOf((weightShift * 0.10f + shoulder * 0.09f) * sway),
                                "TORSO_TWIST", String.valueOf(twist * 0.3f * sway),
                                "TORSO_LEAN_X", String.valueOf(lean * 0.3f * sway));
                        worst = Math.max(worst, mask.worstOpaqueEdgeRatio(mesh, 340f, 560f));
                    }
                }
            }
        }
        assertTrue("idle sway extremes must not deform visible hip/thigh pixels, worst opaque edge "
                + "ratio " + worst, worst < 1.05f);
    }

    @Test
    public void fullAmplitudeWeightShiftMovesTheHipsWithoutSqueezingThem() throws Exception {
        // Independent of PostureBehaviour's idle damping: WEIGHT_SHIFT at its full +-1 range (as a
        // walk or a future state may drive it) must move the pelvis, not compress it.
        LeonTextureMask mask = LeonTextureMask.load();
        for (String shift : new String[]{"1", "-1"}) {
            LeonMeshRig mesh = posed("WEIGHT_SHIFT", shift, "ELBOW_L", "0.12", "ELBOW_R", "0.12");
            float worst = mask.worstOpaqueEdgeRatio(mesh, 340f, 560f);
            assertTrue("WEIGHT_SHIFT=" + shift + " worst opaque hip edge ratio " + worst, worst < 1.1f);
        }
    }

    @Test
    public void bendingTheElbowDoesNotStretchVisibleSkinIntoASliver() throws Exception {
        // The CHIN gesture's channels at their maximum. A bent elbow legitimately compresses the
        // inside and stretches the outside of the joint; what must not happen is the diagonal
        // sliver an unblended seam draws (measured ~13x before the elbow/wrist blends existed).
        LeonTextureMask mask = LeonTextureMask.load();
        LeonMeshRig mesh = posed("ELBOW_R", "0.95", "HAND_R_RAISE", "0.95");
        float worst = mask.worstOpaqueEdgeRatio(mesh, 150f, 740f);
        assertTrue("a bent elbow stretched visible pixels by " + worst, worst < 2.5f);
    }

    @Test
    public void armSwingAloneDoesNotStretchTheShoulderSeam() throws Exception {
        // Swinging only the arm once fanned a smear out of the collar (the y<205 block binds by row
        // alone, so the shoulder needs its own blend into the arm).
        LeonTextureMask mask = LeonTextureMask.load();
        LeonMeshRig mesh = posed("ARM_R_SWING", "0.95");
        float worst = mask.worstOpaqueEdgeRatio(mesh, 150f, 740f);
        assertTrue("an arm swing stretched visible pixels by " + worst, worst < 2.2f);
    }

    @Test
    public void armMotionNeverDragsTheHipOrThigh() throws Exception {
        // The other half of the hip defect: with the hip's outer strip bound to the hand, any arm
        // gesture dragged the hip and thigh edge with it (measured 5.5x under an arm swing alone).
        LeonTextureMask mask = LeonTextureMask.load();
        String[][] poses = {
                {"ARM_R_SWING", "0.95"}, {"ARM_L_SWING", "-0.95"},
                {"ELBOW_R", "0.95", "HAND_R_RAISE", "0.95"},
                {"ELBOW_L", "0.7", "HAND_L_RAISE", "0.6"}};
        for (String[] pose : poses) {
            float worst = mask.worstOpaqueEdgeRatio(posed(pose), 452f, 740f);
            assertTrue(String.join(" ", pose) + " deformed visible hip/leg pixels by " + worst,
                    worst < 1.02f);
        }
    }

    @Test
    public void idleElbowBendMovesTheHandRigidly() {
        // "Wrists bend inward and look wrong" at plain IDLE (a 12% elbow bend). The hand is a child
        // of the forearm, so it must swing as one rigid piece: pairwise distances between vertices
        // inside the hand, below the wrist blend band, must not change.
        LeonMeshRig mesh = posed("ELBOW_R", "0.12");
        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        java.util.List<Integer> hand = new java.util.ArrayList<>();
        for (int i = 0; i < canon.length / 2; i++) {
            float side = canon[i * 2] - 192f;
            float y = canon[i * 2 + 1];
            if (y >= 378f && y < 440f && side > LeonMeshRig.bodyEdgeHalfWidth(y) + 3f && side < 140f) {
                hand.add(i);
            }
        }
        assertTrue("expected hand vertices in the production mesh", hand.size() >= 12);
        float worst = 1f;
        for (int a = 0; a < hand.size(); a++) {
            for (int b = a + 1; b < hand.size(); b++) {
                int i0 = hand.get(a);
                int i1 = hand.get(b);
                float rest = (float) Math.hypot(canon[i1 * 2] - canon[i0 * 2], canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
                float now = (float) Math.hypot(deformed[i1 * 2] - deformed[i0 * 2], deformed[i1 * 2 + 1] - deformed[i0 * 2 + 1]);
                worst = Math.max(worst, Math.max(now / rest, rest / now));
            }
        }
        assertTrue("a small idle elbow bend warped the hand by " + worst + " (cols=" + cols + ")",
                worst < 1.01f);
    }
}
