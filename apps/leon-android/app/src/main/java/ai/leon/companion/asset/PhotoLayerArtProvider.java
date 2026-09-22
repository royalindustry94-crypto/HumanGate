package ai.leon.companion.asset;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;

import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns ONE image of Leon — a photograph or a 3D render, on transparency — into the rig's layers.
 *
 * <p>This is the path to a character that looks like the reference art rather than like a drawing.
 * Instead of an artist exporting forty separate files, the source is a single front-facing figure
 * plus three landmarks ({@link PhotoAlignment}); each layer is then cropped straight out of it at
 * the rectangle the skeleton already defines for that body part. Because the crops come from one
 * image they stay in register, and because they are separate bitmaps on separate bones the head
 * still turns, the chest still breathes and the shoulders still move independently.
 *
 * <p>Layers a single front photo cannot supply — the mouth shapes, and the eyes behind opaque
 * sunglasses — are left to the fallback provider, which draws them colour-matched. Everything the
 * photo does cover is the photo.
 *
 * <p>Source file: {@code leon-front.png} in {@code assets/leon/} or in {@code files/leon-art/},
 * alongside {@code leon-front.txt} holding the three landmark numbers.
 */
public final class PhotoLayerArtProvider implements LeonArtProvider {
    private static final String TAG = "LeonArt";
    public static final String SOURCE_NAME = "leon-front";

