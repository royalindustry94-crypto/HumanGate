package ai.leon.companion.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;

import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.Mat2D;
import ai.leon.companion.rig.Rig;

/**
 * Production renderer for Leon's single continuous full-body texture.
 *
 * <p>The renderer never resolves per-part artwork. The solved skeleton drives two weighted meshes
 * over the one real Leon photo, split into a body layer and an arm layer (see
 * {@link LeonMeshRig.Layer}), and Android deforms each with {@link Canvas#drawBitmapMesh}. The rest
 * pose is pixel-identical to the photo, and no triangle ever joins a hand to a hip.
 */
public final class LeonPuppetRenderer {
    private final Rig rig;
    private final ProductionLeonTexture texture;
    private final LeonMeshRig mesh;
    private final LeonMeshRig armMesh;

    private final Matrix viewMatrix = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint debugBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugJointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean showRigDebug;

    public LeonPuppetRenderer(Rig rig, ProductionLeonTexture texture) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        if (texture == null || !texture.isValid()) {
            throw new IllegalArgumentException("valid production Leon texture required");
        }
        this.rig = rig;
        this.texture = texture;
        this.mesh = LeonMeshRig.create(rig, texture.manifest(), LeonMeshRig.Layer.BODY);
        this.armMesh = LeonMeshRig.create(rig, texture.manifest(), LeonMeshRig.Layer.ARMS);

        debugBonePaint.setStyle(Paint.Style.STROKE);
        debugBonePaint.setStrokeWidth(2f);
        debugBonePaint.setColor(Color.argb(220, 90, 240, 160));
        debugJointPaint.setStyle(Paint.Style.FILL);
        debugJointPaint.setColor(Color.argb(235, 255, 110, 110));
    }

    public void setViewport(int width, int height) {
        viewMatrix.reset();
        if (width <= 0 || height <= 0) return;
        float scale = Math.min(width / rig.designWidth, height / rig.designHeight);
        float dx = (width - rig.designWidth * scale) * 0.5f;
        float dy = (height - rig.designHeight * scale) * 0.5f;
        viewMatrix.setScale(scale, scale);
        viewMatrix.postTranslate(dx, dy);
    }

    public void setShowRigDebug(boolean show) {
        showRigDebug = show;
    }

    public boolean isShowingRigDebug() {
        return showRigDebug;
    }

    public LeonMeshRig mesh() {
        return mesh;
    }

    public void draw(Canvas canvas) {
        mesh.updateFromSolvedRig();
        armMesh.updateFromSolvedRig();

        int save = canvas.save();
        canvas.concat(viewMatrix);
        // Body first, arms on top: a hand swinging inwards passes in front of the hip.
        drawLayer(canvas, texture.bodyLayer(), mesh);
        drawLayer(canvas, texture.armLayer(), armMesh);
        if (showRigDebug) drawSkeleton(canvas);
        canvas.restoreToCount(save);
    }

    private void drawLayer(Canvas canvas, Bitmap layer, LeonMeshRig layerMesh) {
        canvas.drawBitmapMesh(layer, layerMesh.meshCols(), layerMesh.meshRows(),
                layerMesh.deformedVertices(), 0, null, 0, paint);
    }

    private void drawSkeleton(Canvas canvas) {
        for (Bone bone : rig.bones()) {
            Mat2D world = bone.world();
            float x = world.mapX(0f, 0f);
            float y = world.mapY(0f, 0f);
            if (bone.parent != null) {
                Mat2D parent = bone.parent.world();
                canvas.drawLine(parent.mapX(0f, 0f), parent.mapY(0f, 0f),
                        x, y, debugBonePaint);
            }
            canvas.drawCircle(x, y, 3.5f, debugJointPaint);
        }
    }
}
