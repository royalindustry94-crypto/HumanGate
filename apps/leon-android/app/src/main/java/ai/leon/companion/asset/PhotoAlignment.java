package ai.leon.companion.asset;

import ai.leon.companion.rig.LeonRig;

/**
 * Maps a photograph or 3D render of Leon onto the rig's design space.
 *
 * <p>The rig is authored in a 384x768 box with fixed anatomy: the crown of the head at y=44, the
 * soles at y=732 and the centre line at x=192. Given where those three landmarks fall in a source
 * image, this works out the single scale-and-offset that puts the source into that box — after which
 * every rig layer's crop is simply its own rectangle, because the photo and the skeleton now share
 * one coordinate system.
 *
 * <p>Three numbers are all a new render needs to be rigged.
 */
public final class PhotoAlignment {
    /** Design-space y of the crown of the head. */
    public static final float DESIGN_CROWN_Y = 44f;
    /** Design-space y of the soles. */
    public static final float DESIGN_SOLE_Y = 732f;
    /** Design-space x of the figure's centre line. */
    public static final float DESIGN_CENTRE_X = LeonRig.DESIGN_W * 0.5f;

    /** Source pixels per design unit. */
    public final float scale;
    /** Source pixel coordinate that design (0,0) maps to. */
    public final float originX;
    public final float originY;

    private PhotoAlignment(float scale, float originX, float originY) {
        this.scale = scale;
        this.originX = originX;
        this.originY = originY;
    }

    /**
     * @param crownY  source y of the top of the head
     * @param soleY   source y of the bottom of the shoes
     * @param centreX source x of the figure's vertical centre line
     */
    public static PhotoAlignment fromLandmarks(float crownY, float soleY, float centreX) {
        float span = soleY - crownY;
        if (span <= 1f) {
            throw new IllegalArgumentException("soleY must be below crownY; got " + crownY + ".." + soleY);
        }
        float scale = span / (DESIGN_SOLE_Y - DESIGN_CROWN_Y);
        float originY = crownY - DESIGN_CROWN_Y * scale;
        float originX = centreX - DESIGN_CENTRE_X * scale;
        return new PhotoAlignment(scale, originX, originY);
    }

    /** Source x for a design-space x. */
    public float sourceX(float designX) {
        return originX + designX * scale;
    }

    /** Source y for a design-space y. */
    public float sourceY(float designY) {
        return originY + designY * scale;
    }

    /** Source length for a design-space length. */
    public float sourceLength(float designLength) {
        return designLength * scale;
    }

    @Override
    public String toString() {
        return "PhotoAlignment{scale=" + scale + ", origin=(" + originX + "," + originY + ")}";
    }
}
