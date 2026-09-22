package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ai.leon.companion.asset.PhotoAlignment;
import ai.leon.companion.rig.LeonRig;

import org.junit.Test;

/**
 * Aligning a photograph or render of Leon to the rig. Get this wrong and every layer crops from the
 * wrong part of his body, so the three landmarks are worth pinning down.
 */
public class PhotoAlignmentTest {
    @Test
    public void landmarksMapOntoTheRigsAnatomy() {
        // A 1000px-tall render with the crown at 100 and the soles at 900.
        PhotoAlignment a = PhotoAlignment.fromLandmarks(100f, 900f, 300f);
        assertEquals("crown must land on the rig's crown", 100f,
                a.sourceY(PhotoAlignment.DESIGN_CROWN_Y), 0.01f);
        assertEquals("soles must land on the rig's soles", 900f,
                a.sourceY(PhotoAlignment.DESIGN_SOLE_Y), 0.01f);
        assertEquals("the centre line must land on the figure's centre", 300f,
                a.sourceX(PhotoAlignment.DESIGN_CENTRE_X), 0.01f);
    }

    @Test
    public void scaleIsSourcePixelsPerDesignUnit() {
        PhotoAlignment a = PhotoAlignment.fromLandmarks(0f, 688f, 100f);
        // 688 source pixels span the 688 design units between crown and sole.
        assertEquals(1f, a.scale, 0.001f);
        assertEquals(100f, a.sourceLength(100f), 0.001f);
    }

    @Test
    public void aTallerRenderScalesEveryLayerConsistently() {
        PhotoAlignment small = PhotoAlignment.fromLandmarks(0f, 688f, 192f);
        PhotoAlignment big = PhotoAlignment.fromLandmarks(0f, 1376f, 384f);
        assertEquals("twice the pixels means twice the scale", small.scale * 2f, big.scale, 0.001f);
        // The head lands at the same relative place in both.
        float smallHead = (small.sourceY(112f) - small.sourceY(44f)) / small.scale;
        float bigHead = (big.sourceY(112f) - big.sourceY(44f)) / big.scale;
        assertEquals(smallHead, bigHead, 0.001f);
    }

    @Test
    public void anOffCentreFigureIsStillAligned() {
        PhotoAlignment a = PhotoAlignment.fromLandmarks(50f, 850f, 420f);
        assertEquals(420f, a.sourceX(LeonRig.DESIGN_W * 0.5f), 0.01f);
        // Design x=0 sits exactly half a figure-width to the left of the centre line.
        float halfBox = LeonRig.DESIGN_W * 0.5f * a.scale;
        assertEquals(420f - halfBox, a.sourceX(0f), 0.01f);
    }

    @Test
    public void degenerateLandmarksAreRejectedRatherThanProducingAnInfiniteScale() {
        try {
            PhotoAlignment.fromLandmarks(500f, 500f, 100f);
            fail("expected a rejection when the crown and soles coincide");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("soleY"));
        }
        try {
            PhotoAlignment.fromLandmarks(900f, 100f, 100f);
            fail("expected a rejection when the soles are above the crown");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("soleY"));
        }
    }

    @Test
    public void everyRigLayerFallsInsideAFigureAlignedToTheDesignBox() {
        // With the figure filling the design box, no layer should crop from outside a render that
        // covers the box — otherwise a correctly aligned photo would still produce empty layers.
        PhotoAlignment a = PhotoAlignment.fromLandmarks(
                PhotoAlignment.DESIGN_CROWN_Y, PhotoAlignment.DESIGN_SOLE_Y,
                PhotoAlignment.DESIGN_CENTRE_X);
        assertEquals(1f, a.scale, 0.001f);
        assertEquals(0f, a.sourceX(0f), 0.01f);
        assertEquals(0f, a.sourceY(0f), 0.01f);
    }
}
