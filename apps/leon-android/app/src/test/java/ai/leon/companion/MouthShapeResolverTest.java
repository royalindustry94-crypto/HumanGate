package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonPose;
import ai.leon.companion.anim.MouthShapeResolver;
import ai.leon.companion.anim.Viseme;

import org.junit.Test;

/** Each viseme must resolve back to the mouth layer that depicts it. */
public class MouthShapeResolverTest {
    private int dominantFor(Viseme viseme) {
        LeonPose pose = new LeonPose();
        viseme.applyTo(pose, 1f);
        MouthShapeResolver resolver = new MouthShapeResolver();
        resolver.resolve(pose);
        return resolver.dominant();
    }

    @Test
    public void everyVisemeSelectsItsOwnLayer() {
        assertEquals(MouthShapeResolver.CLOSED, dominantFor(Viseme.CLOSED));
        assertEquals(MouthShapeResolver.NEUTRAL_OPEN, dominantFor(Viseme.NEUTRAL_OPEN));
        assertEquals(MouthShapeResolver.A, dominantFor(Viseme.A));
        assertEquals(MouthShapeResolver.E, dominantFor(Viseme.E));
        assertEquals(MouthShapeResolver.O, dominantFor(Viseme.O));
        assertEquals(MouthShapeResolver.U, dominantFor(Viseme.U));
        assertEquals(MouthShapeResolver.MBP, dominantFor(Viseme.MBP));
        assertEquals(MouthShapeResolver.FV, dominantFor(Viseme.FV));
    }

    @Test
    public void weightsAreNormalised() {
        for (Viseme viseme : Viseme.values()) {
            LeonPose pose = new LeonPose();
            viseme.applyTo(pose, 0.7f);
            MouthShapeResolver resolver = new MouthShapeResolver();
            float[] weights = resolver.resolve(pose);
            float total = 0f;
            for (float w : weights) {
                assertTrue(viseme + " weights must not be negative", w >= 0f);
                total += w;
            }
            assertEquals(viseme + " weights must sum to 1", 1f, total, 0.002f);
        }
    }

    @Test
    public void aSilentSmileSelectsTheSmileLayer() {
        LeonPose pose = new LeonPose();
        pose.set(ai.leon.companion.anim.LeonChannel.MOUTH_SMILE, 1f);
        MouthShapeResolver resolver = new MouthShapeResolver();
        resolver.resolve(pose);
        assertEquals(MouthShapeResolver.SMILE, resolver.dominant());
    }

    @Test
    public void aSilentFrownSelectsTheFrownLayer() {
        LeonPose pose = new LeonPose();
        pose.set(ai.leon.companion.anim.LeonChannel.MOUTH_FROWN, 1f);
        MouthShapeResolver resolver = new MouthShapeResolver();
        resolver.resolve(pose);
        assertEquals(MouthShapeResolver.FROWN, resolver.dominant());
    }

    @Test
    public void visemeNamesFromCommonPhonemeSetsAreRecognised() {
        assertEquals(Viseme.CLOSED, Viseme.fromName("sil"));
        assertEquals(Viseme.A, Viseme.fromName("AA"));
        assertEquals(Viseme.E, Viseme.fromName("iy"));
        assertEquals(Viseme.U, Viseme.fromName("UW"));
        assertEquals(Viseme.MBP, Viseme.fromName("p"));
        assertEquals(Viseme.FV, Viseme.fromName("V"));
        org.junit.Assert.assertNull(Viseme.fromName("zzz"));
    }

    @Test
    public void intensityScalesTheMouthChannelsLinearly() {
        LeonPose full = new LeonPose();
        LeonPose half = new LeonPose();
        Viseme.A.applyTo(full, 1f);
        Viseme.A.applyTo(half, 0.5f);
        assertEquals(full.get(ai.leon.companion.anim.LeonChannel.JAW_DROP) * 0.5f,
                half.get(ai.leon.companion.anim.LeonChannel.JAW_DROP), 0.0001f);
    }
}
