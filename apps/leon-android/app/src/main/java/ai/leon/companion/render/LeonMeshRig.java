package ai.leon.companion.render;

import ai.leon.companion.asset.PhotoAlignment;
import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Mat2D;
import ai.leon.companion.rig.Rig;

/**
 * Low-density weighted bitmap mesh for Leon's one continuous production texture.
 *
 * <p>Each canonical vertex is authored in rig design space. At construction time we capture
 * every influencing bone's rest-world transform. At runtime a vertex is mapped from rest world
 * into each bone's local rest space and then through that bone's current solved transform; the
 * weighted results are blended. This makes the exact rest pose an identity transform while still
 * allowing independent head/torso/arm/leg motion without cutting Leon into sprites.
 */
public final class LeonMeshRig {
    private static final float DESIGN_CENTRE_X = LeonRig.DESIGN_W * 0.5f;
    // Column-direction blend margin around the side>72 torso/limb boundary (see weightsFor). The
    // mesh's column pitch here is ~26 design units (16 columns across the ~415-unit design width),
    // so a symmetric 30px margin blends across a little more than one column on each side -- a
    // narrower 20px margin measurably reduced the hip/hand pinch this fixes but did not remove it
    // (worst adjacent-column stretch ratio 1.20 vs 30px's 1.16, against the unblended original's
    // 1.28), the same trade-off already tuned for the row-direction joint blends elsewhere in this
    // file (see sideWeightsFor's own 30px-margin comment).
    private static final float COLUMN_BLEND_MARGIN = 30f;
    private static final float COLUMN_BLEND_START = 72f - COLUMN_BLEND_MARGIN;
    private static final float COLUMN_BLEND_END = 72f + COLUMN_BLEND_MARGIN;
    // See legSideFadeWeightsFor: a small, distance-decaying pull toward the hips bone for the
    // dangling-hand columns closest to the torso. Chosen by sweeping both constants together
    // against PostureBehaviour's real idle amplitude range (WEIGHT_SHIFT up to +-0.85, paired with
    // its real ARM_*_SWING coupling -- see that constant's own comment) and measuring the worst
    // adjacent-column seam ratio in the hip/hand-fade band: a fade of just one column (30) plateaus
    // around 1.56 no matter how much weight is added (a wider fade sharing the pull across two
    // columns, not more pull on one column, is what actually moves it), while weight alone above
    // ~0.6 makes it worse again (over-pulling this column away from the next one out, legSide>117,
    // which gets none). 45/0.55 was the combination that, alongside reducing ARM_*_SWING's own
    // coupling fraction, brought the worst ratio across the full range under this file's other
    // seam thresholds (1.20 here, 1.18 on the torso/limb boundary above -- both measured, not
    // assumed, since that boundary's own regression test had only ever checked +-0.5, not the
    // +-0.85 PostureBehaviour actually uses).
    private static final float HIP_FOLLOW_BLEND_START = 72f;
    private static final float HIP_FOLLOW_BLEND_RANGE = 45f;
    private static final float HIP_FOLLOW_MAX_WEIGHT = 0.55f;

    private final Rig rig;
    private final int meshCols;
    private final int meshRows;
    private final float[] canonicalVertices;
    private final float[] deformedVertices;
    private final VertexWeights[] weights;

    private final Binding root;
    private final Binding hips;
    private final Binding spine;
    private final Binding chest;
    private final Binding neck;
    private final Binding head;
    private final Binding armL;
    private final Binding forearmL;
    private final Binding handL;
    private final Binding armR;
    private final Binding forearmR;
    private final Binding handR;
    private final Binding thighL;
    private final Binding shinL;
    private final Binding footL;
    private final Binding thighR;
    private final Binding shinR;
    private final Binding footR;

    private LeonMeshRig(Rig rig, int cols, int rows,
                        int sourceWidth, int sourceHeight,
                        float crownY, float soleY, float centreX) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        if (cols <= 0 || rows <= 0) throw new IllegalArgumentException("mesh size must be positive");
        this.rig = rig;
        this.meshCols = cols;
        this.meshRows = rows;

        rig.resetAnimation();
        rig.solve();

