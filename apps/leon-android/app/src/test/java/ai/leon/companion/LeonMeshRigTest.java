package ai.leon.companion;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.render.LeonMeshRig;

import org.junit.Test;

public class LeonMeshRigTest {
    @Test
    public void everyVertexWeightSumsToOne() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        for (LeonMeshRig.VertexWeights weights : mesh.weights()) {
            assertEquals(1f, weights.totalWeight(), 0.001f);
            assertTrue(weights.allFinite());
        }
    }

    @Test
    public void restPoseVerticesAreCanonical() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        assertArrayEquals(mesh.canonicalVertices(), mesh.deformIdentityForTest(), 0.0001f);
    }

    @Test
    public void fullMeshHasExpectedVertexCount() {
        LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
        assertEquals((16 + 1) * (28 + 1) * 2, mesh.canonicalVertices().length);
    }
}
