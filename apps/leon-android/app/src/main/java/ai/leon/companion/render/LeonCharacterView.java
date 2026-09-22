package ai.leon.companion.render;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Choreographer;
import android.view.View;

import ai.leon.companion.anim.LeonAnimationController;
import ai.leon.companion.asset.LeonArtProvider;
import ai.leon.companion.rig.Rig;

/**
 * The view Leon is actually drawn in. A transparent, hardware-accelerated custom View — not a
 * SurfaceView, which cannot be transparent inside an overlay window, and not an ImageView, which
 * cannot animate parts independently.
 *
 * <p>Frame pacing is adaptive and is the main reason an always-on companion does not drain the
 * battery: while Leon is idle the loop runs at {@link #IDLE_FPS} by scheduling the next frame on a
 * delay instead of taking every vsync, and when the screen goes off it stops entirely. It returns to
 * {@link #ACTIVE_FPS} whenever the animation controller reports something worth showing smoothly.
 */
public final class LeonCharacterView extends View {
    public static final int ACTIVE_FPS = 60;
    public static final int IDLE_FPS = 20;
    /** Longest gap the animation is stepped by in one frame, so a stall cannot jolt the rig. */
    private static final long MAX_FRAME_NANOS = 100_000_000L;

    private final LeonAnimationController controller;
    private final LeonRenderer renderer;
    private final Choreographer choreographer;

    private long lastFrameNanos;
    private boolean running;
    private boolean paused;
    private boolean attached;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) return;
            long delta = lastFrameNanos == 0L ? 0L : frameTimeNanos - lastFrameNanos;
            if (delta < 0L) delta = 0L;
            if (delta > MAX_FRAME_NANOS) delta = MAX_FRAME_NANOS;
            lastFrameNanos = frameTimeNanos;

            controller.update(delta / 1_000_000_000f);
            invalidate();
            scheduleNextFrame();
        }
    };

    private final Runnable resumeLoop = new Runnable() {
        @Override
        public void run() {
            if (running) choreographer.postFrameCallback(frameCallback);
        }
    };

    public LeonCharacterView(Context context, Rig rig, LeonAnimationController controller,
                             LeonArtProvider art) {
        super(context);
        if (controller == null) throw new IllegalArgumentException("animation controller required");
        this.controller = controller;
        this.renderer = new LeonRenderer(rig, art);
        this.choreographer = Choreographer.getInstance();
        setBackgroundColor(Color.TRANSPARENT);
        // Leon must look like he is standing on the user's screen, not sitting on a card.
        setWillNotDraw(false);
    }

    public LeonRenderer renderer() {
        return renderer;
    }

    public LeonAnimationController controller() {
        return controller;
    }

    /** Stops or resumes the render loop. Called when the screen turns off and on. */
    public void setPaused(boolean paused) {
        if (this.paused == paused) return;
        this.paused = paused;
        if (paused) {
            stopLoop();
        } else if (attached) {
            // A fresh clock, so time spent with the screen off is not integrated in one frame.
            lastFrameNanos = 0L;
            startLoop();
        }
    }

    public boolean isPaused() {
        return paused;
    }

    private void startLoop() {
        if (running) return;
        running = true;
        lastFrameNanos = 0L;
        choreographer.postFrameCallback(frameCallback);
    }

    private void stopLoop() {
        running = false;
        choreographer.removeFrameCallback(frameCallback);
        removeCallbacks(resumeLoop);
    }

    private void scheduleNextFrame() {
        if (!running) return;
        if (controller.wantsHighFrameRate()) {
            choreographer.postFrameCallback(frameCallback);
            return;
        }
        // Idle: hand the frame back and come round again later rather than waking every vsync.
        long delayMs = (1000L / IDLE_FPS) - (1000L / ACTIVE_FPS);
        if (delayMs <= 0L) {
            choreographer.postFrameCallback(frameCallback);
        } else {
            postDelayed(resumeLoop, delayMs);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (!paused) startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stopLoop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        renderer.setViewport(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        renderer.draw(canvas);
    }

    /** Releases the loop. The art provider is owned by the caller and released separately. */
    public void release() {
        stopLoop();
        controller.release();
    }
}
