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

        int left = Math.round(alignment.sourceX(design.left));
        int top = Math.round(alignment.sourceY(design.top));
        int right = Math.round(alignment.sourceX(design.right));
        int bottom = Math.round(alignment.sourceY(design.bottom));
        int w = Math.max(1, right - left);
        int h = Math.max(1, bottom - top);

        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layer);
        // Copy the region out of the source. Areas outside the image stay transparent, which is
        // correct for a layer that runs off the edge of the render.
        canvas.drawBitmap(source, new Rect(left, top, left + w, top + h),
                new Rect(0, 0, w, h), null);
        cache.put(artKey, layer);
        return layer;
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
