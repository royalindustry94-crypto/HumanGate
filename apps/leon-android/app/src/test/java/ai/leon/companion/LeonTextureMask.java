package ai.leon.companion;

import ai.leon.companion.asset.PhotoAlignment;
import ai.leon.companion.render.LeonLayerMask;
import ai.leon.companion.render.LeonMeshRig;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

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

    private final int width;
    private final int height;
    /** Alpha channel only, row-major. */
    private final byte[] alpha;

    private final PhotoAlignment alignment = PhotoAlignment.fromLandmarks(20f, 619f, 180f);
    /** The production layer split, computed by the same code the renderer runs. */
    final boolean[] arm;

    private LeonTextureMask(int width, int height, byte[] alpha) {
        this.width = width;
        this.height = height;
        this.alpha = alpha;
        int[] a = new int[alpha.length];
        for (int i = 0; i < a.length; i++) a[i] = alpha[i] & 0xff;
        this.arm = LeonLayerMask.armTexels(a, width, height, alignment);
    }

    /** Texel under design point (x, y), or -1 off the texture. */
    int texelAt(float x, float y) {
        int px = (int) Math.floor(ORIGIN_X + x * SCALE);
        int py = (int) Math.floor(ORIGIN_Y + y * SCALE);
        if (px < 0 || py < 0 || px >= width || py >= height) return -1;
        return py * width + px;
    }

    boolean anyAlpha(int t) {
        return alpha[t] != 0;
    }

    boolean layerDraws(LeonMeshRig.Layer layer, float x, float y) {
        int t = texelAt(x, y);
        return t >= 0 && LeonLayerMask.covers(layer, arm, t, width, alignment);
    }

    static LeonTextureMask load() throws IOException {
        File[] candidates = {
                new File("app/build/generated/leonAssets/leon/production/leon.png"),
                new File("build/generated/leonAssets/leon/production/leon.png"),
                new File("apps/leon-android/app/build/generated/leonAssets/leon/production/leon.png")
        };
        for (File file : candidates) {
            if (file.isFile()) return decodeRgbaPng(file);
        }
        throw new AssertionError("Generated Leon texture not found; run generateLeonProduction");
    }

    /** True where the texture pixel under design point (x, y) is visibly part of Leon. */
    boolean opaqueAtDesign(float x, float y) {
        int px = Math.round(ORIGIN_X + x * SCALE);
        int py = Math.round(ORIGIN_Y + y * SCALE);
        if (px < 0 || py < 0 || px >= width || py >= height) return false;
        return (alpha[py * width + px] & 0xff) >= 128;
    }

    /**
     * Fraction of samples along the rest-pose segment between two canonical vertices that are
     * visible pixels drawn by {@code layer}.
     */
    float opacityAlong(float[] canon, int i0, int i1, LeonMeshRig.Layer layer) {
        int on = 0;
        for (int s = 0; s <= 8; s++) {
            float t = s / 8f;
            float x = canon[i0 * 2] + t * (canon[i1 * 2] - canon[i0 * 2]);
            float y = canon[i0 * 2 + 1] + t * (canon[i1 * 2 + 1] - canon[i0 * 2 + 1]);
            if (opaqueAtDesign(x, y) && layerDraws(layer, x, y)) on++;
        }
        return on / 9f;
    }

    /**
     * Worst stretch-or-compression ratio over every mesh edge (horizontal and vertical) of this
     * layer that runs mostly through pixels the layer actually draws. No edge is exempt: an earlier
     * version skipped edges crossing the body/arm boundary, which hid hands tearing across the hip.
     */
    float worstOpaqueEdgeRatio(LeonMeshRig mesh, float yLo, float yHi) {
        float[] canon = mesh.canonicalVertices();
        float[] deformed = mesh.deformedVertices();
        int cols = mesh.meshCols();
        int rows = mesh.meshRows();
        float worst = 1f;
        for (int row = 0; row <= rows; row++) {
            for (int col = 0; col <= cols; col++) {
                int i0 = row * (cols + 1) + col;
                for (int dir = 0; dir < 2; dir++) {
                    if (dir == 0 && col == cols) continue;
                    if (dir == 1 && row == rows) continue;
                    int i1 = dir == 0 ? i0 + 1 : i0 + cols + 1;
                    float y = Math.min(canon[i0 * 2 + 1], canon[i1 * 2 + 1]);
                    if (y < yLo || y > yHi) continue;
                    if (opacityAlong(canon, i0, i1, mesh.layer()) < 0.5f) continue;
                    float rest = distance(canon, i0, i1);
                    float now = distance(deformed, i0, i1);
                    float ratio = now / rest;
                    worst = Math.max(worst, Math.max(ratio, 1f / ratio));
                }
            }
        }
        return worst;
    }

    private static float distance(float[] v, int i0, int i1) {
        return (float) Math.hypot(v[i1 * 2] - v[i0 * 2], v[i1 * 2 + 1] - v[i0 * 2 + 1]);
    }

    /**
     * Minimal decoder for the one PNG shape build_leon_production.py writes (8-bit RGBA,
     * non-interlaced). Unit tests compile against android.jar, which has no javax.imageio.
     */
    private static LeonTextureMask decodeRgbaPng(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] signature = new byte[8];
            in.readFully(signature);
            int w = 0;
            int h = 0;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            while (true) {
                int length = in.readInt();
                byte[] type = new byte[4];
                in.readFully(type);
                byte[] data = new byte[length];
                in.readFully(data);
                in.readInt(); // CRC
                String chunk = new String(type, "US-ASCII");
                if (chunk.equals("IHDR")) {
                    w = ((data[0] & 0xff) << 24) | ((data[1] & 0xff) << 16) | ((data[2] & 0xff) << 8) | (data[3] & 0xff);
                    h = ((data[4] & 0xff) << 24) | ((data[5] & 0xff) << 16) | ((data[6] & 0xff) << 8) | (data[7] & 0xff);
                    if (data[8] != 8 || data[9] != 6 || data[12] != 0) {
                        throw new AssertionError("expected 8-bit non-interlaced RGBA Leon texture");
                    }
                } else if (chunk.equals("IDAT")) {
                    idat.write(data);
                } else if (chunk.equals("IEND")) {
                    break;
                }
            }
            int stride = w * 4;
            byte[] raw = new byte[(stride + 1) * h];
            Inflater inflater = new Inflater();
            inflater.setInput(idat.toByteArray());
            int read = 0;
            while (read < raw.length && !inflater.finished()) {
                read += inflater.inflate(raw, read, raw.length - read);
            }
            inflater.end();
            byte[] alpha = new byte[w * h];
            byte[] previous = new byte[stride];
            byte[] current = new byte[stride];
            for (int y = 0; y < h; y++) {
                int filter = raw[y * (stride + 1)] & 0xff;
                System.arraycopy(raw, y * (stride + 1) + 1, current, 0, stride);
                for (int i = 0; i < stride; i++) {
                    int a = i >= 4 ? current[i - 4] & 0xff : 0;
                    int b = previous[i] & 0xff;
                    int c = i >= 4 ? previous[i - 4] & 0xff : 0;
                    int x = current[i] & 0xff;
                    switch (filter) {
                        case 1: x += a; break;
                        case 2: x += b; break;
                        case 3: x += (a + b) >> 1; break;
                        case 4: x += paeth(a, b, c); break;
                        default: break;
                    }
                    current[i] = (byte) x;
                }
                for (int px = 0; px < w; px++) alpha[y * w + px] = current[px * 4 + 3];
                byte[] swap = previous;
                previous = current;
                current = swap;
            }
            return new LeonTextureMask(w, h, alpha);
        } catch (DataFormatException e) {
            throw new IOException("corrupt Leon texture", e);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }
}
