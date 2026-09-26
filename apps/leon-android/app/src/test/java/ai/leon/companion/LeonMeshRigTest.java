package ai.leon.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
        // Two bands, both now including IDLE's own always-on base pose (ELBOW_L/R=0.12, see
        // LeonStateProfile) alongside the swept weight-shift/shoulder range -- an earlier version of
        // this test omitted that base pose entirely, which hid most of the real worst case: the
        // torso/limb band alone (no base elbow) measured under 1.25, but adding the base elbow that
        // real IDLE always applies pushed it to 1.52, and the hip/hand-fade band went from ~3.97 to
        // 65x in one sampled real trajectory. Both bands below are swept with that base pose present,
        // so this can't happen again silently.
        //
        // y[300,400] is the torso/limb (chest/shoulder) boundary, fixed by this file's COLUMN_BLEND_*
        // blend; with the base elbow pose included it stays under 1.3 across the whole real combined
        // range (measured worst 1.232 at PostureBehaviour's post-IDLE_SWAY_SCALE amplitude).
        //
        // y[395,485] (hip/thigh vs. hand-fade-to-root) is NOT fixed by any blend -- a blend pulling
        // this column toward the hips bone measured well here but a further Codex review caught it
        // folding an actively-raised hand instead (this file's weights are assigned once from the
        // rest pose -- see the class doc comment -- so a blend that helps a resting hand's small
        // divergence necessarily hurts a reaching hand's large one; there is no single blend weight
        // that avoids both). That blend was reverted, and PostureBehaviour's own sway amplitude was
        // reduced instead (IDLE_SWAY_SCALE=0.2, see its own comment). This sweep also includes
        // TORSO_TWIST/TORSO_LEAN_X extremes ({@code torso} below) -- a Codex review of an earlier
        // version of this fix caught that leaving them out hid part of the real worst case, since
        // they move the chest/spine (and everything hanging off it, including the whole arm chain)
        // relative to the hips bone just like the arm-swing channels do, through a different bone.
        // With all three swept together: 6.076, down from 65.302 at full amplitude pre-scale. It is
        // still not fully closed -- IDLE's base elbow bend alone, with zero sway at all, already
        // stretches this seam to ~3.1x, a floor no amount of sway reduction can get under. Closing
        // that remainder needs finer mesh resolution at this seam or reducing the base elbow bend
        // itself, neither of which is an animation-amplitude change.
        for (float weightShift : new float[]{0.35f, 0.5f, -0.5f, 0.65f, -0.65f, 0.85f, -0.85f}) {
            for (float shoulder : new float[]{0f, 0.5f, -0.5f, 1f, -1f}) {
                for (float torso : new float[]{0f, 0.5f, -0.5f, 1f, -1f}) {
                    float worstUpper = worstHipBandSeamRatio(weightShift, shoulder, torso, 300f, 400f);
                    assertTrue("idle's autonomous weight-shift (sign=" + weightShift + ", shoulder="
                            + shoulder + ", torso=" + torso + ") must not pinch the torso/limb column "
                            + "seam, worst adjacent-column ratio was " + worstUpper, worstUpper < 1.3f);
                    float worstLower = worstHipBandSeamRatio(weightShift, shoulder, torso, 395f, 485f);
                    assertTrue("idle's autonomous weight-shift (sign=" + weightShift + ", shoulder="
                            + shoulder + ", torso=" + torso + ") hip/hand-fade column seam regressed "
                            + "well beyond its known, still-unresolved bound, worst adjacent-column "
                            + "ratio was " + worstLower, worstLower < 6.2f);
                }
            }
        }
    }

    @Test
    public void idleTrajectoryDoesNotPinchTheHipBeyondTheSyntheticSweepsBound() {
        // The synthetic sweep above (static extremes of every contributing channel, all at once)
        // measures 6.076 worst case -- but a Codex review of an earlier version of this fix pointed
        // out that PostureBehaviour's springs and independent noise sources don't just sit at their
        // extremes, they transit through them on their own schedules, and replaying a real seeded
        // trajectory found a *worse* worst case (8-17x pre-fix) than sweeping static combinations
        // ever did. Confirmed after the fix too: replaying these five seeds for 90 simulated seconds
        // each finds 6.478 (seed 91), higher than the static sweep's 6.076, even though no single
        // static combination in that sweep is more extreme than what these seeds pass through. This
        // is a second, independent regression guard for that reason -- it can catch a transient
        // combination the static sweep's fixed sample points miss.
        long[] seeds = {0L, 7L, 17L, 42L, 91L};
        float worstLower = 1f;
        float worstUpper = 1f;
        for (long seed : seeds) {
            PostureBehaviour posture = new PostureBehaviour(seed);
            LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
            LeonRigBinder binder = new LeonRigBinder(mesh.rig());
            float dt = 1f / 60f;
            for (int i = 0; i < 60 * 90; i++) {
                LeonPose pose = new LeonPose();
                pose.set(LeonChannel.ELBOW_L, 0.12f);
                pose.set(LeonChannel.ELBOW_R, 0.12f);
                posture.update(dt, 1f, pose);
                binder.apply(pose);
                mesh.updateFromSolvedRig();
                worstLower = Math.max(worstLower, seamRatioInBand(mesh, 395f, 485f));
                worstUpper = Math.max(worstUpper, seamRatioInBand(mesh, 300f, 400f));
            }
        }
        assertTrue("a real idle trajectory must not pinch the torso/limb column seam beyond its "
                + "known bound, worst adjacent-column ratio was " + worstUpper, worstUpper < 1.3f);
        assertTrue("a real idle trajectory's hip/hand-fade column seam regressed well beyond its "
                + "known, still-unresolved bound, worst adjacent-column ratio was " + worstLower,
                worstLower < 7f);
    }

    /**
     * Worst adjacent-column distance ratio (either direction) in the given design-space y band,
     * under IDLE's own always-on base pose (ELBOW_L/R=0.12) plus WEIGHT_SHIFT, independent shoulder
     * noise, and independent torso twist/lean, combined exactly as PostureBehaviour combines them
     * post-IDLE_SWAY_SCALE (see its own ARM_*_SWING, SHOULDER_*, and TORSO_* lines): {@code shoulder}
     * stands in for its shL/shR noise terms (each roughly in [-1,1]), which drive SHOULDER_L/R
     * directly (*0.17) and ARM_*_SWING independently of WEIGHT_SHIFT's own contribution (*0.09);
     * {@code torso} stands in for its twist/lean terms (each roughly in [-1,1]), which drive
     * TORSO_TWIST and TORSO_LEAN_X (*0.3); all three scaled by the same 0.2 factor PostureBehaviour
     * applies to just these channels.
     */
    private static float worstHipBandSeamRatio(float weightShift, float shoulder, float torso,
                                                float yLo, float yHi) {
        final float sway = 0.2f; // mirrors PostureBehaviour.IDLE_SWAY_SCALE
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        LeonRigBinder binder = new LeonRigBinder(mesh.rig());
        LeonPose pose = new LeonPose();
        pose.set(LeonChannel.ELBOW_L, 0.12f);
        pose.set(LeonChannel.ELBOW_R, 0.12f);
        pose.set(LeonChannel.WEIGHT_SHIFT, weightShift * sway);
        pose.set(LeonChannel.SHOULDER_L, shoulder * 0.17f * sway);
        pose.set(LeonChannel.SHOULDER_R, shoulder * 0.17f * sway);
        pose.set(LeonChannel.ARM_L_SWING, weightShift * 0.10f * sway + shoulder * 0.09f * sway);
        pose.set(LeonChannel.ARM_R_SWING, weightShift * 0.10f * sway + shoulder * 0.09f * sway);
        pose.set(LeonChannel.TORSO_TWIST, torso * 0.3f * sway);
        pose.set(LeonChannel.TORSO_LEAN_X, torso * 0.3f * sway);
        binder.apply(pose);
        mesh.updateFromSolvedRig();
        return seamRatioInBand(mesh, yLo, yHi);
    }

    /** Worst adjacent-column distance ratio (either direction) in the given design-space y band. */
    private static float seamRatioInBand(LeonMeshRig mesh, float yLo, float yHi) {
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
