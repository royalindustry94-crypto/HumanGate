package ai.leon.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LeonPuppetRendererContractTest {
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

    @Test
    public void productionViewUsesPuppetRendererAndOneContinuousTexture() throws Exception {
        String view = source("ai/leon/companion/render/LeonCharacterView.java");
        String puppet = source("ai/leon/companion/render/LeonPuppetRenderer.java");

        assertTrue(view.contains("ProductionLeonTexture"));
        assertTrue(view.contains("LeonPuppetRenderer"));
        assertFalse(view.contains("LeonArtProvider"));
        assertTrue(puppet.contains("drawBitmapMesh"));
        assertFalse(puppet.contains("bitmapFor("));
    }

    @Test
    public void activityAndOverlayDoNotUseLegacyArtRepository() throws Exception {
        String activity = source("ai/leon/companion/MainActivity.java");
        String overlay = source("ai/leon/companion/overlay/LeonOverlayService.java");

        for (String production : new String[]{activity, overlay}) {
            assertTrue(production.contains("ProductionLeonTexture"));
            assertFalse(production.contains("LeonAssetRepository"));
            assertFalse(production.contains("PhotoLayerArtProvider"));
            assertFalse(production.contains("ProceduralLeonArt"));
        }
    }
}
