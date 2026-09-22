package ai.leon.companion.asset;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides which artwork the renderer draws, and says so plainly.
 *
 * <p>Production art files win over the bundled development rig, per layer. What the repository will
 * not do is quietly present a mixed or incomplete set as finished: {@link #report()} names the
 * source of every layer and lists any the production set is missing, and the control centre shows
 * that report. A partially delivered Leon therefore looks partially delivered.
 */
public final class LeonAssetRepository implements LeonArtProvider {
    private static final String TAG = "LeonArt";

    private final AssetDirArtProvider production;
    private final DevRigArt development;
    private final List<String> missingFromProduction = new ArrayList<>();
    private final Set<String> requiredKeys = new LinkedHashSet<>();
    private boolean released;

    public LeonAssetRepository(Context context, Rig rig, float artScale) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        for (RigPart part : rig.parts()) requiredKeys.add(part.artKey);

        this.production = AssetDirArtProvider.createIfPresent(context);
        this.development = new DevRigArt(rig, artScale);

        if (production == null) {
            missingFromProduction.addAll(requiredKeys);
        } else {
            Set<String> available = production.availableKeys();
            for (String key : requiredKeys) {
                if (!available.contains(key)) missingFromProduction.add(key);
            }
        }
        Log.i(TAG, report());
    }

    /** True once every rig layer is covered by real art files. */
    public boolean hasCompleteProductionArt() {
        return production != null && missingFromProduction.isEmpty();
    }

    /** Layers the production art set does not yet supply. */
    public List<String> missingFromProduction() {
        return java.util.Collections.unmodifiableList(missingFromProduction);
    }

    public int requiredLayerCount() {
        return requiredKeys.size();
    }

    /** One-line summary for logs and the control centre. */
    public String report() {
        int supplied = requiredKeys.size() - missingFromProduction.size();
        if (production == null) {
            return "Leon art: 0/" + requiredKeys.size() + " layers from production files; "
                    + "all layers drawn by the bundled development rig.";
        }
        if (missingFromProduction.isEmpty()) {
            return "Leon art: " + requiredKeys.size() + "/" + requiredKeys.size()
                    + " layers from production files.";
        }
        return "Leon art: " + supplied + "/" + requiredKeys.size()
                + " layers from production files; " + missingFromProduction.size()
                + " still drawn by the development rig (" + summariseMissing() + ").";
    }

    private String summariseMissing() {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(6, missingFromProduction.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            sb.append(missingFromProduction.get(i));
        }
        if (missingFromProduction.size() > limit) {
            sb.append(", +").append(missingFromProduction.size() - limit).append(" more");
        }
        return sb.toString();
    }

    @Override
    public String sourceDescription() {
        return report();
    }

    @Override
    public Bitmap bitmapFor(String artKey) {
        if (released) return null;
        if (production != null) {
            Bitmap b = production.bitmapFor(artKey);
            if (b != null) return b;
        }
        return development.bitmapFor(artKey);
    }

    @Override
    public void release() {
        released = true;
        if (production != null) production.release();
        development.release();
    }
}
