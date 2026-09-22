package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import ai.leon.companion.rig.Bone;
import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Mat2D;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;

import org.junit.Test;

/** The skeleton's topology and transform inheritance — the foundation everything else rests on. */
public class RigTest {
    private static final float EPS = 0.001f;

    @Test
    public void leonRigBuildsWithBonesAndLayers() {
        Rig rig = LeonRig.build();
        assertEquals(27, rig.bones().size());
        assertEquals(45, rig.parts().size());
        assertEquals(LeonRig.DESIGN_W, rig.designWidth, EPS);
        assertEquals(LeonRig.DESIGN_H, rig.designHeight, EPS);
    }

    @Test
    public void bonesAreStoredParentBeforeChild() {
        Rig rig = LeonRig.build();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Bone bone : rig.bones()) {
            if (bone.parent != null && !seen.contains(bone.parent.name)) {
                fail("bone " + bone.name + " appears before its parent " + bone.parent.name);
            }
            seen.add(bone.name);
        }
    }

    @Test
    public void partsAreStoredInAscendingDrawOrder() {
        Rig rig = LeonRig.build();
        int previous = Integer.MIN_VALUE;
        for (RigPart part : rig.parts()) {
            assertTrue("parts must be sorted back to front", part.z >= previous);
            previous = part.z;
        }
    }

    @Test
    public void builderRejectsAChildDeclaredBeforeItsParent() {
        try {
            new Rig.Builder()
                    .root("root", 0f, 0f)
                    .bone("hand", "forearm", 0f, 0f);
            fail("expected the builder to reject a forward parent reference");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("forearm"));
        }
    }

    @Test
    public void builderRejectsAPartOnAnUnknownBone() {
        try {
            new Rig.Builder().root("root", 0f, 0f)
                    .part("ghost", "ghost", "nope", 0f, 0f, 10f, 10f, 0);
            fail("expected the builder to reject an unknown bone");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("nope"));
        }
    }

    @Test
    public void rotatingAParentCarriesItsChildren() {
        Rig rig = LeonRig.build();
        rig.resetAnimation();
        rig.solve();
        Bone jaw = rig.bone(LeonRig.Bones.JAW);
        float restX = jaw.world().mapX(0f, 0f);
        float restY = jaw.world().mapY(0f, 0f);

        // Rotate only the head. The jaw is a child, so it must move without being touched.
        rig.resetAnimation();
        rig.bone(LeonRig.Bones.HEAD).rotationDeg = 30f;
        rig.solve();
        float turnedX = jaw.world().mapX(0f, 0f);
        float turnedY = jaw.world().mapY(0f, 0f);

        assertTrue("the jaw should follow a head rotation",
                Math.hypot(turnedX - restX, turnedY - restY) > 5f);
    }

    @Test
    public void everyLayerStaysInsideTheDesignBoxAtRest() {
        Rig rig = LeonRig.build();
        rig.resetAnimation();
        rig.solve();
        for (RigPart part : rig.parts()) {
            // The aura is a deliberate soft glow that may bleed; every character layer must fit.
            if (LeonRig.Parts.AURA.equals(part.name)) continue;
            Mat2D m = part.world();
            float[] xs = {
                    m.mapX(0f, 0f), m.mapX(part.width, 0f),
                    m.mapX(0f, part.height), m.mapX(part.width, part.height)};
            float[] ys = {
                    m.mapY(0f, 0f), m.mapY(part.width, 0f),
                    m.mapY(0f, part.height), m.mapY(part.width, part.height)};
            for (int i = 0; i < 4; i++) {
                assertTrue(part.name + " x=" + xs[i] + " is outside the design box",
                        xs[i] >= -1f && xs[i] <= rig.designWidth + 1f);
                assertTrue(part.name + " y=" + ys[i] + " is outside the design box",
                        ys[i] >= -1f && ys[i] <= rig.designHeight + 1f);
            }
        }
    }

    @Test
    public void headSitsAboveTheNeckWhichSitsAboveTheChest() {
        Rig rig = LeonRig.build();
        rig.resetAnimation();
        rig.solve();
        float head = rig.bone(LeonRig.Bones.HEAD).world().mapY(0f, 0f);
        float neck = rig.bone(LeonRig.Bones.NECK).world().mapY(0f, 0f);
        float chest = rig.bone(LeonRig.Bones.CHEST).world().mapY(0f, 0f);
        float hips = rig.bone(LeonRig.Bones.HIPS).world().mapY(0f, 0f);
        assertTrue("head above neck", head < neck);
        assertTrue("neck above chest", neck < chest);
        assertTrue("chest above hips", chest < hips);
    }

    @Test
    public void theMouthSwapGroupHoldsEveryVisemeLayer() {
        Rig rig = LeonRig.build();
        assertEquals(10, rig.group(LeonRig.Groups.MOUTH).size());
        for (RigPart part : rig.group(LeonRig.Groups.MOUTH)) {
            assertEquals(LeonRig.Bones.JAW, part.bone.name);
        }
    }

    @Test
    public void skinAndTattooLayersDrawBehindTheHoodie() {
        Rig rig = LeonRig.build();
        int hoodie = rig.part(LeonRig.Parts.TORSO).z;
        assertTrue(rig.part(LeonRig.Parts.NECK).z < hoodie);
        assertTrue(rig.part(LeonRig.Parts.NECK_TATTOO).z < hoodie);
        assertTrue(rig.part(LeonRig.Parts.CHEST_TATTOO).z < hoodie);
        // The necklace hangs over the garment, so it must draw in front of it.
        assertTrue(rig.part(LeonRig.Parts.NECKLACE).z > hoodie);
    }

    @Test
    public void matrixConcatMatchesManualComposition() {
        Mat2D parent = new Mat2D().setTransform(10f, 20f, 90f, 1f, 1f, 0f, 0f);
        Mat2D child = new Mat2D().setTransform(5f, 0f, 0f, 1f, 1f, 0f, 0f);
        Mat2D out = Mat2D.concat(parent, child, new Mat2D());
        // A 90-degree parent turns the child's +5 x offset into +5 y.
        assertEquals(10f, out.mapX(0f, 0f), 0.01f);
        assertEquals(25f, out.mapY(0f, 0f), 0.01f);
    }

    @Test
    public void matrixValuesAreExportedInAndroidRowMajorOrder() {
        Mat2D m = new Mat2D().setTransform(3f, 7f, 0f, 2f, 5f, 0f, 0f);
        float[] values = new float[9];
        m.toMatrixValues(values);
        assertEquals(2f, values[0], EPS);
        assertEquals(0f, values[1], EPS);
        assertEquals(3f, values[2], EPS);
        assertEquals(0f, values[3], EPS);
        assertEquals(5f, values[4], EPS);
        assertEquals(7f, values[5], EPS);
        assertEquals(1f, values[8], EPS);
    }

    @Test
    public void unknownLookupsFailLoudlyButFindIsNullSafe() {
        Rig rig = LeonRig.build();
        assertNotNull(rig.findBone(LeonRig.Bones.HEAD));
        assertNull(rig.findBone("no_such_bone"));
        try {
            rig.bone("no_such_bone");
            fail("expected an exception for an unknown bone");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no_such_bone"));
        }
    }
}
