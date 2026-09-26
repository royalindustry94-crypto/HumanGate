package ai.leon.companion;

import ai.leon.companion.render.LeonMeshRig;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * The generated production texture's alpha, addressed the way drawBitmapMesh samples it, so mesh
 * tests can tell a visible Leon pixel from the transparent space around him.
 *
 * <p>Mesh seams are only a defect where they cross opaque pixels. A test that also counts vertices
 * in the transparent gap between the hips and the hands would punish the stretch that gap exists to
 * absorb, and would miss a hip being squeezed by bones that are not the hip's.
 */
final class LeonTextureMask {
    /** Same landmarks as LeonMeshRig.createForTest (manifest.json of the production build). */
    private static final float SCALE = (619f - 20f) / (732f - 44f);
    private static final float ORIGIN_X = 180f - 192f * SCALE;
    private static final float ORIGIN_Y = 20f - 44f * SCALE;

    private final BufferedImage texture;

    private LeonTextureMask(BufferedImage texture) {
        this.texture = texture;
    }

    static LeonTextureMask load() throws IOException {
        File[] candidates = {
                new File("app/build/generated/leonAssets/leon/production/leon.png"),
                new File("build/generated/leonAssets/leon/production/leon.png"),
                new File("apps/leon-android/app/build/generated/leonAssets/leon/production/leon.png")
        };
        for (File file : candidates) {
            if (file.isFile()) return new LeonTextureMask(ImageIO.read(file));
        }
        throw new AssertionError("Generated Leon texture not found; run generateLeonProduction");
    }

    /** True where the texture pixel under design point (x, y) is visibly part of Leon. */
    boolean opaqueAtDesign(float x, float y) {
        int px = Math.round(ORIGIN_X + x * SCALE);
        int py = Math.round(ORIGIN_Y + y * SCALE);
        if (px < 0 || py < 0 || px >= texture.getWidth() || py >= texture.getHeight()) return false;
        return (texture.getRGB(px, py) >>> 24) >= 128;
    }

    /** Fraction of opaque samples along the rest-pose segment between two canonical vertices. */
    float opacityAlong(float[] canon, int i0, int i1) {
        int on = 0;
        for (int s = 0; s <= 8; s++) {
            float t = s / 8f;
            float x = canon[i0 * 2] + t * (canon[i1 * 2] - canon[i0 * 2]);
            float y = canon[i0 * 2 + 1] + t * (canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
            if (opaqueAtDesign(x, y)) on++;
        }
        return on / 9f;
    }

    /**
     * Worst stretch-or-compression ratio over every mesh edge (horizontal and vertical) that runs
     * mostly through visible pixels, skipping only edges that straddle the body/arm boundary: those
     * connect two independently moving parts, so their length change is the gap between the parts
     * opening or closing, not a deformation of either.
     */
    float worstOpaqueEdgeRatio(LeonMeshRig mesh, float yLo, float yHi) {
        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        float worst = 1f;
        int checked = 0;
        for (int row = 0; row <= rows; row++) {
            for (int col = 0; col <= cols; col++) {
                int i0 = row * (cols + 1) + col;
                for (int dir = 0; dir < 2; dir++) {
                    if (dir == 0 && col == cols) continue;
                    if (dir == 1 && row == rows) continue;
                    int i1 = dir == 0 ? i0 + 1 : i0 + cols + 1;
                    float y = Math.min(canon[i0 * 2 + 1], canon[i1 * 2 + 1]);
                    if (y < yLo || y > yHi) continue;
                    if (straddlesBodyEdge(canon, i0, i1)) continue;
                    if (opacityAlong(canon, i0, i1) < 0.5f) continue;
                    float rest = distance(canon, i0, i1);
                    float now = distance(deformed, i0, i1);
                    float ratio = now / rest;
                    worst = Math.max(worst, Math.max(ratio, 1f / ratio));
                    checked++;
                }
            }
        }
        if (checked < 20) throw new AssertionError("too few opaque edges in [" + yLo + "," + yHi + "]");
        return worst;
    }

    static boolean straddlesBodyEdge(float[] canon, int i0, int i1) {
        float y0 = canon[i0 * 2 + 1];
        float y1 = canon[i1 * 2 + 1];
        if (Math.max(y0, y1) < 262f || Math.min(y0, y1) > 446f) return false;
        boolean in0 = Math.abs(canon[i0 * 2] - 192f) <= LeonMeshRig.bodyEdgeHalfWidth(y0);
        boolean in1 = Math.abs(canon[i1 * 2] - 192f) <= LeonMeshRig.bodyEdgeHalfWidth(y1);
        return in0 != in1;
    }

    private static float distance(float[] v, int i0, int i1) {
        return (float) Math.hypot(v[i1 * 2] - v[i0 * 2], v[i1 * 2 + 1] - v[i0 * 2 + 1]);
    }
}
