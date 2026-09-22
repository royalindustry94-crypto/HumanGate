package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.overlay.OverlayPlacement;

import org.junit.Test;

/**
 * Where Leon is allowed to sit. Pixel positions must survive a rotation, and must never land under a
 * status bar, a navigation area or a display cutout.
 */
public class OverlayPlacementTest {
    private static final int SCREEN_W = 1080;
    private static final int SCREEN_H = 2340;
    private static final int LEFT = 0;
    private static final int TOP = 96;
    private static final int RIGHT = 0;
    private static final int BOTTOM = 132;
    private static final int OVERLAY_W = 396;
    private static final int OVERLAY_H = 792;

    @Test
    public void clampingKeepsLeonInsideTheInsets() {
        OverlayPlacement p = OverlayPlacement.clamp(-500, -500, OVERLAY_W, OVERLAY_H,
                SCREEN_W, SCREEN_H, LEFT, TOP, RIGHT, BOTTOM);
        assertEquals(LEFT, p.x);
        assertEquals(TOP, p.y);

        OverlayPlacement q = OverlayPlacement.clamp(9999, 9999, OVERLAY_W, OVERLAY_H,
                SCREEN_W, SCREEN_H, LEFT, TOP, RIGHT, BOTTOM);
        assertEquals(SCREEN_W - OVERLAY_W, q.x);
        assertEquals(SCREEN_H - BOTTOM - OVERLAY_H, q.y);
        assertTrue("Leon's bottom edge must clear the navigation area",
                q.y + OVERLAY_H <= SCREEN_H - BOTTOM);
    }

    @Test
    public void aCutoutInsetIsRespected() {
        int cutout = 140;
        OverlayPlacement p = OverlayPlacement.clamp(0, 0, OVERLAY_W, OVERLAY_H,
                SCREEN_W, SCREEN_H, cutout, TOP, cutout, BOTTOM);
        assertEquals(cutout, p.x);
        OverlayPlacement q = OverlayPlacement.clamp(SCREEN_W, 0, OVERLAY_W, OVERLAY_H,
                SCREEN_W, SCREEN_H, cutout, TOP, cutout, BOTTOM);
        assertEquals(SCREEN_W - cutout - OVERLAY_W, q.x);
    }

    @Test
    public void fractionsRoundTripInPortrait() {
        for (int x = 0; x <= SCREEN_W - OVERLAY_W; x += 60) {
            float fraction = OverlayPlacement.toFractionX(x, OVERLAY_W, SCREEN_W, LEFT, RIGHT);
            OverlayPlacement back = OverlayPlacement.fromFraction(fraction, 0f,
                    OVERLAY_W, OVERLAY_H, SCREEN_W, SCREEN_H, LEFT, TOP, RIGHT, BOTTOM);
            assertEquals("x=" + x + " must round trip", x, back.x, 1.0);
        }
    }

    @Test
    public void aPositionSavedInPortraitStaysOnScreenInLandscape() {
        // Right-hand edge in portrait.
        float fx = OverlayPlacement.toFractionX(SCREEN_W - OVERLAY_W, OVERLAY_W, SCREEN_W, LEFT, RIGHT);
        float fy = OverlayPlacement.toFractionY(SCREEN_H - BOTTOM - OVERLAY_H, OVERLAY_H, SCREEN_H,
                TOP, BOTTOM);

        OverlayPlacement landscape = OverlayPlacement.fromFraction(fx, fy,
                OVERLAY_W, OVERLAY_H, SCREEN_H, SCREEN_W, LEFT, TOP, RIGHT, BOTTOM);
        assertTrue("x must stay on screen", landscape.x >= 0 && landscape.x + OVERLAY_W <= SCREEN_H);
        assertTrue("y must stay on screen",
                landscape.y >= TOP && landscape.y + OVERLAY_H <= SCREEN_W - BOTTOM);
    }

    @Test
    public void anOverlayTallerThanTheScreenIsPinnedRatherThanGivenANegativePosition() {
        OverlayPlacement p = OverlayPlacement.clamp(0, 500, 400, 5000,
                SCREEN_W, SCREEN_H, LEFT, TOP, RIGHT, BOTTOM);
        assertEquals("a degenerate region must pin to the top inset", TOP, p.y);
    }

    @Test
    public void fractionsAreZeroWhenThereIsNoRoomToMove() {
        assertEquals(0f, OverlayPlacement.toFractionX(0, SCREEN_W, SCREEN_W, LEFT, RIGHT), 0.0001f);
    }

    @Test
    public void snappingPicksTheNearerEdge() {
        assertEquals(LEFT, OverlayPlacement.snapToNearestEdge(40, OVERLAY_W, SCREEN_W, LEFT, RIGHT));
        assertEquals(SCREEN_W - OVERLAY_W,
                OverlayPlacement.snapToNearestEdge(600, OVERLAY_W, SCREEN_W, LEFT, RIGHT));
    }

    @Test
    public void fractionsOutsideZeroToOneAreClamped() {
        OverlayPlacement low = OverlayPlacement.fromFraction(-3f, -3f, OVERLAY_W, OVERLAY_H,
                SCREEN_W, SCREEN_H, LEFT, TOP, RIGHT, BOTTOM);
        assertEquals(LEFT, low.x);
        assertEquals(TOP, low.y);
        OverlayPlacement high = OverlayPlacement.fromFraction(3f, 3f, OVERLAY_W, OVERLAY_H,
                SCREEN_W, SCREEN_H, LEFT, TOP, RIGHT, BOTTOM);
        assertEquals(SCREEN_W - OVERLAY_W, high.x);
    }
}
