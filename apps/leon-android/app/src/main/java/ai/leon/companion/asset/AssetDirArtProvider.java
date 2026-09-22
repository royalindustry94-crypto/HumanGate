package ai.leon.companion.asset;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Loads Leon's artwork from real image files, one per rig layer, named after its art key:
 * {@code head_bald.png}, {@code lid_l.png}, {@code mouth_a.png} and so on.
 *
 * <p>Two locations are searched, in order:
 * <ol>
 *   <li>{@code <filesDir>/leon-art/} — art pushed onto a device, so a new Leon can be tried without
 *       rebuilding the APK;</li>
 *   <li>{@code assets/leon/} inside the APK — the shipped production art.</li>
 * </ol>
 *
 * <p>This is the production art path. It is intentionally separate from {@link ProceduralLeonArt}: the
 * repository reports which provider each layer came from rather than silently mixing them, so a
 * half-delivered art set is visible instead of looking like finished work.
 */
public final class AssetDirArtProvider implements LeonArtProvider {
    private static final String TAG = "LeonArt";
    public static final String ASSET_DIR = "leon";
    public static final String OVERRIDE_DIR = "leon-art";

    private final Context context;
    private final File overrideDir;
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final Set<String> known;
    private boolean released;

    private AssetDirArtProvider(Context context, File overrideDir, Set<String> known) {
        this.context = context.getApplicationContext();
        this.overrideDir = overrideDir;
        this.known = known;
    }

    /**
     * @return a provider for whatever art files are actually present, or null when neither location
     *         holds any. Returning null is what lets the repository report "no production art yet"
     *         instead of pretending the set exists.
     */
    public static AssetDirArtProvider createIfPresent(Context context) {
        if (context == null) return null;
        Set<String> known = new HashSet<>();

        File dir = new File(context.getFilesDir(), OVERRIDE_DIR);
        if (dir.isDirectory()) {
            String[] names = dir.list();
            if (names != null) {
                for (String name : names) {
                    String key = stripExtension(name);
                    if (key != null) known.add(key);
                }
            }
        }
        try {
            String[] names = context.getAssets().list(ASSET_DIR);
            if (names != null) {
                for (String name : names) {
                    String key = stripExtension(name);
                    if (key != null) known.add(key);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not list assets/" + ASSET_DIR, e);
        }

        if (known.isEmpty()) return null;
        return new AssetDirArtProvider(context, dir, known);
    }

    private static String stripExtension(String name) {
        if (name == null) return null;
        String lower = name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".png")) return name.substring(0, name.length() - 4);
        if (lower.endsWith(".webp")) return name.substring(0, name.length() - 5);
        return null;
    }

    /** Art keys this provider can supply. */
    public Set<String> availableKeys() {
        return java.util.Collections.unmodifiableSet(known);
    }

    @Override
    public String sourceDescription() {
        return "Leon art files (" + known.size() + " layers from " + OVERRIDE_DIR + "/ and assets/"
                + ASSET_DIR + "/)";
    }

    @Override
    public Bitmap bitmapFor(String artKey) {
        if (released || artKey == null || !known.contains(artKey)) return null;
        Bitmap cached = cache.get(artKey);
        if (cached != null && !cached.isRecycled()) return cached;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap bitmap = null;
        for (String extension : new String[]{".png", ".webp"}) {
            File file = new File(overrideDir, artKey + extension);
            if (file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to read " + file, e);
                }
                if (bitmap != null) break;
            }
        }
        if (bitmap == null) {
            for (String extension : new String[]{".png", ".webp"}) {
                try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + artKey + extension)) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                } catch (IOException ignored) {
                    // Missing extension variant; try the next one.
                }
                if (bitmap != null) break;
            }
        }
        if (bitmap == null) {
            Log.w(TAG, "Art key '" + artKey + "' was listed but could not be decoded");
            return null;
        }
        cache.put(artKey, bitmap);
        return bitmap;
    }

    @Override
    public void release() {
        released = true;
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
        cache.clear();
    }
}
