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
        // Widened from the original 375-460 (chosen against this file's old, wrong 360x640 test
        // dimensions -- see createForTest) to 250-600: at the real 941x1672 production resolution
        // the mesh's row pitch is ~69 design units, so a 20-85-unit window can land between rows
        // and check nothing at all. 250-600 reliably spans several real adjacent-row pairs in the
        // one real column (x=323) that falls past x=300 on-canvas, covering the same fingertip/
        // hand-to-hip-fade seam this test targets, just measured against geometry that matches
        // what's actually rendered.
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
                if (Math.min(y0, y1) < 250f || Math.max(y0, y1) > 600f) continue; // hand-to-hip fade
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
        // 1.1f was tuned against this file's old, wrong 360x640 createForTest geometry (row pitch
        // ~27 units); at the real 941x1672 resolution (row pitch ~69 units) the same HAND_R_RAISE
        // pose measures ~1.117 on the one real fingertip column (x=323) -- confirmed unrelated to
        // this PR's column-blend fix by re-running with COLUMN_BLEND_MARGIN forced to 0 (x=323's
        // legSide is 131, outside that blend's reach either way): identical 1.1173618 both times.
        // 1.2 keeps real headroom below this same file's own known-bad baseline for this exact
        // pose/bug (~1.30, from PR #184's probe reproduction of the pre-fix compound-rotation
        // regression), so this still fails if that regression comes back, while accepting the
        // real, correctly-measured mesh's coarser clean baseline instead of the old test's wrong
        // finer-mesh clean baseline (~0.79-0.86).
        assertTrue("a hand raise alone must not stretch the fingertip/hip-fade seam, worst "
                + "adjacent-row stretch ratio was " + worstRatio, worstRatio < 1.2f);
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
        // x0 > 294, y in [290,510]: at the real 941x1672 production resolution (see createForTest)
        // only ONE on-canvas column (x=323, legSide=131) sits fully outside the torso/limb column
        // blend (legSide > COLUMN_BLEND_END=102); a wider x0 > 250 was tried first to reach the
        // "several vertices" this assertion wants, but that pulls in x=255 (legSide=63), a column
        // still inside COLUMN_BLEND_START/END's intentional torso-blend margin -- not hand at all
        // near rest, and genuinely not rigid with a real hand vertex, so it measured a shear
        // (~1.29) between two different weighting domains, not a hand-shape defect. Restricting to
        // x0 > 294 and widening y instead (to the same column's 4 real on-canvas rows spanning the
        // forearm/hand and hand/root blend bands -- see sideWeightsFor/legSideFadeWeightsFor)
        // measures actual hand-region shape stability and drops the worst ratio to ~1.095.
        for (int col = 0; col <= cols; col++) {
            float x0 = canon[col * 2];
            if (x0 <= 294f || x0 > 384f) continue;
            for (int row = 0; row <= rows; row++) {
                int i = row * (cols + 1) + col;
                float y0 = canon[i * 2 + 1];
                if (y0 >= 290f && y0 <= 510f) handVertices.add(i);
            }
        }
        assertTrue("expected several hand vertices in the test mesh geometry",
                handVertices.size() >= 4);

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
        // 1.05f assumed a purely rigid hand under a small bend; at real resolution no on-canvas
        // vertex is ever 100% hand-bound (the pure-hand window is y in [375,395), a 20-unit band
        // no real row lands in at ~69-unit row pitch -- see sideWeightsFor), so every real "hand"
        // vertex here is intentionally blended toward forearm or root and will not be perfectly
        // rigid with its neighbors. Measured worst ratio among the 4 real vertices is ~1.095;
        // 1.12 keeps headroom while still catching a materially wider/miscalibrated blend margin.
        assertTrue("a small idle elbow bend must not warp the hand's shape, worst pairwise "
                + "distance ratio was " + worstRatio, worstRatio < 1.12f);
    }

    @Test
    public void idleWeightShiftDoesNotPinchTheHipAgainstTheRestingHand() {
        // Real-device report: at plain IDLE, with no gesture and no touch, the hip visibly
        // compressed right where the hand rests against it. Unlike every other seam bug this file
        // already covers (all triggered by an explicit large-ish gesture channel), this one needs
        // no gesture at all -- PostureBehaviour's autonomous idle weight-shift alone reproduces it,
        // because it drives WEIGHT_SHIFT (a translation/rotation of the hips bone) AND independently
        // feeds a fraction of the same signal into ARM_*_SWING ("arms hang from the shoulders, so
        // they inherit part of the sway"), a rotation around the shoulder. The two bones never move
        // in lockstep, so the column boundary between the hip-bound centre columns and the
        // hand-bound side columns (right at hand-resting-on-hip height) used to be a hard,
        // completely unblended cutoff -- every other seam in this class blends smoothly, this one
        // didn't. This measures adjacent-COLUMN (not adjacent-row) stretch, since this seam runs
        // vertically alongside the hip, not across a horizontal joint like the others above.
        // Both signs: PostureBehaviour's weightTarget alternates ("always shift away from the
        // current side"), and a Codex review of this PR caught that they are not equivalent here --
        // deformedDist/restDist alone only ever grows above 1 for a seam that *stretches*, so a pose
        // whose seam *compresses* instead (ratio below 1) could never move worstRatio and would pass
        // even the unfixed hard-cutoff mesh. Measured directly (see PR discussion): the pre-fix seam
        // hit a symmetric worst ratio of 1.28 at +0.5 but 1.39 at -0.5 -- negative was the worse
        // direction, and this test would have missed it entirely with only one sign and a one-way
        // ratio.
        for (float weightShift : new float[]{0.5f, -0.5f}) {
            float worstRatio = worstHipBandSeamRatio(weightShift);
            assertTrue("idle's autonomous weight-shift (sign=" + weightShift + ") must not pinch "
                    + "the hip/hand column seam, worst adjacent-column ratio was " + worstRatio,
                    worstRatio < 1.25f);
        }
    }

    /** Worst adjacent-column distance ratio (either direction) in the hip/hand-resting band. */
    private static float worstHipBandSeamRatio(float weightShift) {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.WEIGHT_SHIFT, weightShift);
        pose.set(LeonChannel.ARM_L_SWING, weightShift * 0.18f);
        pose.set(LeonChannel.ARM_R_SWING, weightShift * 0.18f);
        binder.apply(pose);
        mesh.updateFromSolvedRig();

        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        float worstRatio = 1f;
        int seamColumnsChecked = 0;
        for (int row = 0; row <= rows; row++) {
            float y = canon[(row * (cols + 1)) * 2 + 1];
            if (y < 300f || y > 400f) continue; // hip / hand-resting-on-hip height
            for (int col = 0; col < cols; col++) {
                int i0 = row * (cols + 1) + col;
                int i1 = row * (cols + 1) + col + 1;
                float x0 = canon[i0 * 2];
                float x1 = canon[i1 * 2];
                if (x1 < 0f || x0 > 384f) continue; // off the texture entirely; never rendered
                float restDist = (float) Math.hypot(
                        canon[i1 * 2] - canon[i0 * 2], canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
                if (restDist < 1f) continue;
                seamColumnsChecked++;
                float deformedDist = (float) Math.hypot(
                        deformed[i1 * 2] - deformed[i0 * 2], deformed[i1 * 2 + 1] - deformed[i0 * 2 + 1]);
                // max(ratio, 1/ratio): a pinch is either the seam stretching OR compressing, and
                // deformedDist/restDist alone only ever detects the former.
                float ratio = deformedDist / restDist;
                worstRatio = Math.max(worstRatio, Math.max(ratio, 1f / ratio));
            }
        }
        assertTrue("expected several hip-band seam columns in the test mesh geometry",
                seamColumnsChecked > 4);
        return worstRatio;
    }
}
