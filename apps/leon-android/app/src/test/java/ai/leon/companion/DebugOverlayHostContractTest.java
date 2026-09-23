package ai.leon.companion;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class DebugOverlayHostContractTest {
    private static String source(String relative) throws Exception {
        File[] candidates = {
                new File("app/src/main/java/" + relative),
                new File("src/main/java/" + relative),
                new File("apps/leon-android/app/src/main/java/" + relative)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Source file not found: " + relative);
    }

    private static String manifestSource() throws Exception {
        File[] candidates = {
                new File("src/main/AndroidManifest.xml"),
                new File("app/src/main/AndroidManifest.xml"),
                new File("apps/leon-android/app/src/main/AndroidManifest.xml")
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("AndroidManifest.xml not found");
    }

    @Test
    public void visualTestStateIsRequestedAfterAnimationControllerExists() throws Exception {
        String activity = source("ai/leon/companion/MainActivity.java");
        int controllerIndex = activity.indexOf("animation = new LeonAnimationController");
        int requestIndex = activity.indexOf("runtime.states().request(requested)");
        int actionIndex = activity.indexOf("DebugTestHooks.applyAction");

        assertTrue("visual test controller must exist before state request", controllerIndex >= 0);
        assertTrue("state request must occur after controller construction", requestIndex > controllerIndex);
        assertTrue("deterministic action must occur after state request", actionIndex > requestIndex);
    }

    @Test
    public void debugOverlayHostStartsNonExportedServiceFromInsideApp() throws Exception {
        String hooks = source("ai/leon/companion/DebugTestHooks.java");
        String activity = source("ai/leon/companion/MainActivity.java");
        String manifest = manifestSource();

        assertTrue(hooks.contains("EXTRA_START_OVERLAY"));
        assertTrue(activity.contains("DebugTestHooks.shouldStartOverlay"));
        assertTrue(activity.contains("LeonOverlayService.start(this)"));
        assertTrue(manifest.contains("android:name=\".overlay.LeonOverlayService\""));
        assertTrue(manifest.contains("android:exported=\"false\""));
    }
}
