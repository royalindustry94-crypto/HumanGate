package ai.leon.companion.overlay;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/**
 * The usable screen area for the overlay: full size minus the system bars and any display cutout.
 * Leon is clamped to this, which is how he stays clear of the status bar, the navigation area and a
 * notch or punch-hole.
 */
public final class ScreenMetrics {
    public final int width;
    public final int height;
    public final int insetLeft;
    public final int insetTop;
    public final int insetRight;
    public final int insetBottom;

    private ScreenMetrics(int width, int height, int left, int top, int right, int bottom) {
        this.width = width;
        this.height = height;
        this.insetLeft = left;
        this.insetTop = top;
        this.insetRight = right;
        this.insetBottom = bottom;
    }

    public static ScreenMetrics of(Context context, WindowManager windowManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
            Rect bounds = metrics.getBounds();
            WindowInsets windowInsets = metrics.getWindowInsets();
            Insets insets = windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new ScreenMetrics(bounds.width(), bounds.height(),
                    insets.left, insets.top, insets.right, insets.bottom);
        }

        // API 26-29: real display size plus the system bar heights from platform resources.
        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(dm);
        int top = systemDimen(context, "status_bar_height", Math.round(24f * dm.density));
        int bottom = systemDimen(context, "navigation_bar_height", Math.round(48f * dm.density));
        return new ScreenMetrics(dm.widthPixels, dm.heightPixels, 0, top, 0, bottom);
    }

    private static int systemDimen(Context context, String name, int fallbackPx) {
        int id = context.getResources().getIdentifier(name, "dimen", "android");
        if (id <= 0) return fallbackPx;
        int value = context.getResources().getDimensionPixelSize(id);
        return value > 0 ? value : fallbackPx;
    }

    @Override
    public String toString() {
        return width + "x" + height + " insets(" + insetLeft + "," + insetTop + ","
                + insetRight + "," + insetBottom + ")";
    }
}
