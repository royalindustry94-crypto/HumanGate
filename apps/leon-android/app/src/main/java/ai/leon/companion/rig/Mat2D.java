package ai.leon.companion.rig;

import ai.leon.companion.util.Mathx;

/**
 * Mutable 2x3 affine matrix — {@code [a c tx; b d ty]} — matching the column convention used by
 * android.graphics.Matrix so a solved bone transform can be handed to Canvas without conversion.
 *
 * <p>Kept free of Android types so the whole skeleton can be solved and asserted in plain JVM
 * unit tests.
 */
public final class Mat2D {
    public float a = 1f, b = 0f, c = 0f, d = 1f, tx = 0f, ty = 0f;

    public Mat2D() {}

    public Mat2D reset() {
        a = 1f; b = 0f; c = 0f; d = 1f; tx = 0f; ty = 0f;
        return this;
    }

    public Mat2D set(Mat2D o) {
        a = o.a; b = o.b; c = o.c; d = o.d; tx = o.tx; ty = o.ty;
        return this;
    }

    /**
     * Builds translate * rotate * scale around a local pivot, i.e. the pivot point stays fixed
     * while the part rotates and scales about it.
     */
    public Mat2D setTransform(float x, float y, float rotationDeg, float scaleX, float scaleY,
                              float pivotX, float pivotY) {
        float r = rotationDeg * Mathx.DEG_TO_RAD;
        float cos = (float) Math.cos(r);
        float sin = (float) Math.sin(r);
        a = cos * scaleX;
        b = sin * scaleX;
        c = -sin * scaleY;
        d = cos * scaleY;
        tx = x - (a * pivotX + c * pivotY);
        ty = y - (b * pivotX + d * pivotY);
        return this;
    }

    /** {@code out = parent * child}. {@code out} may alias {@code parent} or {@code child}. */
    public static Mat2D concat(Mat2D parent, Mat2D child, Mat2D out) {
        float na = parent.a * child.a + parent.c * child.b;
        float nb = parent.b * child.a + parent.d * child.b;
        float nc = parent.a * child.c + parent.c * child.d;
        float nd = parent.b * child.c + parent.d * child.d;
        float ntx = parent.a * child.tx + parent.c * child.ty + parent.tx;
        float nty = parent.b * child.tx + parent.d * child.ty + parent.ty;
        out.a = na; out.b = nb; out.c = nc; out.d = nd; out.tx = ntx; out.ty = nty;
        return out;
    }

    public float mapX(float x, float y) {
        return a * x + c * y + tx;
    }

    public float mapY(float x, float y) {
        return b * x + d * y + ty;
    }

    /** Rotation of the matrix in degrees, derived from the mapped x axis. */
    public float rotationDeg() {
        return (float) Math.toDegrees(Math.atan2(b, a));
    }

    /** Uniform-ish scale magnitude, used for level-of-detail decisions. */
    public float scaleMagnitude() {
        return (float) Math.sqrt(Math.abs(a * d - b * c));
    }

    /** Copies into the 9-element row-major array that android.graphics.Matrix.setValues() wants. */
    public void toMatrixValues(float[] out) {
        if (out == null || out.length < 9) throw new IllegalArgumentException("need float[9]");
        out[0] = a;  out[1] = c;  out[2] = tx;
        out[3] = b;  out[4] = d;  out[5] = ty;
        out[6] = 0f; out[7] = 0f; out[8] = 1f;
    }
}
