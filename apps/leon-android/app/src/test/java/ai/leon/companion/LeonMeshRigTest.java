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
        // x0 > 294: this was the pure-hand boundary back when COLUMN_BLEND_MARGIN was 30
        // (COLUMN_BLEND_END 102, absolute boundary 192+102=294). A second real-device report (the
        // hip/hand-fade seam stretching under PostureBehaviour's real idle amplitude range,
        // including its independent SHOULDER_* noise term, not just WEIGHT_SHIFT) required widening
        // that margin to 72 (absolute boundary 192+144=336) to keep the torso/limb seam under this
        // file's other thresholds across the real range. x0 > 294 now includes two columns (295/321)
        // that are genuinely blended, not pure hand, under the new margin -- this test's original
        // "strictly pure hand" premise no longer holds for its full vertex set. Rather than shrink to
        // only the two still-literally-pure columns (347/373, which would make this test trivial: two
        // same-bone vertices are rigid by construction, so their pairwise ratio can never move,
        // testing nothing), this keeps the same four-column window and treats it as "hand-dominant,
        // with intentional partial torso-blend at its inner edge" -- consistent with how this file's
        // other tests already handle partially-blended vertex sets.
        for (int col = 0; col <= cols; col++) {
            float x0 = canon[col * 2];
            if (x0 <= 294f || x0 > 384f) continue;
            for (int row = 0; row <= rows; row++) {
                int i = row * (cols + 1) + col;
                float y0 = canon[i * 2 + 1];
                if (y0 >= 375f && y0 < 395f) handVertices.add(i);
            }
        }
        // Exactly 4, not >4: of this mesh's 5 columns past the old pure-hand boundary (x0 > 294),
        // one (x0=398.7) is off the 384-wide texture and already excluded by the x0 > 384 check
        // above, same as every other seam test in this class excludes off-canvas columns.
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
        // 1.05f assumed all four columns were pure hand; two of them (295/321) no longer are (see
        // above), so this small idle bend now measures ~1.092 across the mix -- still a small,
        // real shear from the intentional partial blend, not the uncontrolled warp this test was
        // originally written to catch. 1.12 keeps headroom while still catching a materially wider
        // or miscalibrated blend margin.
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
        // even the unfixed hard-cutoff mesh.
        //
        // Two bands, real but NOT equally fixed: y[300,400] is the torso/limb (chest/shoulder)
        // boundary, and is genuinely fixed by this file's COLUMN_BLEND_* blend -- swept below across
        // PostureBehaviour's actual weightTarget range (0.35 to 0.85, both signs) and its independent
        // shoulder noise (a Codex review of an earlier version of this fix caught that sweeping
        // WEIGHT_SHIFT alone, deriving ARM_*_SWING from it the way PostureBehaviour does, missed that
        // the shoulder noise feeds ARM_*_SWING independently AND drives SHOULDER_L/R itself), it stays
        // under 1.25 across the whole real combined range.
        //
        // y[395,485] (hip/thigh vs. hand-fade-to-root) is NOT fixed. A second real-device report
        // ("18 seconds and onward" of plain idle) traced back to this exact seam; a blend pulling
        // this column toward the hips bone measured well here but a further Codex review caught it
        // folding an actively-raised hand instead (this file's weights are assigned once from the
        // rest pose -- see the class doc comment -- so a blend that helps a resting hand's small
        // divergence necessarily hurts a reaching hand's large one; there is no single blend weight
        // that avoids both). That blend was reverted. What's left, measured with it removed: even
        // WEIGHT_SHIFT and shoulder noise alone (no gesture, this test's own scope) reach ~3.97;
        // stacking IDLE's own always-on base pose (ELBOW_*=0.12) on top of a real simulated
        // PostureBehaviour trajectory reached over 100x in one sampled run. This is a real,
        // unresolved limitation -- closing it needs either finer mesh resolution at this seam or
        // less idle animation amplitude, both decisions beyond a mesh-weighting fix, not something
        // this test can respons­ibly assert a tight bound for. The sweep below still runs and prints
        // its result (so a future change that measurably worsens it further is visible in the
        // failure message), but the threshold is loose enough to only catch that kind of gross
        // regression, not to claim this seam is fixed.
        for (float weightShift : new float[]{0.35f, 0.5f, -0.5f, 0.65f, -0.65f, 0.85f, -0.85f}) {
            for (float shoulder : new float[]{0f, 0.5f, -0.5f, 1f, -1f}) {
                float worstUpper = worstHipBandSeamRatio(weightShift, shoulder, 300f, 400f);
                assertTrue("idle's autonomous weight-shift (sign=" + weightShift + ", shoulder="
                        + shoulder + ") must not pinch the torso/limb column seam, worst "
                        + "adjacent-column ratio was " + worstUpper, worstUpper < 1.25f);
                float worstLower = worstHipBandSeamRatio(weightShift, shoulder, 395f, 485f);
                assertTrue("idle's autonomous weight-shift (sign=" + weightShift + ", shoulder="
                        + shoulder + ") hip/hand-fade column seam regressed well beyond its known, "
                        + "still-unresolved bound, worst adjacent-column ratio was " + worstLower,
                        worstLower < 6f);
            }
        }
    }

    /**
     * Worst adjacent-column distance ratio (either direction) in the given design-space y band,
     * under WEIGHT_SHIFT and independent shoulder noise combined exactly as PostureBehaviour
     * combines them (see its own ARM_*_SWING/SHOULDER_* lines): {@code shoulder} stands in for its
     * shL/shR noise terms (each roughly in [-1,1]), which drive SHOULDER_L/R directly (*0.17) and
     * ARM_*_SWING independently of WEIGHT_SHIFT's own contribution (*0.09).
     */
    private static float worstHipBandSeamRatio(float weightShift, float shoulder, float yLo, float yHi) {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.WEIGHT_SHIFT, weightShift);
        pose.set(LeonChannel.SHOULDER_L, shoulder * 0.17f);
        pose.set(LeonChannel.SHOULDER_R, shoulder * 0.17f);
        pose.set(LeonChannel.ARM_L_SWING, weightShift * 0.10f + shoulder * 0.09f);
        pose.set(LeonChannel.ARM_R_SWING, weightShift * 0.10f + shoulder * 0.09f);
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
            if (y < yLo || y > yHi) continue;
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
        assertTrue("expected several seam columns in the test mesh geometry for y in [" + yLo + "," + yHi + "]",
                seamColumnsChecked > 4);
        return worstRatio;
    }
}