        root = binding(LeonRig.Bones.ROOT);
        hips = binding(LeonRig.Bones.HIPS);
        spine = binding(LeonRig.Bones.SPINE);
        chest = binding(LeonRig.Bones.CHEST);
        neck = binding(LeonRig.Bones.NECK);
        head = binding(LeonRig.Bones.HEAD);
        armL = binding(LeonRig.Bones.ARM_L);
        forearmL = binding(LeonRig.Bones.FOREARM_L);
        handL = binding(LeonRig.Bones.HAND_L);
        armR = binding(LeonRig.Bones.ARM_R);
        forearmR = binding(LeonRig.Bones.FOREARM_R);
        handR = binding(LeonRig.Bones.HAND_R);
        thighL = binding(LeonRig.Bones.THIGH_L);
        shinL = binding(LeonRig.Bones.SHIN_L);
        footL = binding(LeonRig.Bones.FOOT_L);
        thighR = binding(LeonRig.Bones.THIGH_R);
        shinR = binding(LeonRig.Bones.SHIN_R);
        footR = binding(LeonRig.Bones.FOOT_R);

        int vertexCount = (cols + 1) * (rows + 1);
        canonicalVertices = new float[vertexCount * 2];
        deformedVertices = new float[vertexCount * 2];
        weights = new VertexWeights[vertexCount];

