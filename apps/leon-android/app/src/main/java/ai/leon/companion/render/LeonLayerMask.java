package ai.leon.companion.render;

import ai.leon.companion.asset.PhotoAlignment;

/**
 * Which texels of the production texture belong to the arm layer, found from the texture itself.
 *
 * <p>Below the armpit, Leon's clearly visible pixels form three separate regions: the body with
 * its legs, and each arm with its hand. The gap between a thumb and the hip is as little as ~1
 * design unit once anti-aliased fringe is counted, which no hand-measured boundary line gets
 * right on every row: a line inside the fringe leaves faint thumb pixels behind on the hip when
 * the hand moves. Connectivity is exact, and still holds if the artwork changes.
 *
 * <ol>
 *   <li>Label 8-connected regions of texels with alpha >= {@link #SOLID_ALPHA}, in the rows from
 *       just above {@link LeonMeshRig#ARM_LAYER_TOP_Y} down.</li>
 *   <li>The largest region is the body and legs; every other region is arm.</li>
 *   <li>Fainter texels (the anti-aliased halo) take the label of their nearest labelled texel,
 *       spreading outwards one ring at a time.</li>
 * </ol>
 *
 * <p>No Android types, so the renderer and the JVM tests run the same code.
 */
public final class LeonLayerMask {
    /**
     * Separation threshold. The arms separate from the body from alpha 5 upwards on the
     * production texture; 16 (~6% opacity) leaves margin and yields exactly three regions.
     */
    static final int SOLID_ALPHA = 16;

    private LeonLayerMask() {}

    /**
     * @param alpha row-major alpha (0-255) of the full texture
     * @return per texel, true when the arm layer draws it
     */
    public static boolean[] armTexels(int[] alpha, int width, int height, PhotoAlignment alignment) {
        int size = width * height;
        if (alpha.length != size) throw new IllegalArgumentException("alpha size mismatch");
        int top = firstRowAtOrBelow(LeonMeshRig.ARM_LAYER_TOP_Y - LeonMeshRig.ARM_LAYER_OVERLAP,
                height, alignment);

        int[] label = new int[size];
        java.util.Arrays.fill(label, -1);
        int[] queue = new int[size];
        java.util.List<Integer> regionSizes = new java.util.ArrayList<>();

        for (int start = top * width; start < size; start++) {
            if (label[start] != -1 || alpha[start] < SOLID_ALPHA) continue;
            int id = regionSizes.size();
            int head = 0;
            int tail = 0;
            label[start] = id;
            queue[tail++] = start;
            while (head < tail) {
                int t = queue[head++];
                int x = t % width;
                int y = t / width;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < top || ny >= height) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        if ((dx | dy) == 0 || nx < 0 || nx >= width) continue;
                        int n = ny * width + nx;
                        if (label[n] != -1 || alpha[n] < SOLID_ALPHA) continue;
                        label[n] = id;
                        queue[tail++] = n;
                    }
                }
            }
            regionSizes.add(tail);
        }
        if (regionSizes.isEmpty()) return new boolean[size];

        int body = 0;
        for (int i = 1; i < regionSizes.size(); i++) {
            if (regionSizes.get(i) > regionSizes.get(body)) body = i;
        }

        // Faint halo: grow every labelled region into the texels below SOLID_ALPHA, ring by ring,
        // so each faint texel follows whichever part it fringes.
        int head = 0;
        int tail = 0;
        for (int t = top * width; t < size; t++) if (label[t] != -1) queue[tail++] = t;
        while (head < tail) {
            int t = queue[head++];
            int x = t % width;
            int y = t / width;
            for (int dy = -1; dy <= 1; dy++) {
                int ny = y + dy;
                if (ny < top || ny >= height) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    if ((dx | dy) == 0 || nx < 0 || nx >= width) continue;
                    int n = ny * width + nx;
                    if (label[n] != -1 || alpha[n] == 0) continue;
                    label[n] = label[t];
                    queue[tail++] = n;
                }
            }
        }

        boolean[] arm = new boolean[size];
        for (int t = top * width; t < size; t++) arm[t] = label[t] != -1 && label[t] != body;
        return arm;
    }

    /** Whether {@code layer} draws texel {@code t}, given {@link #armTexels}' result. */
    public static boolean covers(LeonMeshRig.Layer layer, boolean[] armTexels, int t, int width,
                                 PhotoAlignment alignment) {
        if (layer == LeonMeshRig.Layer.ARMS) return armTexels[t];
        // The body keeps arm texels above the cut: the overlap band is drawn by both layers.
        return !armTexels[t] || designY(t / width, alignment) < LeonMeshRig.ARM_LAYER_TOP_Y;
    }

    static float designY(int row, PhotoAlignment alignment) {
        return (row + 0.5f - alignment.originY) / alignment.scale;
    }

    private static int firstRowAtOrBelow(float designYLimit, int height, PhotoAlignment alignment) {
        for (int row = 0; row < height; row++) {
            if (designY(row, alignment) >= designYLimit) return row;
        }
        return height;
    }
}
