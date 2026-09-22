package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ProductionAssetContractTest {
    private static File generated(String name) {
        File[] candidates = {
                new File("app/build/generated/leonAssets/leon/production/" + name),
                new File("build/generated/leonAssets/leon/production/" + name),
                new File("apps/leon-android/app/build/generated/leonAssets/leon/production/" + name)
        };
        for (File file : candidates) if (file.isFile()) return file;
        throw new AssertionError("Generated Leon asset not found: " + name);
    }

    private static String manifest() throws Exception {
        return new String(Files.readAllBytes(generated("manifest.json").toPath()),
                StandardCharsets.UTF_8);
    }

    private static double number(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\"" + key + "\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)")
                .matcher(json);
        if (!m.find()) throw new AssertionError("Missing manifest key " + key);
        return Double.parseDouble(m.group(1));
    }

    @Test
    public void generatedProductionMasterIsCompleteAndPinned() throws Exception {
        String json = manifest();
        assertEquals(360, (int) number(json, "source_width"));
        assertEquals(640, (int) number(json, "source_height"));
        assertEquals(16, (int) number(json, "mesh_cols"));
        assertEquals(28, (int) number(json, "mesh_rows"));
        assertEquals(20f, (float) number(json, "crown_y"), 0.01f);
        assertEquals(619f, (float) number(json, "sole_y"), 0.01f);
        assertEquals(180f, (float) number(json, "centre_x"), 0.01f);
        assertTrue(number(json, "bottom") >= 619);
        assertTrue(generated("leon.png").length() > 20_000L);
    }
}
