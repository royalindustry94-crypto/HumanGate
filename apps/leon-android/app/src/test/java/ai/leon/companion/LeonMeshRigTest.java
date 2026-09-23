package ai.leon.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonChannel;
import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.LeonRigBinder;
import ai.leon.companion.render.LeonMeshRig;

import org.junit.Test;

public class LeonMeshRigTest {
    @Test
    public void everyVertexWeightSumsToOne() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        for (LeonMeshRig.VertexWeights weights : mesh.weights()) {
            assertEquals(1f, weights.totalWeight(), 0.001f);
            assertTrue(weights.allFinite());
        }
    }

    @Test
    public void restPoseVerticesAreCanonical() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        assertArrayEquals(mesh.canonicalVertices(), mesh.deformIdentityForTest(), 0.0001f);
    }

    @Test
    public void fullMeshHasExpectedVertexCount() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        assertEquals((16 + 1) * (28 + 1) * 2, mesh.canonicalVertices().length);
    }

    @Test
    public void bendingTheElbowDoesNotStretchAnyMeshSeamIntoASliver() {
        // Reproduces the real-device defect: a bent elbow (the CHIN gesture's channel combination)
        // turned into a thin diagonal bar reaching across the torso. Two things caused it: the
        // arm/forearm/hand region bound each row rigidly to exactly one bone with a hard cutoff at
        // the elbow and wrist (unlike the legs, which blend continuously across the hip and knee),
        // and separately, everything below the arm's y-range rebound to hip/thigh regardless of
        // x-offset, so a swung hand's own column snapped back to a stationary hip position one row
        // below it. drawBitmapMesh always draws the quad connecting grid-adjacent rows regardless of
        // which bone(s) they're bound to, so either hard seam stretches into a sliver once the two
        // sides diverge far enough. This measures the worst on-canvas adjacent-row stretch under a
        // large bend -- the original rigid/unaware binding measures ~13x here; with the seams blended
        // and the joint rotations clamped to what the mesh actually renders cleanly (verified by
        // rendering it, not just this vertex math -- see LeonRigBinder's ARM/ELBOW/HAND_ROTATION_LIMIT
        // constants), it measures ~2x, i.e. real hand motion rather than a seam artifact.
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.ELBOW_R, 0.95f);
        pose.set(LeonChannel.HAND_R_RAISE, 0.95f);
        binder.apply(pose);
        mesh.updateFromSolvedRig();

        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        float worstRatio = 0f;
        for (int col = 0; col <= cols; col++) {
            float colX = canon[col * 2];
            if (colX < 0f || colX > 384f) continue; // off the texture entirely; never rendered
            for (int row = 0; row < rows; row++) {
                int i0 = row * (cols + 1) + col;
                int i1 = (row + 1) * (cols + 1) + col;
                float restDist = (float) Math.hypot(
                        canon[i1 * 2] - canon[i0 * 2], canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
                if (restDist < 1f) continue;
                float deformedDist = (float) Math.hypot(
                        deformed[i1 * 2] - deformed[i0 * 2], deformed[i1 * 2 + 1] - deformed[i0 * 2 + 1]);
                worstRatio = Math.max(worstRatio, deformedDist / restDist);
            }
        }
        assertTrue("a bent elbow must not stretch a mesh seam into a sliver, worst on-canvas "
                + "adjacent-row stretch ratio was " + worstRatio, worstRatio < 4f);
    }

    @Test
    public void armSwingAloneDoesNotStretchTheShoulderSeam() {
        // A third real defect rendering the mesh actually caught: swinging just the arm (no elbow or
        // hand movement at all -- ARM_R_SWING alone) fanned a fuzzy diagonal smear out of the collar
        // toward the shoulder. The y<205 block above binds every column to a head/neck/chest blend
        // without checking side, so an arm-side column just above y=205 was still chest-bound at rest;
        // swinging the arm without blending that seam stretched the collar/chest toward the arm's new
        // position. This measures the same worst on-canvas adjacent-row stretch for that isolated
        // channel -- the unblended shoulder seam measured double digits here before the fix.
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.ARM_R_SWING, 0.95f);
        binder.apply(pose);
        mesh.updateFromSolvedRig();

        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        float worstRatio = 0f;
        for (int col = 0; col <= cols; col++) {
            float colX = canon[col * 2];
            if (colX < 0f || colX > 384f) continue;
            for (int row = 0; row < rows; row++) {
                int i0 = row * (cols + 1) + col;
                int i1 = (row + 1) * (cols + 1) + col;
                float restDist = (float) Math.hypot(
                        canon[i1 * 2] - canon[i0 * 2], canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
                if (restDist < 1f) continue;
                float deformedDist = (float) Math.hypot(
                        deformed[i1 * 2] - deformed[i0 * 2], deformed[i1 * 2 + 1] - deformed[i0 * 2 + 1]);
                worstRatio = Math.max(worstRatio, deformedDist / restDist);
            }
        }
        assertTrue("swinging the arm alone must not stretch the shoulder seam, worst on-canvas "
                + "adjacent-row stretch ratio was " + worstRatio, worstRatio < 3f);
    }

    @Test
    public void handRaiseAloneDoesNotStretchTheWristSeam() {
        // THINKING's chin/pocket gesture drives HAND_R_RAISE, which used to also swing the arm bone
        // alongside bending the forearm. Each bone's own rotation stayed within LeonRigBinder's
        // render-safe clamp, but the two compound in world space (a child bone's rotation adds to its
        // parent's), pushing the net rotation at the wrist past what this mesh's forearm/hand blend
        // seam can render cleanly -- a real-device screenshot showed a skin-toned sliver folding out
        // near the pocket, in the fingertip region where the hand blends toward the hip (LeonMeshRig's
        // leg-side fade below the hand). Restricted to exactly that zone: a global worst-row-stretch
        // (or a wider zone that includes the unrelated arm/torso boundary column) is dominated by
        // geometry this fix never touches and measures ~2.0 unchanged whether or not the compound
        // rotation is reintroduced -- confirmed by running this same measurement against the pre-fix
        // binder, where it reported an identical ratio and so never would have caught this regression.
        // This zone/threshold was chosen by measuring the same known-clean pose (ELBOW_R alone, no
        // shoulder swing -- see armSwingAloneDoesNotStretchTheShoulderSeam above) at ~0.86 and this
        // fix's HAND_R_RAISE-alone pose at ~0.79, against the pre-fix binder's ~1.30 for that same pose.
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.HAND_R_RAISE, 0.95f);
        binder.apply(pose);
        mesh.updateFromSolvedRig();

        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        float worstRatio = 0f;
        int seamRowsChecked = 0;
        for (int col = 0; col <= cols; col++) {
            float colX = canon[col * 2];
            if (colX < 300f || colX > 384f) continue; // fingertip columns, on-canvas only
            for (int row = 0; row < rows; row++) {
                int i0 = row * (cols + 1) + col;
                int i1 = (row + 1) * (cols + 1) + col;
                float y0 = canon[i0 * 2 + 1];
                float y1 = canon[i1 * 2 + 1];
                if (Math.min(y0, y1) < 375f || Math.max(y0, y1) > 460f) continue; // hand-to-hip fade
                float restDist = (float) Math.hypot(
                        canon[i1 * 2] - canon[i0 * 2], canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
                if (restDist < 1f) continue;
                seamRowsChecked++;
                float deformedDist = (float) Math.hypot(
                        deformed[i1 * 2] - deformed[i0 * 2], deformed[i1 * 2 + 1] - deformed[i0 * 2 + 1]);
                worstRatio = Math.max(worstRatio, deformedDist / restDist);
            }
        }
        assertTrue("expected several fingertip-seam rows in the test mesh geometry", seamRowsChecked > 2);
        assertTrue("a hand raise alone must not stretch the fingertip/hip-fade seam, worst "
                + "adjacent-row stretch ratio was " + worstRatio, worstRatio < 1.1f);
    }

    @Test
    public void idleElbowBendDoesNotWarpTheHandsShape() {
        // The hand is a child of the forearm, so it correctly swings as a rigid whole when the elbow
        // bends -- that is real anatomy, not a bug. What must not happen is the hand's own shape
        // warping, which is what a second real-device report called "wrists bend inward and look
        // wrong" at plain IDLE (a mere 12% elbow bend -- LeonStateProfile's idle base pose, nowhere
        // near the CHIN gesture's extreme). A blend zone between forearm and hand that is too wide
        // mixes their transforms by a different ratio at every row, shearing the hand instead of
        // moving it as one piece. This checks that pairwise distances between vertices well inside
        // the hand (away from the narrow wrist blend band) are preserved under a small elbow bend --
        // i.e. the hand moves but does not deform.
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.ELBOW_R, 0.12f);
        binder.apply(pose);
        mesh.updateFromSolvedRig();

        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        java.util.List<Integer> handVertices = new java.util.ArrayList<>();
        for (int col = 0; col <= cols; col++) {
            float x0 = canon[col * 2];
            if (x0 <= 264f || x0 > 384f) continue;
            for (int row = 0; row <= rows; row++) {
                int i = row * (cols + 1) + col;
                float y0 = canon[i * 2 + 1];
                if (y0 >= 375f && y0 < 395f) handVertices.add(i);
            }
        }
        assertTrue("expected several hand vertices in the test mesh geometry",
                handVertices.size() > 4);

        float worstRatio = 1f;
        for (int a = 0; a < handVertices.size(); a++) {
            for (int b = a + 1; b < handVertices.size(); b++) {
                int i0 = handVertices.get(a);
                int i1 = handVertices.get(b);
                float rest = (float) Math.hypot(
                        canon[i1 * 2] - canon[i0 * 2], canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
                if (rest < 1f) continue;
                float def = (float) Math.hypot(
                        deformed[i1 * 2] - deformed[i0 * 2], deformed[i1 * 2 + 1] - deformed[i0 * 2 + 1]);
                worstRatio = Math.max(worstRatio, Math.max(def / rest, rest / def));
            }
        }
        assertTrue("a small idle elbow bend must not warp the hand's shape, worst pairwise "
                + "distance ratio was " + worstRatio, worstRatio < 1.05f);
    }
}
