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
    /**
     * Production grid (tools/build_leon_production.py writes it into manifest.json). One cell is
     * ~8.6 x 8.8 design units, fine enough that a vertex column lands in, or within a few units of,
     * the transparent gap between the hips and the hanging hands. At the old 16x28 (25.8 units a
     * cell) the outer hip and the hand shared one cell, so no binding could keep both intact.
     */
    public static final int PRODUCTION_COLS = 48;
    public static final int PRODUCTION_ROWS = 84;

    private static final float DESIGN_CENTRE_X = LeonRig.DESIGN_W * 0.5f;

    /*
     * Skin is bound by anatomy, not by a fixed x threshold. BODY_EDGE_* is, per design row, the
     * half-width (distance from the centre line) of the boundary between Leon's body (torso, hips,
     * legs) and his hanging arms/hands. From y=268 down it sits in the middle of the transparent gap
     * measured on the production texture's alpha. LeonMeshRigTest#bodyEdgeLiesInTheMeasuredGap
     * re-measures it against the generated texture, so replacement art that moves the gap fails a
     * test instead of silently mis-binding.
     *
     * The rule this replaces bound everything beyond |x - centre| > 72 below y=395 to hand->root.
     * The pelvis and thighs reach +-80..93 there, so the outer strip of each hip followed the hand
     * and the static root while the inner strip followed HIPS. Idle weight shift then squeezed one
     * hip to ~0.35x its width (measured at plain IDLE). It also blended arm weight into the torso
     * from the centre line outwards, putting ~29% forearm/hand weight inside the belly.
     */
    // Gap midpoints measured every 3 design rows (both sides, the narrower gap wins), plus the
    // rows where the thumb nearly touches the hip (y 427-432, gap ~3 units).
    private static final float[] BODY_EDGE_Y = {
            268f, 271f, 274f, 277f, 280f, 283f, 286f, 289f, 292f, 295f, 298f, 301f, 304f, 307f,
            310f, 313f, 316f, 319f, 322f, 325f, 328f, 331f, 334f, 337f, 340f, 343f, 346f, 349f,
            352f, 355f, 358f, 361f, 364f, 367f, 370f, 373f, 376f, 379f, 382f, 385f, 388f, 391f,
            394f, 397f, 400f, 403f, 406f, 409f, 412f, 415f, 418f, 421f, 424f, 427f, 430f, 431f,
            432f, 433f, 436f, 439f, 442f, 445f, 446f};
    private static final float[] BODY_EDGE_HALF_WIDTH = {
            70f, 70.75f, 71.25f, 70.25f, 69.5f, 70.75f, 72.5f, 74f, 73.5f, 73.5f, 74.25f, 75.5f,
            76f, 77f, 76.5f, 77f, 77f, 77.5f, 78.25f, 80f, 80.5f, 81.5f, 81.5f, 81.75f, 83.5f,
            83.5f, 83.5f, 82.25f, 83.5f, 84.5f, 85f, 85.75f, 87f, 88.5f, 88.5f, 89.75f, 90.75f,
            90.25f, 91f, 92.5f, 92f, 91f, 89f, 88.5f, 88f, 86.75f, 86.75f, 86.75f, 87f, 86.25f,
            86.5f, 86.5f, 85.75f, 84.5f, 84.5f, 85f, 85f, 93.25f, 93f, 92f, 91f, 90.5f, 90.25f};
    /**
     * Above the armpit the sleeve and the torso are one continuous piece of cloth, so the seam is
     * blended over a wide margin. Below it a transparent gap separates body from arm, and the blend
     * is kept narrower than that gap so neither side drags the other's pixels.
     */
    private static final float SHOULDER_BLEND_HALF_WIDTH = 16f;
    private static final float GAP_BLEND_HALF_WIDTH = 2.5f;
    private static final float ARMPIT_Y = 262f;
    private static final float GAP_OPEN_Y = 274f;
    /**
     * Leon is drawn as two meshes over the same texture, body first and arms on top, and no
     * triangle connects them. Below the armpit the hanging arms are separated from the torso and
     * hips by a transparent gap only 3-12 design units wide, narrower than a mesh cell. In a single
     * mesh the cells spanning that gap tie arm vertices to hip vertices, so any arm motion (even
     * IDLE's 12% elbow bend swings the hand ~20 units inwards) either dragged and squeezed the
     * outer hip and thigh or, bound the other way, tore the hand into slivers across the hip.
     * With two layers each visible pixel follows only its own bones: hips and legs never move with
     * a hand, and a hand swinging over the hip simply passes in front of it.
     *
     * Above SHARED_SKIN_END_Y both layers use the same blended skinning (the sleeve is one piece of
     * cloth with the shoulder), so the cut through the sleeve at ARM_LAYER_TOP_Y is seamless. The
     * arm layer starts ARM_LAYER_OVERLAP units above the cut so filtering at the cut never leaves a
     * half-transparent line; both layers draw those pixels at identical positions.
     */
    public enum Layer { BODY, ARMS }

    public static final float ARM_LAYER_TOP_Y = 283f;
    public static final float ARM_LAYER_OVERLAP = 2f;
    /** One mesh cell below the cut, so no cell holding cut-line pixels changes binding. */
    private static final float SHARED_SKIN_END_Y = 292f;

    private final Rig rig;
    private final Layer layer;
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

    private LeonMeshRig(Rig rig, Layer layer, int cols, int rows,
                        int sourceWidth, int sourceHeight,
                        float crownY, float soleY, float centreX) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        if (layer == null) throw new IllegalArgumentException("layer required");
        if (cols <= 0 || rows <= 0) throw new IllegalArgumentException("mesh size must be positive");
        this.rig = rig;
        this.layer = layer;
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

    public static LeonMeshRig create(Rig rig, ProductionLeonTexture.Manifest manifest, Layer layer) {
        if (manifest == null) throw new IllegalArgumentException("manifest required");
        return new LeonMeshRig(rig, layer, manifest.meshCols, manifest.meshRows,
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
        return createForTest(LeonRig.build(), cols, rows, Layer.BODY);
    }

    /** Same production geometry for one layer; pass the same rig to both layers to pose them together. */
    public static LeonMeshRig createForTest(Rig rig, int cols, int rows, Layer layer) {
        return new LeonMeshRig(rig, layer, cols, rows, 360, 640, 20f, 619f, 180f);
    }

    public Layer layer() {
        return layer;
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
        // drawBitmapMesh needs a full rectangular grid, so transparent vertices outside Leon are
        // still bound to the nearest body chain.
        if (y < 150f) return one(head);

        if (y < 205f) {
            float t = smooth((y - 150f) / 55f);
            if (t < 0.5f) return two(head, 1f - t, neck, t);
            return three(head, (1f - t) * 0.35f, neck, 1f - Math.abs(t - 0.5f),
                    chest, t * 0.65f);
        }

        float side = Math.abs(x - DESIGN_CENTRE_X);
        boolean left = x < DESIGN_CENTRE_X;
        if (y >= SHARED_SKIN_END_Y) {
            // Below the armpit each layer follows only its own chain, wherever the vertex is.
            if (layer == Layer.ARMS) return sideWeightsFor(y, left);
            return y < 395f ? centerWeightsFor(y) : legWeightsFor(y, left);
        }
        float edge = bodyEdgeHalfWidth(y);
        float half = y <= ARMPIT_Y ? SHOULDER_BLEND_HALF_WIDTH
                : y >= GAP_OPEN_Y ? GAP_BLEND_HALF_WIDTH
                : SHOULDER_BLEND_HALF_WIDTH + (GAP_BLEND_HALF_WIDTH - SHOULDER_BLEND_HALF_WIDTH)
                        * ((y - ARMPIT_Y) / (GAP_OPEN_Y - ARMPIT_Y));
        if (side <= edge - half) return centerWeightsFor(y);
        VertexWeights limb = sideWeightsFor(y, left);
        if (side >= edge + half) return limb;
        float t = smooth((side - (edge - half)) / (2f * half));
        return blend(centerWeightsFor(y), 1f - t, limb, t);
    }

    /** Body/arm boundary half-width at design row y (see BODY_EDGE_*). */
    public static float bodyEdgeHalfWidth(float y) {
        if (y <= BODY_EDGE_Y[0]) return BODY_EDGE_HALF_WIDTH[0];
        int last = BODY_EDGE_Y.length - 1;
        if (y >= BODY_EDGE_Y[last]) return BODY_EDGE_HALF_WIDTH[last];
        int i = 1;
        while (BODY_EDGE_Y[i] < y) i++;
        float t = (y - BODY_EDGE_Y[i - 1]) / (BODY_EDGE_Y[i] - BODY_EDGE_Y[i - 1]);
        return BODY_EDGE_HALF_WIDTH[i - 1] + t * (BODY_EDGE_HALF_WIDTH[i] - BODY_EDGE_HALF_WIDTH[i - 1]);
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
        if (y < LeonRig.ELBOW_Y - 15f) return one(left ? armL : armR);
        if (y < LeonRig.ELBOW_Y + 15f) {
            float t = smooth((y - (LeonRig.ELBOW_Y - 15f)) / 30f);
            return two(left ? armL : armR, 1f - t, left ? forearmL : forearmR, t);
        }
        if (y < LeonRig.WRIST_Y - 15f) return one(left ? forearmL : forearmR);
        if (y < LeonRig.WRIST_Y + 15f) {
            float t = smooth((y - (LeonRig.WRIST_Y - 15f)) / 30f);
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
