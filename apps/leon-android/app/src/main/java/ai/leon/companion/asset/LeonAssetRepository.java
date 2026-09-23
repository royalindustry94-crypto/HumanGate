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
 * <p>Order of preference per layer: an individual art file, then a slice of the single photo or
 * render source, then the built-in drawing. What the repository will
 * not do is quietly present a mixed or incomplete set as finished: {@link #report()} names the
 * source of every layer and lists any the production set is missing, and the control centre shows
 * that report. A partially delivered Leon therefore looks partially delivered.
 */
public final class LeonAssetRepository implements LeonArtProvider {
    private static final String TAG = "LeonArt";

    private final PhotoLayerArtProvider photo;
    private final AssetDirArtProvider custom;
    private final ProceduralLeonArt builtIn;
    private final List<String> missingFromProduction = new ArrayList<>();
    private final Set<String> requiredKeys = new LinkedHashSet<>();
    private boolean released;

    public LeonAssetRepository(Context context, Rig rig, float artScale) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        for (RigPart part : rig.parts()) requiredKeys.add(part.artKey);

        this.photo = PhotoLayerArtProvider.createIfPresent(context, rig);
        this.custom = AssetDirArtProvider.createIfPresent(context);
        this.builtIn = new ProceduralLeonArt(rig, artScale);

        Set<String> available = custom == null
                ? java.util.Collections.<String>emptySet() : custom.availableKeys();
        for (String key : requiredKeys) {
            boolean covered = available.contains(key)
                    || (photo != null && PhotoLayerArtProvider.coversKey(key));
            if (!covered) missingFromProduction.add(key);
        }
        Log.i(TAG, report());
    }

    /** True once every rig layer is covered by custom art files rather than the built-in art. */
    public boolean hasCompleteProductionArt() {
        return (custom != null || photo != null) && missingFromProduction.isEmpty();
    }

    /** Layers no custom art file supplies, which the built-in artwork draws instead. */
    public List<String> missingFromProduction() {
        return java.util.Collections.unmodifiableList(missingFromProduction);
    }

    public int requiredLayerCount() {
        return requiredKeys.size();
    }

    /** Bitmap memory held by Leon's built-in artwork. Shown in the control centre's diagnostics. */
    public long developmentArtBytes() {
        return builtIn.allocatedBytes();
    }

    /** One-line summary for logs and the control centre. */
    public String report() {
        int supplied = requiredKeys.size() - missingFromProduction.size();
        if (custom == null && photo == null) {
            return "Leon art: all " + requiredKeys.size() + " layers from the built-in artwork.";
        }
        if (missingFromProduction.isEmpty()) {
            return "Leon art: all " + requiredKeys.size() + " layers from "
                    + (photo != null ? "the photo source" : "custom art files") + ".";
        }
        String from = photo != null ? "the photo source" : "custom art files";
        return "Leon art: " + supplied + "/" + requiredKeys.size() + " layers from " + from + "; "
                + missingFromProduction.size() + " from the built-in artwork ("
                + summariseMissing() + ").";
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
        // Per-layer art files win, then the photo source, then the built-in drawing.
        if (custom != null) {
            Bitmap b = custom.bitmapFor(artKey);
            if (b != null) return b;
        }
        if (photo != null) {
            Bitmap b = photo.bitmapFor(artKey);
            if (b != null) return b;
        }
        return builtIn.bitmapFor(artKey);
    }

    @Override
    public void release() {
        released = true;
        if (custom != null) custom.release();
        if (photo != null) photo.release();
        builtIn.release();
    }
}