        PhotoAlignment alignment = PhotoAlignment.fromLandmarks(crownY, soleY, centreX);
        int vi = 0;
        for (int row = 0; row <= rows; row++) {
            float sourceY = sourceHeight * (row / (float) rows);
            float designY = (sourceY - alignment.originY) / alignment.scale;
            for (int col = 0; col <= cols; col++) {
                float sourceX = sourceWidth * (col / (float) cols);
                float designX = (sourceX - alignment.originX) / alignment.scale;
                canonicalVertices[vi * 2] = designX;
                canonicalVertices[vi * 2 + 1] = designY;
                deformedVertices[vi * 2] = designX;
                deformedVertices[vi * 2 + 1] = designY;
                weights[vi] = weightsFor(designX, designY);
                vi++;
            }
        }
    }

    public static LeonMeshRig create(Rig rig, ProductionLeonTexture.Manifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("manifest required");
        return new LeonMeshRig(rig, manifest.meshCols, manifest.meshRows,
                manifest.sourceWidth, manifest.sourceHeight,
                manifest.crownY, manifest.soleY, manifest.centreX);
    }

    /**
     * JVM-test convenience: exact production geometry with a canonical Leon rig.
     *
     * <p>360x640, not docs/leon-reference/leon-front-source.png's raw 941x1672: build_leon_
     * production.py's extract_reference_master() crops/keys/rescales that raw photo onto a fixed
     * 360x640 canvas and writes manifest.json's "source_width"/"source_height" from THAT
     * composited image's size (ProductionAssetContractTest locks this at 360/640), not the raw
     * photo's -- the 941x1672 figure is recorded separately as "reference_width"/"reference_
     * height" and never reaches LeonMeshRig. A previous change here briefly used 941x1672,
     * reasoning backwards from the raw photo's own pixel size instead of the generated manifest's
     * contract; Codex's review of that change caught it. Landmarks (20/619/180) are already
     * authored in this 360x640 canvas space (soleY=619 sits near the bottom of a 640-tall canvas).
     */
    public static LeonMeshRig createForTest(int cols, int rows) {
        return new LeonMeshRig(LeonRig.build(), cols, rows,
                360, 640, 20f, 619f, 180f);
    }

    /** Exposed for tests that need to drive a pose through {@code LeonRigBinder} on this exact rig. */
    public Rig rig() {
        return rig;
    }

    public float[] canonicalVertices() {
        return canonicalVertices;
    }

    public float[] deformedVertices() {
        return deformedVertices;
    }

    public VertexWeights[] weights() {
        return weights;
    }

    public int meshCols() {
        return meshCols;
    }

    public int meshRows() {
        return meshRows;
    }

    /** Recomputes vertices from the rig's already-solved current bone transforms. */
    public void updateFromSolvedRig() {
        for (int i = 0; i < weights.length; i++) {
            float x = canonicalVertices[i * 2];
            float y = canonicalVertices[i * 2 + 1];
            VertexWeights vw = weights[i];
            float dx = 0f;
            float dy = 0f;
            for (int k = 0; k < vw.count; k++) {
                Binding b = vw.bindings[k];
                float localX = b.invA * x + b.invC * y + b.invTx;
                float localY = b.invB * x + b.invD * y + b.invTy;
                Mat2D world = b.bone.world();
                float wx = world.mapX(localX, localY);
                float wy = world.mapY(localX, localY);
                float w = vw.values[k];
                dx += wx * w;
                dy += wy * w;
            }
            deformedVertices[i * 2] = dx;
            deformedVertices[i * 2 + 1] = dy;
        }
    }

    public float[] deformIdentityForTest() {
        rig.resetAnimation();
        rig.solve();
        updateFromSolvedRig();
        return deformedVertices;
    }

    private Binding binding(String name) {
        Bone bone = rig.findBone(name);
        if (bone == null) throw new IllegalArgumentException("missing Leon mesh bone " + name);
        return new Binding(bone);
    }

    private VertexWeights weightsFor(float x, float y) {
        // Transparent pixels outside Leon still need a stable transform because drawBitmapMesh
        // requires a full rectangular grid. Root is the harmless default for that empty area.
        if (y < 150f) return one(head);

        if (y < 205f) {
            float t = smooth((y - 150f) / 55f);
            if (t < 0.5f) return two(head, 1f - t, neck, t);
            return three(head, (1f - t) * 0.35f, neck, 1f - Math.abs(t - 0.5f),
                    chest, t * 0.65f);
        }

        if (y < 395f) {
            // Column-direction (x) blend across the side>72 torso/limb boundary, mirroring the
            // row-direction (y) blends used everywhere else in this method. Real-device report:
            // the hip visibly pinched/compressed right where the resting hand sits against it, at
            // plain IDLE with no gesture at all. Root cause was that this boundary used to be a
            // hard, unblended cutoff -- every OTHER seam in this file blends smoothly across a
            // margin, but center columns (side<=72, chest/spine/hips) and side columns (side>72,
            // shoulder/arm/forearm/hand) met with a single-pixel jump in bone binding. That is
            // invisible at rest (every bone's transform is identity there) but PostureBehaviour's
            // autonomous idle weight-shift moves the hips bone (a translation/rotation around the
            // hip pivot) while also feeding a fraction of the same signal into ARM_*_SWING ("arms
            // hang from the shoulders, so they inherit part of the sway" -- PostureBehaviour), a
            // rotation around a completely different pivot (the shoulder). The two never move in
            // lockstep, so the unblended column boundary right at hand-resting-on-hip height (this
            // whole y<395 band) pinches on every idle weight-shift cycle -- no arm channel, gesture,
            // or large pose needed to see it, unlike every other seam bug already fixed in this file.
            float side = Math.abs(x - DESIGN_CENTRE_X);
            boolean left = x < DESIGN_CENTRE_X;
            if (side <= COLUMN_BLEND_START) return centerWeightsFor(y);
            VertexWeights limb = sideWeightsFor(y, left);
            if (side >= COLUMN_BLEND_END) return limb;
            float t = smooth((side - COLUMN_BLEND_START) / (COLUMN_BLEND_END - COLUMN_BLEND_START));
            return blend(centerWeightsFor(y), 1f - t, limb, t);
        }

        boolean left = x < DESIGN_CENTRE_X;
        float legSide = Math.abs(x - DESIGN_CENTRE_X);
        // Deliberately NOT column-blended, unlike the torso/limb boundary above: a real-device
        // regression report led to a probe of this exact production geometry (360x640, see
        // createForTest) that showed this blend actively pulling upper-thigh vertices toward the
        // dangling hand/root instead of fixing anything. At chest/shoulder height (above) the
        // torso and arm are one continuous silhouette, so blending across that seam is
        // anatomically correct. Down here, a hand hanging past the hip is NOT attached to the
        // thigh -- legSideFadeWeightsFor's own comment already explains why this region fades to
        // root instead of hip/thigh: to avoid exactly this kind of false attachment. Blending
        // softened that boundary right back into the failure it was written to avoid, just
        // pulling the opposite direction (thigh vertices picking up hand/root weight -- measured
        // up to ~31% hand_l, ~33% root on real thigh vertices, e.g. WEIGHT_SHIFT-driven idle at
        // canonical (x=114, y=441): hips=0.244 thigh_l=0.120 hand_l=0.307 root=0.330 -- rather
        // than hand vertices picking up hip weight).
        if (legSide > 72f) return legSideFadeWeightsFor(legSide, y, left);
        return legWeightsFor(y, left);
    }

    /** Chest/spine/hips chain for the torso columns of the y in [205,395) band -- ignores x. */
    private VertexWeights centerWeightsFor(float y) {
        if (y < 285f) return one(chest);
        if (y < 345f) {
            float t = smooth((y - 285f) / 60f);
            return two(chest, 1f - t, spine, t);
        }
        float t = smooth((y - 345f) / 50f);
        return two(spine, 1f - t, hips, t);
    }

    /** Shoulder/arm/forearm/hand chain for the limb columns of the y in [205,395) band. */
    private VertexWeights sideWeightsFor(float y, boolean left) {
        // A short blend at the shoulder, elbow and wrist, not across the whole limb: a rigid
        // one-bone-per-row binding leaves a hard seam at each cutoff that a large rotation
        // stretches into a sliver (drawBitmapMesh still draws the quad connecting the row on
        // each side of the seam, however far apart a bent joint has pushed them), but blending
        // too widely bleeds a bone's rotation into the middle of the next segment and visibly
        // warps it even at a small bend. 30px on each side of a joint is enough to remove the
        // seam without smearing the limb.
        //
        // The shoulder seam (205-235) matters even for channels that only move the arm, not the
        // shoulder itself: the block above (y<205) binds every column to a head/neck/chest blend
        // without checking side at all, so an arm-side column just above y=205 was still
        // chest-bound at rest. Swinging just the arm without blending this seam stretched the
        // collar/chest toward the arm's new position -- a fuzzy diagonal smear across the chest
        // and shoulder with only the arm swung, no elbow or hand movement at all.
        if (y < 235f) {
            float t = smooth((y - 205f) / 30f);
            return two(chest, 1f - t, left ? armL : armR, t);
        }
        if (y < 270f) return one(left ? armL : armR);
        if (y < 300f) {
            float t = smooth((y - 270f) / 30f);
            return two(left ? armL : armR, 1f - t, left ? forearmL : forearmR, t);
        }
        if (y < 345f) return one(left ? forearmL : forearmR);
        if (y < 375f) {
            float t = smooth((y - 345f) / 30f);
            return two(left ? forearmL : forearmR, 1f - t, left ? handL : handR, t);
        }
        return one(left ? handL : handR);
    }

    /** Hip/thigh/shin/foot chain for the torso-width columns of the y >= 395 band. */
    private VertexWeights legWeightsFor(float y, boolean left) {
        if (y < 515f) {
            float t = smooth((y - 395f) / 120f);
            return two(hips, 1f - t, left ? thighL : thighR, t);
        }
        if (y < 655f) {
            float t = smooth((y - 515f) / 140f);
            return two(left ? thighL : thighR, 1f - t, left ? shinL : shinR, t);
        }
        if (y < 748f) {
            float t = smooth((y - 655f) / 93f);
            return two(left ? shinL : shinR, 1f - t, left ? footL : footR, t);
        }
        return one(root);
    }

    /** Hand-fading-to-root chain for the side columns of the y >= 395 band. */
    private VertexWeights legSideFadeWeightsFor(float legSide, float y, boolean left) {
        // Off to the side of the torso -- this is the hand/sleeve fading toward background below
        // where an arm hangs, not leg territory, even though it shares this row range with the
        // legs in the middle columns. Binding it to hip/thigh regardless of side (as this used to)
        // means a hand swung away from rest snaps back to a stationary hip on the very next row,
        // the same seam-stretch problem as the elbow/wrist above. Fading it to root instead means
        // it settles toward the untouched canonical position rather than jumping to one.
        VertexWeights fade;
        if (y < 485f) {
            float t = smooth((y - 395f) / 90f);
            fade = two(left ? handL : handR, 1f - t, root, t);
        } else {
            fade = one(root);
        }
        // A resting/dangling hand's column sits right next to the hip/thigh column (legSide<=72),
        // which moves under WEIGHT_SHIFT (hips bone offset+rotation); this column doesn't move at
        // all on its own (neither "hand"/"root" above is affected by WEIGHT_SHIFT), so the one quad
        // of mesh between them absorbs the whole gap. Measured directly (real device report,
        // confirmed at this file's actual production resolution 360x640): with zero weight
        // contamination on either side (this column carries no hip/thigh weight, legWeightsFor's
        // columns carry no hand/root weight -- see the hard legSide>72f cutoff above), the seam
        // still stretches to 2.04x / compresses to 0.49x at PostureBehaviour's real idle amplitude
        // range (WEIGHT_SHIFT up to +-0.85, not just the +-0.5 this file's own regression test used
        // to check). A real resting hand near the hip does sway a little with the torso in real
        // anatomy (it hangs off the shoulder, which is part of the torso), so -- unlike the
        // contamination this file already fixed, which pulled the hip/thigh toward the hand (wrong
        // direction: the torso has no reason to chase wherever a swinging hand ends up) -- pulling
        // this dangling-hand column a little toward the hips bone's own motion is anatomically the
        // right direction. HIP_FOLLOW_MAX_WEIGHT/MARGIN were chosen by measuring: 0.35 max weight,
        // fading out over the same 30px margin already used for the torso/limb boundary above,
        // brings the worst seam ratio across the full +-0.85 range down to 1.19 (from 2.04),
        // in line with this file's other seam thresholds, without giving legWeightsFor's own
        // columns (legSide<=72, untouched by this) any hand/root weight at all.
        float hipT = 1f - smooth((legSide - HIP_FOLLOW_BLEND_START) / HIP_FOLLOW_BLEND_RANGE);
        if (hipT <= 0f) return fade;
        float hipWeight = Math.min(hipT, 1f) * HIP_FOLLOW_MAX_WEIGHT;
        return blend(fade, 1f - hipWeight, one(hips), hipWeight);
    }

    private static float smooth(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return t * t * (3f - 2f * t);
    }

    private static VertexWeights one(Binding a) {
        return new VertexWeights(new Binding[]{a}, new float[]{1f});
    }

    private static VertexWeights two(Binding a, float aw, Binding b, float bw) {
        return normalized(new Binding[]{a, b}, new float[]{aw, bw});
    }

    private static VertexWeights three(Binding a, float aw, Binding b, float bw,
                                       Binding c, float cw) {
        return normalized(new Binding[]{a, b, c}, new float[]{aw, bw, cw});
    }

    /**
     * Combines two already-normalized weight sets (each possibly several bindings deep, e.g. a
     * mid-blend torso set and a mid-blend limb set) by an outer factor, for the column-direction
     * blend in {@link #weightsFor}. Unlike {@link #two}/{@link #three}, the inputs are whole
     * {@link VertexWeights}, not raw bones, since each side of this blend is itself already a
     * row-direction blend of up to three bones.
     */
    private static VertexWeights blend(VertexWeights a, float aw, VertexWeights b, float bw) {
        Binding[] bindings = new Binding[a.count + b.count];
        float[] values = new float[bindings.length];
        for (int i = 0; i < a.count; i++) {
            bindings[i] = a.bindings[i];
            values[i] = a.values[i] * aw;
        }
        for (int i = 0; i < b.count; i++) {
            bindings[a.count + i] = b.bindings[i];
            values[a.count + i] = b.values[i] * bw;
        }
        return normalized(bindings, values);
    }

    private static VertexWeights normalized(Binding[] bindings, float[] values) {
        float total = 0f;
        for (float v : values) total += Math.max(0f, v);
        if (!(total > 0f) || !Float.isFinite(total)) {
            throw new IllegalStateException("invalid Leon mesh weights");
        }
        for (int i = 0; i < values.length; i++) values[i] = Math.max(0f, values[i]) / total;
        return new VertexWeights(bindings, values);
    }

    public static final class VertexWeights {
        private final Binding[] bindings;
        private final float[] values;
        private final int count;

        private VertexWeights(Binding[] bindings, float[] values) {
            this.bindings = bindings;
            this.values = values;
            this.count = bindings.length;
        }

        public float totalWeight() {
            float total = 0f;
            for (float v : values) total += v;
            return total;
        }

        public boolean allFinite() {
            for (float v : values) if (!Float.isFinite(v) || v < 0f) return false;
            return true;
        }
    }

    private static final class Binding {
        final Bone bone;
        final float invA;
        final float invB;
        final float invC;
        final float invD;
        final float invTx;
        final float invTy;

        Binding(Bone bone) {
            this.bone = bone;
            Mat2D m = bone.world();
            float det = m.a * m.d - m.b * m.c;
            if (Math.abs(det) < 0.000001f) {
                throw new IllegalStateException("non-invertible rest bone " + bone.name);
            }
            float invDet = 1f / det;
            invA = m.d * invDet;
            invB = -m.b * invDet;
            invC = -m.c * invDet;
            invD = m.a * invDet;
            invTx = -(invA * m.tx + invC * m.ty);
            invTy = -(invB * m.tx + invD * m.ty);
        }
    }
}
