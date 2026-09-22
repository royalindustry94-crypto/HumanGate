package ai.leon.companion.overlay;

import ai.leon.companion.util.Mathx;

/**
 * Where Leon is allowed to sit on screen, and how that position survives a rotation, a different
 * device or a change of overlay size.
 *
 * <p>The stored value is a fraction of the usable region rather than a pixel coordinate, so Leon
 * comes back in the same relative place after a rotation instead of off screen. The usable region
 * excludes the window insets, which is how display cutouts, status bars and navigation areas are
 * respected.
 *
 * <p>Pure Java so the clamping is unit-testable without a device.
 */
public final class OverlayPlacement {
    public final int x;
    public final int y;

    private OverlayPlacement(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** Widest x Leon's left edge may take. */
    public static int maxX(int overlayWidth, int screenWidth, int insetLeft, int insetRight) {
        return Math.max(insetLeft, screenWidth - insetRight - overlayWidth);
    }

    /** Lowest y Leon's top edge may take. */
    public static int maxY(int overlayHeight, int screenHeight, int insetTop, int insetBottom) {
        return Math.max(insetTop, screenHeight - insetBottom - overlayHeight);
    }

    /** Clamps a pixel position into the usable region. */
    public static OverlayPlacement clamp(int x, int y, int overlayWidth, int overlayHeight,
                                         int screenWidth, int screenHeight,
                                         int insetLeft, int insetTop, int insetRight, int insetBottom) {
        int maxX = maxX(overlayWidth, screenWidth, insetLeft, insetRight);
        int maxY = maxY(overlayHeight, screenHeight, insetTop, insetBottom);
        int cx = Math.min(Math.max(x, insetLeft), maxX);
        int cy = Math.min(Math.max(y, insetTop), maxY);
        return new OverlayPlacement(cx, cy);
    }

    /** Turns a stored 0..1 fraction back into a pixel position inside the usable region. */
    public static OverlayPlacement fromFraction(float fractionX, float fractionY,
                                                int overlayWidth, int overlayHeight,
                                                int screenWidth, int screenHeight,
                                                int insetLeft, int insetTop,
                                                int insetRight, int insetBottom) {
        int maxX = maxX(overlayWidth, screenWidth, insetLeft, insetRight);
        int maxY = maxY(overlayHeight, screenHeight, insetTop, insetBottom);
        int x = Math.round(insetLeft + (maxX - insetLeft) * Mathx.clamp01(fractionX));
        int y = Math.round(insetTop + (maxY - insetTop) * Mathx.clamp01(fractionY));
        return clamp(x, y, overlayWidth, overlayHeight, screenWidth, screenHeight,
                insetLeft, insetTop, insetRight, insetBottom);
    }

    /** Turns a pixel position into the 0..1 fraction that gets stored. */
    public static float toFractionX(int x, int overlayWidth, int screenWidth,
                                    int insetLeft, int insetRight) {
        int maxX = maxX(overlayWidth, screenWidth, insetLeft, insetRight);
        int span = maxX - insetLeft;
        if (span <= 0) return 0f;
        return Mathx.clamp01((x - insetLeft) / (float) span);
    }

    public static float toFractionY(int y, int overlayHeight, int screenHeight,
                                    int insetTop, int insetBottom) {
        int maxY = maxY(overlayHeight, screenHeight, insetTop, insetBottom);
        int span = maxY - insetTop;
        if (span <= 0) return 0f;
        return Mathx.clamp01((y - insetTop) / (float) span);
    }

    /**
     * Nudges Leon to whichever side edge he is nearer, the way a floating companion should settle
     * rather than being left in the middle of the user's content.
     */
    public static int snapToNearestEdge(int x, int overlayWidth, int screenWidth,
                                        int insetLeft, int insetRight) {
        int maxX = maxX(overlayWidth, screenWidth, insetLeft, insetRight);
        int centre = x + overlayWidth / 2;
        return centre * 2 < screenWidth ? insetLeft : maxX;
    }
}
