package ai.leon.companion.asset;

import android.graphics.Color;

/**
 * Leon's colour scheme, in one place so the development rig stays visually consistent and so the
 * production art brief has exact values to match.
 */
public final class LeonPalette {
    public static final int SKIN = Color.rgb(198, 144, 100);
    public static final int SKIN_SHADOW = Color.rgb(157, 108, 70);
    public static final int SKIN_HIGHLIGHT = Color.rgb(224, 176, 132);

    /** Tattoo ink, applied over skin at partial alpha so the skin tone still shows through. */
    public static final int INK = Color.rgb(28, 38, 56);
    public static final int INK_SOFT = Color.argb(150, 32, 44, 66);

    public static final int HOODIE = Color.rgb(20, 21, 25);
    public static final int HOODIE_HIGHLIGHT = Color.rgb(44, 46, 54);
    public static final int HOODIE_SHADOW = Color.rgb(9, 10, 12);
    /** Three-stripe sleeve detail. Generic by design — see docs/LEON_CHARACTER_ASSET_SPEC.md. */
    public static final int SLEEVE_STRIPE = Color.rgb(228, 230, 235);

    public static final int PANTS = Color.rgb(15, 16, 19);
    public static final int PANTS_HIGHLIGHT = Color.rgb(34, 36, 41);

    public static final int SNEAKER = Color.rgb(243, 244, 247);
    public static final int SNEAKER_SHADOW = Color.rgb(205, 208, 214);
    public static final int SNEAKER_SOLE = Color.rgb(226, 228, 233);

    /** Sunglasses lens: deliberately translucent so the eyes and blink read through it. */
    public static final int LENS = Color.argb(206, 12, 14, 20);
    public static final int LENS_SHEEN = Color.argb(70, 150, 180, 215);
    public static final int FRAME = Color.rgb(8, 9, 12);

    public static final int GOLD = Color.rgb(217, 174, 87);
    public static final int SILVER = Color.rgb(198, 203, 212);

    public static final int EYE_WHITE = Color.rgb(243, 241, 236);
    public static final int IRIS = Color.rgb(74, 102, 74);
    public static final int PUPIL = Color.rgb(16, 18, 20);

    public static final int MOUTH_INNER = Color.rgb(74, 38, 41);
    public static final int TEETH = Color.rgb(240, 238, 232);
    public static final int LIP = Color.rgb(158, 104, 92);

    /** Multicolour gemstones for the necklace, cycled in order. */
    public static final int[] GEMS = {
            Color.rgb(226, 72, 86),    // ruby
            Color.rgb(74, 196, 140),   // emerald
            Color.rgb(82, 148, 235),   // sapphire
            Color.rgb(242, 182, 72),   // amber
            Color.rgb(168, 112, 224),  // amethyst
            Color.rgb(96, 214, 222),   // topaz
    };

    /** State feedback ring. */
    public static final int AURA = Color.rgb(96, 198, 246);

    private LeonPalette() {}
}
