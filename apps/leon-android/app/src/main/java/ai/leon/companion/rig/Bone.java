package ai.leon.companion.rig;

/**
 * One joint of Leon's skeleton. A bone carries an immutable rest pose plus per-frame animated
 * offsets; {@link Rig#solve()} composes it with its parent so rotating {@code head} carries the
 * sunglasses, tattoos and jaw with it without any of them being transformed individually.
 */
public final class Bone {
    public final String name;
    public final Bone parent;

    /** Rest pose, in the parent bone's coordinate space. Set once when the rig is built. */
    public final float restX;
    public final float restY;
    public final float restRotationDeg;
    public final float restScaleX;
    public final float restScaleY;

    /** Per-frame animation, reset every frame and written by the animation layer. */
    public float rotationDeg;
    public float offsetX;
    public float offsetY;
    public float scaleX = 1f;
    public float scaleY = 1f;

    private final Mat2D local = new Mat2D();
    private final Mat2D world = new Mat2D();

    Bone(String name, Bone parent, float restX, float restY, float restRotationDeg,
         float restScaleX, float restScaleY) {
        this.name = name;
        this.parent = parent;
        this.restX = restX;
        this.restY = restY;
        this.restRotationDeg = restRotationDeg;
        this.restScaleX = restScaleX;
        this.restScaleY = restScaleY;
    }

    /** Clears animation back to the rest pose. Called once per frame before behaviours run. */
    public void resetAnimation() {
        rotationDeg = 0f;
        offsetX = 0f;
        offsetY = 0f;
        scaleX = 1f;
        scaleY = 1f;
    }

    void solve() {
        local.setTransform(restX + offsetX, restY + offsetY, restRotationDeg + rotationDeg,
                restScaleX * scaleX, restScaleY * scaleY, 0f, 0f);
        if (parent == null) {
            world.set(local);
        } else {
            Mat2D.concat(parent.world, local, world);
        }
    }

    /** Solved world transform. Only valid after {@link Rig#solve()} for the current frame. */
    public Mat2D world() {
        return world;
    }

    @Override
    public String toString() {
        return "Bone{" + name + "}";
    }
}
