package ai.leon.companion.anim;

/**
 * An independent source of motion that adds its contribution onto the working pose each frame.
 * Behaviours never own the whole character, which is how breathing, blinking, head drift, gaze and
 * gesture can all run at once without fighting each other.
 */
public interface LeonBehaviour {
    /**
     * @param dt     seconds since the previous frame
     * @param weight 0..1 blend from the active state profile; 0 means fully faded out
     * @param pose   working pose to add into
     */
    void update(float dt, float weight, LeonPose pose);

    /** Returns the behaviour to a clean start, e.g. when the overlay is recreated. */
    void reset();
}