    /**
     * Art keys a single front-facing source cannot provide. The mouth must open, and Leon's
     * sunglasses are opaque in the reference art, so the eye layers behind them have nothing to
     * crop from.
     */
    private static final java.util.Set<String> NOT_IN_PHOTO = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    LeonRig.Art.AURA,
                    LeonRig.Art.EYE_WHITE, LeonRig.Art.IRIS, LeonRig.Art.EYELID,
                    LeonRig.Art.MOUTH_CLOSED, LeonRig.Art.MOUTH_NEUTRAL_OPEN,
                    LeonRig.Art.MOUTH_A, LeonRig.Art.MOUTH_E, LeonRig.Art.MOUTH_O,
                    LeonRig.Art.MOUTH_U, LeonRig.Art.MOUTH_MBP, LeonRig.Art.MOUTH_FV,
                    LeonRig.Art.MOUTH_SMILE, LeonRig.Art.MOUTH_FROWN));

    /**
     * Rectangular slices from one front render overlap heavily. These details already exist inside
     * the larger head/torso photo slices; drawing them again produces double eyes, duplicated
     * jewellery and ghosted tattoos as the bones move. In photo mode these logical layers resolve
     * to transparent bitmaps instead of falling back to the cartoon artwork.
     */
    private static final java.util.Set<String> EMBEDDED_IN_BASE = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    LeonRig.Art.HOODIE_POCKET,
                    LeonRig.Art.NECK_SKIN, LeonRig.Art.NECK_TATTOO,
                    LeonRig.Art.CHEST_TATTOO, LeonRig.Art.NECKLACE,
                    LeonRig.Art.HEAD_TATTOO_WRAP,
                    LeonRig.Art.NOSE, LeonRig.Art.SUNGLASSES));

    /**
     * Only these photo rectangles are cut out of the continuity backfill. Every other visible
     * source pixel stays in the backfill, which closes the gaps between rectangles without making
     * the whole character a single animated bitmap.
     */
    private static final java.util.Set<String> ANIMATED_PHOTO_KEYS = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    LeonRig.Art.HOOD_DOWN,
                    LeonRig.Art.PANT_LEG_L, LeonRig.Art.PANT_LEG_R,
                    LeonRig.Art.PANT_SHIN_L, LeonRig.Art.PANT_SHIN_R,
                    LeonRig.Art.SNEAKER_L, LeonRig.Art.SNEAKER_R,
                    LeonRig.Art.SLEEVE_UPPER_L, LeonRig.Art.SLEEVE_UPPER_R,
                    LeonRig.Art.FOREARM_TATTOO_L, LeonRig.Art.FOREARM_TATTOO_R,
                    LeonRig.Art.HAND_L, LeonRig.Art.HAND_R,
                    LeonRig.Art.HOODIE_BODY,
                    LeonRig.Art.HEAD_BALD,
                    LeonRig.Art.EAR_L, LeonRig.Art.EAR_R, LeonRig.Art.EARRING));

    private final Bitmap source;
    private final PhotoAlignment alignment;
    private final Map<String, RectF> designRects = new HashMap<>();
    private final Map<String, Bitmap> cache = new HashMap<>();
    private boolean released;

    private PhotoLayerArtProvider(Bitmap source, PhotoAlignment alignment, Rig rig) {
        this.source = source;
        this.alignment = alignment;
        for (RigPart part : rig.parts()) {
            if (designRects.containsKey(part.artKey)) continue;
            // The layer's own rectangle in design space, taken from its pivot and size.
            float left = part.offsetX - part.pivotX * part.width;
            float top = part.offsetY - part.pivotY * part.height;
            // Bone rest position, walked up the chain, puts that rectangle on the figure.
            float[] bone = restPositionOf(part);
            designRects.put(part.artKey,
                    new RectF(bone[0] + left, bone[1] + top,
                            bone[0] + left + part.width, bone[1] + top + part.height));
        }
    }

    private static float[] restPositionOf(RigPart part) {
        float x = 0f, y = 0f;
        ai.leon.companion.rig.Bone b = part.bone;
        while (b != null) {
            x += b.restX;
            y += b.restY;
            b = b.parent;
        }
        return new float[]{x, y};
    }

    /**
     * @return a provider, or null when no source image is present. Returning null is what lets the
     *         repository say plainly that Leon is drawn rather than photographed.
     */
    public static PhotoLayerArtProvider createIfPresent(android.content.Context context, Rig rig) {
        if (context == null || rig == null) return null;
        Bitmap bitmap = null;
        PhotoAlignment alignment = null;

        File override = new File(context.getFilesDir(), AssetDirArtProvider.OVERRIDE_DIR);
        for (String extension : new String[]{".png", ".webp"}) {
            File file = new File(override, SOURCE_NAME + extension);
            if (!file.isFile()) continue;
            try (InputStream in = new FileInputStream(file)) {
                bitmap = BitmapFactory.decodeStream(in);
            } catch (IOException e) {
                Log.w(TAG, "Could not read " + file, e);
            }
            if (bitmap != null) {
                alignment = readLandmarks(new File(override, SOURCE_NAME + ".txt"), bitmap);
                break;
            }
        }
        if (bitmap == null) {
            for (String extension : new String[]{".png", ".webp"}) {
                try (InputStream in = context.getAssets()
                        .open(AssetDirArtProvider.ASSET_DIR + "/" + SOURCE_NAME + extension)) {
                    bitmap = BitmapFactory.decodeStream(in);
                } catch (IOException ignored) {
                    // Not present in this variant; try the next.
                }
                if (bitmap != null) {
                    alignment = readAssetLandmarks(context, bitmap);
                    break;
                }
            }
        }
        if (bitmap == null) return null;
        if (alignment == null) alignment = defaultAlignment(bitmap);
        Log.i(TAG, "Leon photo source " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " " + alignment);
        return new PhotoLayerArtProvider(bitmap, alignment, rig);
    }

    /** Landmarks file: three whitespace- or comma-separated numbers, crownY soleY centreX. */
    private static PhotoAlignment readLandmarks(File file, Bitmap bitmap) {
        if (!file.isFile()) return null;
        try (InputStream in = new FileInputStream(file)) {
            return parseLandmarks(readAll(in), bitmap);
        } catch (IOException e) {
            Log.w(TAG, "Could not read landmarks " + file, e);
            return null;
        }
    }

    private static PhotoAlignment readAssetLandmarks(android.content.Context context, Bitmap bitmap) {
        try (InputStream in = context.getAssets()
                .open(AssetDirArtProvider.ASSET_DIR + "/" + SOURCE_NAME + ".txt")) {
            return parseLandmarks(readAll(in), bitmap);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    static PhotoAlignment parseLandmarks(String text, Bitmap bitmap) {
        if (text == null) return null;
        String[] parts = text.trim().split("[,\\s]+");
        if (parts.length < 3) return null;
        try {
            return PhotoAlignment.fromLandmarks(
                    Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
        } catch (NumberFormatException e) {
            Log.w(TAG, "Malformed landmarks '" + text.trim() + "'", e);
            return null;
        }
    }

    /** With no landmarks file, assume the figure fills the image and is horizontally centred. */
    static PhotoAlignment defaultAlignment(Bitmap bitmap) {
        return PhotoAlignment.fromLandmarks(0f, bitmap.getHeight(), bitmap.getWidth() * 0.5f);
    }

    /** True for art keys this provider deliberately leaves to the fallback. */
    public static boolean coversKey(String artKey) {
        return artKey != null && !NOT_IN_PHOTO.contains(artKey);
    }

    @Override
    public String sourceDescription() {
        return "Leon photo source " + source.getWidth() + "x" + source.getHeight()
                + " sliced into rig layers";
    }

    @Override
    public Bitmap bitmapFor(String artKey) {
        if (released || artKey == null || !coversKey(artKey)) return null;
        Bitmap cached = cache.get(artKey);
        if (cached != null && !cached.isRecycled()) return cached;

        RectF design = designRects.get(artKey);
        if (design == null) return null;

        if (EMBEDDED_IN_BASE.contains(artKey)) {
            Bitmap transparent = Bitmap.createBitmap(
                    Math.max(1, Math.round(design.width())),
                    Math.max(1, Math.round(design.height())),
                    Bitmap.Config.ARGB_8888);
            cache.put(artKey, transparent);
            return transparent;
        }

        // Map the layer's design-space bounds ONCE into source pixels. The resulting bitmap is
        // deliberately rasterised back at DESIGN size, not source-image size. Keeping the cached
        // layer in design pixels prevents the source scale (often ~2.28x on the supplied Leon
        // render) from being applied a second time by LeonRenderer's bitmap-to-part transform.
        float sourceLeftF = alignment.sourceX(design.left);
        float sourceTopF = alignment.sourceY(design.top);
        float sourceRightF = alignment.sourceX(design.right);
        float sourceBottomF = alignment.sourceY(design.bottom);

        int outW = Math.max(1, Math.round(design.width()));
        int outH = Math.max(1, Math.round(design.height()));
        Bitmap layer = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layer);

        int srcLeft = Math.max(0, (int) Math.floor(sourceLeftF));
        int srcTop = Math.max(0, (int) Math.floor(sourceTopF));
        int srcRight = Math.min(source.getWidth(), (int) Math.ceil(sourceRightF));
        int srcBottom = Math.min(source.getHeight(), (int) Math.ceil(sourceBottomF));

        if (srcRight > srcLeft && srcBottom > srcTop) {
            float sourceSpanX = Math.max(1f, sourceRightF - sourceLeftF);
            float sourceSpanY = Math.max(1f, sourceBottomF - sourceTopF);
            int dstLeft = Math.round((srcLeft - sourceLeftF) / sourceSpanX * outW);
            int dstTop = Math.round((srcTop - sourceTopF) / sourceSpanY * outH);
            int dstRight = Math.round((srcRight - sourceLeftF) / sourceSpanX * outW);
            int dstBottom = Math.round((srcBottom - sourceTopF) / sourceSpanY * outH);
            canvas.drawBitmap(source,
                    new Rect(srcLeft, srcTop, srcRight, srcBottom),
                    new Rect(dstLeft, dstTop, dstRight, dstBottom), null);
        }

        // Remove only edge-connected green-screen pixels. This preserves Leon's intentional green
        // jewellery/details because an isolated green gem is not connected to the crop boundary.
        // It also de-spills the one-pixel edge so the overlay does not glow neon green.
        keyChromaGreen(layer);

        if (LeonRig.Art.PHOTO_BACKFILL.equals(artKey)) {
            clearAnimatedPartHoles(layer);
        }

        cache.put(artKey, layer);
        return layer;
    }

    private void clearAnimatedPartHoles(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        for (Map.Entry<String, RectF> entry : designRects.entrySet()) {
            String key = entry.getKey();
            if (!ANIMATED_PHOTO_KEYS.contains(key)) continue;
            RectF r = entry.getValue();
            int left = Math.max(0, (int) Math.floor(r.left));
            int top = Math.max(0, (int) Math.floor(r.top));
            int right = Math.min(w, (int) Math.ceil(r.right));
            int bottom = Math.min(h, (int) Math.ceil(r.bottom));
            for (int y = top; y < bottom; y++) {
                int row = y * w;
                for (int x = left; x < right; x++) {
                    pixels[row + x] = android.graphics.Color.TRANSPARENT;
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    /**
     * Chroma-keys only green pixels connected to the bitmap boundary. A colour-only key deletes
     * green gemstones and tattoo highlights; boundary flood-fill removes the actual screen while
     * preserving intentional green detail inside Leon.
     */
    private static void keyChromaGreen(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int count = w * h;
        int[] pixels = new int[count];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        boolean[] candidate = new boolean[count];
        boolean[] background = new boolean[count];
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int i = 0; i < count; i++) {
            int c = pixels[i];
            int a = android.graphics.Color.alpha(c);
            int r = android.graphics.Color.red(c);
            int g = android.graphics.Color.green(c);
            int b = android.graphics.Color.blue(c);
            int maxRB = Math.max(r, b);
            candidate[i] = a != 0
                    && g >= 95
                    && g - r >= 22
                    && g - b >= 22
                    && g * 100 >= maxRB * 112;
        }

        for (int x = 0; x < w; x++) {
            seed(candidate, background, queue, x);
            seed(candidate, background, queue, (h - 1) * w + x);
        }
        for (int y = 0; y < h; y++) {
            seed(candidate, background, queue, y * w);
            seed(candidate, background, queue, y * w + (w - 1));
        }

        while (!queue.isEmpty()) {
            int i = queue.removeFirst();
            int x = i % w;
            int y = i / w;
            if (x > 0) seed(candidate, background, queue, i - 1);
            if (x + 1 < w) seed(candidate, background, queue, i + 1);
            if (y > 0) seed(candidate, background, queue, i - w);
            if (y + 1 < h) seed(candidate, background, queue, i + w);
        }

        for (int i = 0; i < count; i++) {
            if (background[i]) pixels[i] = android.graphics.Color.TRANSPARENT;
        }

        // De-spill only pixels immediately touching the keyed background.
        for (int i = 0; i < count; i++) {
            if (background[i]) continue;
            int x = i % w;
            int y = i / w;
            boolean edge = (x > 0 && background[i - 1])
                    || (x + 1 < w && background[i + 1])
                    || (y > 0 && background[i - w])
                    || (y + 1 < h && background[i + w]);
            if (!edge) continue;

            int c = pixels[i];
            int a = android.graphics.Color.alpha(c);
            int r = android.graphics.Color.red(c);
            int g = android.graphics.Color.green(c);
            int b = android.graphics.Color.blue(c);
            int maxRB = Math.max(r, b);
            if (g > maxRB + 8) {
                pixels[i] = android.graphics.Color.argb(a, r, Math.min(255, maxRB + 10), b);
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void seed(boolean[] candidate, boolean[] background,
                             ArrayDeque<Integer> queue, int index) {
        if (index < 0 || index >= candidate.length || !candidate[index] || background[index]) return;
        background[index] = true;
        queue.addLast(index);
    }

    @Override
    public void release() {
        released = true;
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cache.clear();
        if (!source.isRecycled()) source.recycle();
    }
}
