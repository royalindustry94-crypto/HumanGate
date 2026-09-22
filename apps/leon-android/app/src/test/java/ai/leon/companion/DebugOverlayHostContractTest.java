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
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Source file not found: " + relative);
    }

    @Test
    public void debugOverlayHostStartsNonExportedServiceFromInsideApp() throws Exception {
        String hooks = source("ai/leon/companion/DebugTestHooks.java");
        String activity = source("ai/leon/companion/MainActivity.java");
        String manifest = Files.readString(
                new File("app/src/main/AndroidManifest.xml").toPath(),
                StandardCharsets.UTF_8);

        assertTrue(hooks.contains("EXTRA_START_OVERLAY"));
        assertTrue(activity.contains("DebugTestHooks.shouldStartOverlay"));
        assertTrue(activity.contains("LeonOverlayService.start(this)"));
        assertTrue(manifest.contains("android:name=\".overlay.LeonOverlayService\""));
        assertTrue(manifest.contains("android:exported=\"false\""));
    }
}
