package ai.leon.companion.anim;

import java.util.ArrayList;
import java.util.List;

/**
 * Fans one viseme stream out to every attached {@link LeonLipSyncController}, so the overlay and the
 * control centre's preview both speak from a single source. A future audio pipeline publishes here
 * once and does not need to know how many Leons are on screen.
 */
public final class LeonVisemeBus implements LeonVisemeSink {
    private final List<LeonLipSyncController> targets = new ArrayList<>(2);

    public void attach(LeonLipSyncController controller) {
        if (controller != null && !targets.contains(controller)) targets.add(controller);
    }

    public void detach(LeonLipSyncController controller) {
        targets.remove(controller);
    }

    public int attachedCount() {
        return targets.size();
    }

    @Override
    public void onViseme(Viseme viseme, float intensity, long durationMs) {
        for (int i = 0; i < targets.size(); i++) {
            targets.get(i).onViseme(viseme, intensity, durationMs);
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < targets.size(); i++) {
            targets.get(i).stop();
        }
    }
}
