package ai.leon.companion.asset;

import android.graphics.Bitmap;

/**
 * Resolves a rig layer's logical art key to a bitmap. The renderer only ever talks to this
 * interface, so Leon's built-in artwork and any custom art files are interchangeable without
 * a single change to the rig, the animation layer or the overlay.
 */
public interface LeonArtProvider {
    /** @return the bitmap for {@code artKey}, or null when this provider does not have it. */
    Bitmap bitmapFor(String artKey);

    /** Human-readable description of where this art came from, for the control centre. */
    String sourceDescription();

    /** Releases any bitmaps held. The provider must be unusable afterwards. */
    void release();
}
