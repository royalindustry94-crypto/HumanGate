package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.asset.PhotoAlignment;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Legacy alignment contract retained until ProductionAssetContractTest replaces this class.
 *
 * <p>This exists because an earlier build used landmarks measured against a different-sized image:
 * CI was green, but the phone showed only Leon's eyes and mouth. The test pins the source dimensions,
 * landmarks and design mapping together so that class of regression cannot ship again.
 */
public class PhotoAssetContractTest {
    private static File asset(String name) {
        File[] candidates = {
                new File("app/src/main/assets/leon/" + name),
                new File("src/main/assets/leon/" + name),
                new File("apps/leon-android/app/src/main/assets/leon/" + name)
        };
        for (File file : candidates) if (file.isFile()) return file;
        throw new AssertionError("Leon asset not found: " + name
                + " (working dir " + new File(".").getAbsolutePath() + ")");
    }

    @Test
    public void landmarksMatchTheProductionCanvasExactly() throws Exception {
        // leon-front.txt is the build's only hand-authored input besides the reference photo:
        // build_leon_production.py and LeonMeshRig both place Leon on the 360x640 canvas by it.
        String[] values = new String(Files.readAllBytes(asset("leon-front.txt").toPath()),
                StandardCharsets.UTF_8).trim().split("[,\\s]+");
        assertTrue(values.length >= 3);
        float crownY = Float.parseFloat(values[0]);
        float soleY = Float.parseFloat(values[1]);
        float centreX = Float.parseFloat(values[2]);

        assertEquals(20f, crownY, 0.01f);
        assertEquals(619f, soleY, 0.01f);
        assertEquals(180f, centreX, 0.01f);
        assertTrue(crownY >= 0f && crownY < soleY && soleY < 640);
        assertTrue(centreX > 0f && centreX < 360);

        PhotoAlignment alignment = PhotoAlignment.fromLandmarks(crownY, soleY, centreX);
        assertEquals(crownY, alignment.sourceY(PhotoAlignment.DESIGN_CROWN_Y), 0.01f);
        assertEquals(soleY, alignment.sourceY(PhotoAlignment.DESIGN_SOLE_Y), 0.01f);
        assertEquals(centreX, alignment.sourceX(PhotoAlignment.DESIGN_CENTRE_X), 0.01f);
        assertEquals(0.87063956f, alignment.scale, 0.0001f);
    }
}
