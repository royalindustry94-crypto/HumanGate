package ai.leon.companion.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The only production visual payload for Leon: one transparent full-body texture plus
 * a deterministic manifest. There is deliberately no fallback to procedural character art.
 */
public final class ProductionLeonTexture implements AutoCloseable {
    private static final String TEXTURE_PATH = "leon/production/leon.png";
    private static final String MANIFEST_PATH = "leon/production/manifest.json";

    private final Bitmap bitmap;
    private final Manifest manifest;
    private boolean closed;

    private ProductionLeonTexture(Bitmap bitmap, Manifest manifest) {
        this.bitmap = bitmap;
        this.manifest = manifest;
    }

    public static ProductionLeonTexture load(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        try {
            Manifest manifest;
            try (InputStream in = context.getAssets().open(MANIFEST_PATH)) {
                manifest = Manifest.parse(readUtf8(in));
            }

            Bitmap bitmap;
            try (InputStream in = context.getAssets().open(TEXTURE_PATH)) {
                bitmap = BitmapFactory.decodeStream(in);
            }
            if (bitmap == null) {
                throw new IllegalStateException("Leon production texture could not be decoded");
            }
            if (bitmap.getWidth() != manifest.sourceWidth
                    || bitmap.getHeight() != manifest.sourceHeight) {
                bitmap.recycle();
                throw new IllegalStateException(
                        "Leon production texture dimensions do not match manifest: "
                                + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " vs " + manifest.sourceWidth + "x" + manifest.sourceHeight);
            }
            if (manifest.meshCols <= 0 || manifest.meshRows <= 0
                    || manifest.alphaBounds.isEmpty()) {
                bitmap.recycle();
                throw new IllegalStateException("Leon production manifest is invalid");
            }
            return new ProductionLeonTexture(bitmap, manifest);
        } catch (IOException | JSONException e) {
            throw new IllegalStateException("Leon production assets are missing or invalid", e);
        }
    }

    public Bitmap bitmap() {
        ensureOpen();
        return bitmap;
    }

    public Manifest manifest() {
        ensureOpen();
        return manifest;
    }

    public boolean isValid() {
        return !closed && bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() == manifest.sourceWidth
                && bitmap.getHeight() == manifest.sourceHeight
                && !manifest.alphaBounds.isEmpty();
    }

    public String report() {
        return isValid()
                ? "Leon production texture: " + manifest.sourceWidth + "x" + manifest.sourceHeight
                        + ", mesh " + manifest.meshCols + "x" + manifest.meshRows
                        + ", full-body alpha " + manifest.alphaBounds
                : "Leon production texture: invalid/closed";
    }

    @Override
    public void close() {
        closed = true;
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }

    private void ensureOpen() {
        if (closed || bitmap == null || bitmap.isRecycled()) {
            throw new IllegalStateException("Leon production texture is closed");
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    public static final class Manifest {
        public final int sourceWidth;
        public final int sourceHeight;
        public final int meshCols;
        public final int meshRows;
        public final float crownY;
        public final float soleY;
        public final float centreX;
        public final String sourceSha256;
        public final Rect alphaBounds;

        public Manifest(int sourceWidth, int sourceHeight, int meshCols, int meshRows,
                        float crownY, float soleY, float centreX, String sourceSha256,
                        Rect alphaBounds) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.meshCols = meshCols;
            this.meshRows = meshRows;
            this.crownY = crownY;
            this.soleY = soleY;
            this.centreX = centreX;
            this.sourceSha256 = sourceSha256;
            this.alphaBounds = new Rect(alphaBounds);
        }

        static Manifest parse(String json) throws JSONException {
            JSONObject root = new JSONObject(json);
            JSONObject landmarks = root.getJSONObject("landmarks");
            JSONObject bounds = root.getJSONObject("alpha_bounds");
            return new Manifest(
                    root.getInt("source_width"),
                    root.getInt("source_height"),
                    root.getInt("mesh_cols"),
                    root.getInt("mesh_rows"),
                    (float) landmarks.getDouble("crown_y"),
                    (float) landmarks.getDouble("sole_y"),
                    (float) landmarks.getDouble("centre_x"),
                    root.getString("source_sha256"),
                    new Rect(
                            bounds.getInt("left"),
                            bounds.getInt("top"),
                            bounds.getInt("right") + 1,
                            bounds.getInt("bottom") + 1));
        }
    }
}
