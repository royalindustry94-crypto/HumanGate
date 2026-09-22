package ai.leon.companion.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import ai.leon.companion.asset.LeonArtProvider;
import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.Mat2D;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;

/**
 * Draws a solved {@link Rig} onto a Canvas: one bitmap per layer, each with its own transform taken
 * straight from the bone hierarchy. There is no whole-character bitmap and no whole-character
 * transform — the only thing applied to everything is the design-space-to-view fit.
 *
 * <p>Allocation-free per frame: matrices, paints and the value buffer are all reused.
 */
public final class LeonRenderer {
    private final Rig rig;
    private LeonArtProvider art;

    private final Matrix viewMatrix = new Matrix();
    private final Matrix partMatrix = new Matrix();
    private final float[] values = new float[9];
    private final Paint layerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint debugBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugJointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF designBounds = new RectF();

    private boolean showRigDebug;
    private int layersDrawnLastFrame;
    private int layersMissingArt;

    public LeonRenderer(Rig rig, LeonArtProvider art) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        if (art == null) throw new IllegalArgumentException("art provider required");
        this.rig = rig;
        this.art = art;
        designBounds.set(0f, 0f, rig.designWidth, rig.designHeight);

        debugBonePaint.setStyle(Paint.Style.STROKE);
        debugBonePaint.setStrokeWidth(2f);
        debugBonePaint.setColor(Color.argb(210, 90, 240, 160));
        debugJointPaint.setStyle(Paint.Style.FILL);
        debugJointPaint.setColor(Color.argb(230, 255, 110, 110));
    }

    public Rig rig() {
        return rig;
    }

    /** Swaps the art source at runtime, e.g. after production art is copied onto the device. */
    public void setArtProvider(LeonArtProvider provider) {
        if (provider == null) throw new IllegalArgumentException("art provider required");
        this.art = provider;
    }

    /**
     * Draws the rig's bone skeleton over the character. Off by default; the control centre exposes
     * it so independent limb motion can be verified directly on a device.
     */
    public void setShowRigDebug(boolean show) {
        this.showRigDebug = show;
    }

    public boolean isShowingRigDebug() {
        return showRigDebug;
    }

    public int layersDrawnLastFrame() {
        return layersDrawnLastFrame;
    }

    /** Layers whose art key resolved to nothing in the most recent frame. */
    public int layersMissingArt() {
        return layersMissingArt;
    }

    /** Recomputes the design-space to view fit. Call from onSizeChanged. */
    public void setViewport(int viewWidth, int viewHeight) {
        viewMatrix.reset();
        if (viewWidth <= 0 || viewHeight <= 0) return;
        float scale = Math.min(viewWidth / rig.designWidth, viewHeight / rig.designHeight);
        float dx = (viewWidth - rig.designWidth * scale) * 0.5f;
        float dy = (viewHeight - rig.designHeight * scale) * 0.5f;
        viewMatrix.setScale(scale, scale);
        viewMatrix.postTranslate(dx, dy);
    }

    /** Draws the current solved pose. The rig must already have been solved for this frame. */
    public void draw(Canvas canvas) {
        layersDrawnLastFrame = 0;
        layersMissingArt = 0;

        int save = canvas.save();
        canvas.concat(viewMatrix);

        java.util.List<RigPart> parts = rig.parts();
        for (int i = 0; i < parts.size(); i++) {
            RigPart part = parts.get(i);
            if (!part.isVisible()) continue;
            android.graphics.Bitmap bitmap = art.bitmapFor(part.artKey);
            if (bitmap == null || bitmap.isRecycled()) {
                layersMissingArt++;
                continue;
            }
            Mat2D world = part.world();
            world.toMatrixValues(values);
            partMatrix.setValues(values);
            // The bitmap may be rasterised at any resolution; map its pixels onto the layer's own
            // design-space rectangle so art resolution is independent of the rig.
            partMatrix.preScale(part.width / bitmap.getWidth(), part.height / bitmap.getHeight());
            layerPaint.setAlpha(Math.round(Math.min(1f, part.alpha) * 255f));
            canvas.drawBitmap(bitmap, partMatrix, layerPaint);
            layersDrawnLastFrame++;
        }

        if (showRigDebug) drawSkeleton(canvas);
        canvas.restoreToCount(save);
    }

    private void drawSkeleton(Canvas canvas) {
        java.util.List<Bone> bones = rig.bones();
        for (int i = 0; i < bones.size(); i++) {
            Bone bone = bones.get(i);
            Mat2D w = bone.world();
            float x = w.mapX(0f, 0f);
            float y = w.mapY(0f, 0f);
            if (bone.parent != null) {
                Mat2D p = bone.parent.world();
                canvas.drawLine(p.mapX(0f, 0f), p.mapY(0f, 0f), x, y, debugBonePaint);
            }
            canvas.drawCircle(x, y, 3.5f, debugJointPaint);
        }
    }

    /** Design-space bounds, for callers that need the rig's authored size. */
    public RectF designBounds() {
        return designBounds;
    }
}
