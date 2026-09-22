package ai.leon.companion.rig;

/**
 * A drawable layer bound to a {@link Bone}. Each part owns its own art, its own z-order and its own
 * local offset, which is what makes independent motion possible: the left eyelid, the jaw, the
 * right shoulder and the hood are separate parts on separate bones, not regions of one bitmap.
 *
 * <p>{@link #artKey} is a logical name. The render layer resolves it to a bitmap, either from the
 * bundled development rig or from swapped-in production art, so upgrading Leon's visuals never
 * touches the rig or the overlay.
 */
public final class RigPart {
    public final String name;
    public final String artKey;
    public final Bone bone;

    /** Position of the art's pivot in bone space, and the pivot itself in normalised art space. */
    public final float offsetX;
    public final float offsetY;
    public final float pivotX;
    public final float pivotY;
    public final float width;
    public final float height;
    public final float restRotationDeg;
    public final int z;

    /**
     * Mutually exclusive swap group (e.g. {@code mouth}, {@code brow_l}). At most one part per
     * group is expected to be fully opaque at a time; the renderer still honours per-part alpha so
     * groups can cross-fade.
     */
    public final String group;

    /** Per-frame state written by the animation layer. */
    public float alpha = 1f;
    public float rotationDeg;
    public float animOffsetX;
    public float animOffsetY;
    public float scaleX = 1f;
    public float scaleY = 1f;

    private final Mat2D local = new Mat2D();
    private final Mat2D world = new Mat2D();

    RigPart(String name, String artKey, Bone bone, float offsetX, float offsetY,
            float width, float height, float pivotX, float pivotY, float restRotationDeg,
            int z, String group) {
        this.name = name;
        this.artKey = artKey;
        this.bone = bone;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.width = width;
        this.height = height;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.restRotationDeg = restRotationDeg;
        this.z = z;
        this.group = group;
    }

    public void resetAnimation() {
        alpha = 1f;
        rotationDeg = 0f;
        animOffsetX = 0f;
        animOffsetY = 0f;
        scaleX = 1f;
        scaleY = 1f;
    }

    void solve() {
        local.setTransform(offsetX + animOffsetX, offsetY + animOffsetY,
                restRotationDeg + rotationDeg, scaleX, scaleY,
                pivotX * width, pivotY * height);
        Mat2D.concat(bone.world(), local, world);
    }

    /** Solved world transform, valid after {@link Rig#solve()}. */
    public Mat2D world() {
        return world;
    }

    public boolean isVisible() {
        return alpha > 0.002f && scaleX != 0f && scaleY != 0f;
    }

    @Override
    public String toString() {
        return "RigPart{" + name + "->" + artKey + "@" + bone.name + "}";
    }
}
