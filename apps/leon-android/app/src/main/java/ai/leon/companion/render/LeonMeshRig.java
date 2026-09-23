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

    /** JVM-test convenience: exact production geometry with a canonical Leon rig. */
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
            float side = Math.abs(x - DESIGN_CENTRE_X);
            if (side > 72f) {
                boolean left = x < DESIGN_CENTRE_X;
                // A short blend right at the elbow and wrist, not across the whole limb: a rigid
                // one-bone-per-row binding leaves a hard seam at each cutoff that a large rotation
                // stretches into a sliver (drawBitmapMesh still draws the quad connecting the row on
                // each side of the seam, however far apart a bent joint has pushed them), but blending
                // too widely bleeds the forearm's rotation into the middle of the hand and visibly
                // bends the wrist even at a small idle elbow bend. 30px on each side of a joint is
                // enough to remove the seam without smearing the limb.
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
            if (y < 285f) return one(chest);
            if (y < 345f) {
                float t = smooth((y - 285f) / 60f);
                return two(chest, 1f - t, spine, t);
            }
            float t = smooth((y - 345f) / 50f);
            return two(spine, 1f - t, hips, t);
        }

        boolean left = x < DESIGN_CENTRE_X;
        float legSide = Math.abs(x - DESIGN_CENTRE_X);
        if (legSide > 72f) {
            // Off to the side of the torso -- this is the hand/sleeve fading toward background below
            // where an arm hangs, not leg territory, even though it shares this row range with the
            // legs in the middle columns. Binding it to hip/thigh regardless of side (as this used to)
            // means a hand swung away from rest snaps back to a stationary hip on the very next row,
            // the same seam-stretch problem as the elbow/wrist above. Fading it to root instead means
            // it settles toward the untouched canonical position rather than jumping to one.
            if (y < 485f) {
                float t = smooth((y - 395f) / 90f);
                return two(left ? handL : handR, 1f - t, root, t);
            }
            return one(root);
        }
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
