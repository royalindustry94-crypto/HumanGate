package ai.leon.companion.asset;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import ai.leon.companion.rig.LeonRig;
import ai.leon.companion.rig.Rig;
import ai.leon.companion.rig.RigPart;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws Leon himself, procedurally: one bitmap per logical art key, built from his character
 * specification rather than loaded from image files.
 *
 * <p>This is the shipped character, not a placeholder. Every layer is a genuinely separate bitmap on
 * its own bone, so every motion the rig supports — blinking, breathing, the head turn, per-side
 * shoulders and arms, the viseme set — is real. Drawing him in code rather than shipping PNGs keeps
 * the APK small, makes him resolution-independent, and puts his appearance under version control.
 *
 * <p>Custom artwork can still replace him layer by layer through {@link AssetDirArtProvider}; see
 * {@code docs/LEON_CHARACTER_ASSET_SPEC.md}.
 *
 * <p>Each key is drawn once, on demand, at the layer's own design size multiplied by
 * {@code artScale}, and cached. Author coordinates are design units; the canvas is pre-scaled.
 *
 * <p>The three-stripe sleeve detail is generic. No brand logo or wordmark is reproduced.
 */
public final class ProceduralLeonArt implements LeonArtProvider {
    /** Cap so an unusual density cannot allocate an unreasonable amount of bitmap memory. */
    private static final float MAX_SCALE = 2.5f;

    private final Map<String, float[]> sizes = new HashMap<>();
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final float artScale;
    private boolean released;

    public ProceduralLeonArt(Rig rig, float artScale) {
        if (rig == null) throw new IllegalArgumentException("rig required");
        this.artScale = Math.max(0.5f, Math.min(artScale, MAX_SCALE));
        for (RigPart part : rig.parts()) {
            if (!sizes.containsKey(part.artKey)) {
                sizes.put(part.artKey, new float[]{part.width, part.height});
            }
        }
    }

    @Override
    public String sourceDescription() {
        return "Built-in Leon artwork (procedural, " + sizes.size() + " layers @" + artScale + "x)";
    }

    @Override
    public Bitmap bitmapFor(String artKey) {
        if (released || artKey == null) return null;
        Bitmap cached = cache.get(artKey);
        if (cached != null && !cached.isRecycled()) return cached;

        float[] size = sizes.get(artKey);
        if (size == null) return null;
        // The aura is a soft gradient, so it can be rasterised well below its display size.
        float scale = LeonRig.Art.AURA.equals(artKey) ? Math.min(artScale, 0.4f) : artScale;
        int w = Math.max(1, Math.round(size[0] * scale));
        int h = Math.max(1, Math.round(size[1] * scale));

        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(w / size[0], h / size[1]);
        draw(artKey, canvas, size[0], size[1]);
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

    /** Total bitmap memory currently held, for the control centre's diagnostics. */
    public long allocatedBytes() {
        long total = 0;
        for (Bitmap b : cache.values()) {
            if (b != null && !b.isRecycled()) total += (long) b.getWidth() * b.getHeight() * 4L;
        }
        return total;
    }

    // ------------------------------------------------------------------ dispatch

    private void draw(String key, Canvas c, float w, float h) {
        switch (key) {
            case LeonRig.Art.AURA: aura(c, w, h); break;
            case LeonRig.Art.PHOTO_BACKFILL: break; // photo-only continuity layer
            case LeonRig.Art.HOOD_DOWN: hoodDown(c, w, h); break;
            case LeonRig.Art.PANT_LEG_L:
            case LeonRig.Art.PANT_LEG_R:
                pantLeg(c, w, h, true); break;
            case LeonRig.Art.PANT_SHIN_L:
            case LeonRig.Art.PANT_SHIN_R:
                pantLeg(c, w, h, false); break;
            case LeonRig.Art.SNEAKER_L: sneaker(c, w, h, false); break;
            case LeonRig.Art.SNEAKER_R: sneaker(c, w, h, true); break;
            case LeonRig.Art.SLEEVE_UPPER_L: sleeve(c, w, h, false); break;
            case LeonRig.Art.SLEEVE_UPPER_R: sleeve(c, w, h, true); break;
            case LeonRig.Art.FOREARM_TATTOO_L: forearm(c, w, h, false); break;
            case LeonRig.Art.FOREARM_TATTOO_R: forearm(c, w, h, true); break;
            case LeonRig.Art.HAND_L: hand(c, w, h, false); break;
            case LeonRig.Art.HAND_R: hand(c, w, h, true); break;
            case LeonRig.Art.HOODIE_BODY: hoodieBody(c, w, h); break;
            case LeonRig.Art.HOODIE_POCKET: hoodiePocket(c, w, h); break;
            case LeonRig.Art.NECK_SKIN: neckSkin(c, w, h); break;
            case LeonRig.Art.NECK_TATTOO: neckTattoo(c, w, h); break;
            case LeonRig.Art.CHEST_TATTOO: chestTattoo(c, w, h); break;
            case LeonRig.Art.NECKLACE: necklace(c, w, h); break;
            case LeonRig.Art.HEAD_BALD: headBald(c, w, h); break;
            case LeonRig.Art.HEAD_TATTOO_WRAP: headTattoo(c, w, h); break;
            case LeonRig.Art.EAR_L: ear(c, w, h, false); break;
            case LeonRig.Art.EAR_R: ear(c, w, h, true); break;
            case LeonRig.Art.EYE_WHITE: eyeWhite(c, w, h); break;
            case LeonRig.Art.IRIS: iris(c, w, h); break;
            case LeonRig.Art.EYELID: eyelid(c, w, h); break;
            case LeonRig.Art.BROW_L: brow(c, w, h, false); break;
            case LeonRig.Art.BROW_R: brow(c, w, h, true); break;
            case LeonRig.Art.NOSE: nose(c, w, h); break;
            case LeonRig.Art.SUNGLASSES: sunglasses(c, w, h); break;
            case LeonRig.Art.EARRING: earring(c, w, h); break;
            case LeonRig.Art.MOUTH_CLOSED: mouthClosed(c, w, h, 0f); break;
            case LeonRig.Art.MOUTH_SMILE: mouthClosed(c, w, h, 1f); break;
            case LeonRig.Art.MOUTH_FROWN: mouthClosed(c, w, h, -1f); break;
            case LeonRig.Art.MOUTH_NEUTRAL_OPEN: mouthOpen(c, w, h, 0.5f, 0.45f, false, false); break;
            case LeonRig.Art.MOUTH_A: mouthOpen(c, w, h, 0.72f, 0.9f, true, true); break;
            case LeonRig.Art.MOUTH_E: mouthOpen(c, w, h, 0.95f, 0.42f, true, true); break;
            case LeonRig.Art.MOUTH_O: mouthOpen(c, w, h, 0.46f, 0.7f, false, false); break;
            case LeonRig.Art.MOUTH_U: mouthOpen(c, w, h, 0.3f, 0.36f, false, false); break;
            case LeonRig.Art.MOUTH_MBP: mouthPressed(c, w, h); break;
            case LeonRig.Art.MOUTH_FV: mouthTeeth(c, w, h); break;
            default:
                // Unknown key: draw nothing rather than invent a shape. The repository reports it.
                break;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    private static Paint stroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Paint vertical(float top, float bottom, int topColor, int bottomColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0f, top, 0f, bottom, topColor, bottomColor, Shader.TileMode.CLAMP));
        return p;
    }

    private static Paint horizontal(float left, float right, int leftColor, int rightColor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(left, 0f, right, 0f, leftColor, rightColor, Shader.TileMode.CLAMP));
        return p;
    }

    /** Mirrors subsequent drawing about the vertical centre line. */
    private static void mirror(Canvas c, float w) {
        c.translate(w, 0f);
        c.scale(-1f, 1f);
    }

    /**
     * A limb with rounded ends: a deltoid-style cap at the top tapering to a rounded wrist. Square
     * limb ends are the single biggest giveaway of programmer art, so nothing here uses them.
     */
    private static Path limb(float w, float h, float topWidth, float bottomWidth, float bulge) {
        float cx = w * 0.5f;
        float tr = topWidth * 0.5f;
        float br = bottomWidth * 0.5f;
        float capTop = tr * 0.82f;
        float capBottom = br * 0.9f;
        Path path = new Path();
        // Rounded top cap.
        path.moveTo(cx - tr, capTop);
        path.cubicTo(cx - tr, capTop * 0.25f, cx - tr * 0.55f, 0f, cx, 0f);
        path.cubicTo(cx + tr * 0.55f, 0f, cx + tr, capTop * 0.25f, cx + tr, capTop);
        // Down the outer edge to the wrist.
        path.cubicTo(cx + tr + bulge, h * 0.42f, cx + br + bulge * 0.35f, h * 0.72f,
                cx + br, h - capBottom);
        // Rounded bottom.
        path.cubicTo(cx + br, h - capBottom * 0.2f, cx + br * 0.5f, h, cx, h);
        path.cubicTo(cx - br * 0.5f, h, cx - br, h - capBottom * 0.2f, cx - br, h - capBottom);
        path.cubicTo(cx - br - bulge * 0.35f, h * 0.72f, cx - tr - bulge, h * 0.42f,
                cx - tr, capTop);
        path.close();
        return path;
    }

    /** Soft rim light down one edge, which is what gives a flat vector shape volume. */
    private static void rimLight(Canvas c, Path shape, float w, float h, boolean fromRight) {
        c.save();
        c.clipPath(shape);
        Paint rim = fromRight
                ? horizontal(w * 0.62f, w, Color.TRANSPARENT, Color.argb(58, 255, 250, 240))
                : horizontal(0f, w * 0.38f, Color.argb(58, 255, 250, 240), Color.TRANSPARENT);
        c.drawRect(0f, 0f, w, h, rim);
        c.restore();
    }

    /** Core shadow on the opposite edge from the rim light. */
    private static void coreShadow(Canvas c, Path shape, float w, float h, int colour) {
        c.save();
        c.clipPath(shape);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.45f, colour, Color.TRANSPARENT));
        c.restore();
    }

    // ------------------------------------------------------------------ head

    private void headBald(Canvas c, float w, float h) {
        float cx = w * 0.5f;
        Path skull = new Path();
        // Broad cranium, strong cheekbones, square jaw tapering to the chin.
        skull.moveTo(cx, 0f);
        skull.cubicTo(w * 0.86f, 0f, w * 0.975f, h * 0.20f, w * 0.965f, h * 0.40f);
        skull.cubicTo(w * 0.955f, h * 0.56f, w * 0.90f, h * 0.68f, w * 0.845f, h * 0.78f);
        skull.cubicTo(w * 0.79f, h * 0.90f, w * 0.68f, h, cx, h);
        skull.cubicTo(w * 0.32f, h, w * 0.21f, h * 0.90f, w * 0.155f, h * 0.78f);
        skull.cubicTo(w * 0.10f, h * 0.68f, w * 0.045f, h * 0.56f, w * 0.035f, h * 0.40f);
        skull.cubicTo(w * 0.025f, h * 0.20f, w * 0.14f, 0f, cx, 0f);
        skull.close();
        c.drawPath(skull, vertical(0f, h, LeonPalette.SKIN_HIGHLIGHT, LeonPalette.SKIN_SHADOW));

        c.save();
        c.clipPath(skull);
        // Light from the upper right: shade the opposite side, then a broad scalp highlight.
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.48f, Color.argb(96, 74, 40, 20), Color.TRANSPARENT));
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new RadialGradient(w * 0.60f, h * 0.15f, w * 0.40f,
                new int[]{Color.argb(112, 255, 246, 234), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, w, h, gloss);
        // Temple hollows and cheekbones, kept soft.
        Paint hollow = fill(Color.argb(34, 92, 50, 28));
        c.drawOval(new RectF(w * 0.03f, h * 0.30f, w * 0.20f, h * 0.52f), hollow);
        c.drawOval(new RectF(w * 0.80f, h * 0.30f, w * 0.97f, h * 0.52f), hollow);
        Paint cheek = fill(Color.argb(30, 196, 104, 74));
        c.drawOval(new RectF(w * 0.08f, h * 0.55f, w * 0.34f, h * 0.70f), cheek);
        c.drawOval(new RectF(w * 0.66f, h * 0.55f, w * 0.92f, h * 0.70f), cheek);
        // Jaw shadow only under the chin, not smeared across the face.
        c.drawRect(0f, h * 0.84f, w, h,
                vertical(h * 0.84f, h, Color.TRANSPARENT, Color.argb(62, 58, 32, 18)));
        c.restore();
    }

    private void headTattoo(Canvas c, float w, float h) {
        // Ink on the SIDES and crown-back only. Nothing crosses the front of the face, because
        // horizontal banding over the forehead reads as hair rather than as a tattoo.
        Paint ink = stroke(Color.argb(168, 30, 42, 64), w * 0.021f);
        Paint inkFine = stroke(Color.argb(126, 32, 44, 66), w * 0.014f);

        for (int side = 0; side < 2; side++) {
            c.save();
            if (side == 1) mirror(c, w);

            // Three sweeps hugging the side of the skull, rising from the temple over the crown.
            for (int i = 0; i < 3; i++) {
                float inset = w * (0.045f + i * 0.032f);
                Path sweep = new Path();
                sweep.moveTo(inset + w * 0.02f, h * 0.92f);
                sweep.cubicTo(inset - w * 0.01f, h * 0.60f,
                        inset + w * 0.03f, h * 0.24f,
                        inset + w * 0.16f, h * 0.06f);
                c.drawPath(sweep, i == 1 ? ink : inkFine);
            }
            // Solid geometric wedge at the temple, the anchor of the piece.
            Path wedge = new Path();
            wedge.moveTo(w * 0.055f, h * 0.44f);
            wedge.lineTo(w * 0.125f, h * 0.36f);
            wedge.lineTo(w * 0.115f, h * 0.60f);
            wedge.lineTo(w * 0.05f, h * 0.64f);
            wedge.close();
            c.drawPath(wedge, fill(Color.argb(124, 30, 42, 62)));
            // Dot cluster trailing behind it.
            Paint dot = fill(Color.argb(120, 30, 42, 62));
            for (int i = 0; i < 3; i++) {
                c.drawCircle(w * (0.145f + i * 0.018f), h * (0.70f + i * 0.055f), w * 0.009f, dot);
            }
            c.restore();
        }

        // A single arc across the very top of the skull, joining the two sides around the back.
        Path crown = new Path();
        crown.moveTo(w * 0.10f, h * 0.20f);
        crown.cubicTo(w * 0.30f, h * 0.01f, w * 0.70f, h * 0.01f, w * 0.90f, h * 0.20f);
        c.drawPath(crown, ink);
    }

    private void ear(Canvas c, float w, float h, boolean flip) {
        c.save();
        if (flip) mirror(c, w);
        Path outer = new Path();
        outer.moveTo(w * 0.72f, h * 0.06f);
        outer.cubicTo(w * 0.18f, h * 0.02f, w * 0.02f, h * 0.36f, w * 0.14f, h * 0.66f);
        outer.cubicTo(w * 0.26f, h * 0.96f, w * 0.62f, h, w * 0.80f, h * 0.86f);
        outer.close();
        c.drawPath(outer, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));
        Path curl = new Path();
        curl.moveTo(w * 0.62f, h * 0.24f);
        curl.cubicTo(w * 0.30f, h * 0.30f, w * 0.30f, h * 0.62f, w * 0.56f, h * 0.72f);
        c.drawPath(curl, stroke(Color.argb(92, 118, 62, 36), w * 0.10f));
        c.restore();
    }

    private void eyeWhite(Canvas c, float w, float h) {
        Path almond = new Path();
        almond.moveTo(0f, h * 0.50f);
        almond.cubicTo(w * 0.16f, h * 0.02f, w * 0.84f, h * 0.02f, w, h * 0.46f);
        almond.cubicTo(w * 0.82f, h, w * 0.18f, h, 0f, h * 0.50f);
        almond.close();
        c.drawPath(almond, fill(LeonPalette.EYE_WHITE));
        c.save();
        c.clipPath(almond);
        c.drawRect(0f, 0f, w, h, vertical(0f, h * 0.55f, Color.argb(88, 78, 50, 34), Color.TRANSPARENT));
        c.restore();
        Paint lash = stroke(Color.argb(225, 24, 20, 19), h * 0.15f);
        Path lashPath = new Path();
        lashPath.moveTo(0f, h * 0.48f);
        lashPath.cubicTo(w * 0.18f, h * 0.01f, w * 0.82f, h * 0.01f, w, h * 0.44f);
        c.drawPath(lashPath, lash);
    }

    private void iris(Canvas c, float w, float h) {
        float r = Math.min(w, h) * 0.5f;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        Paint irisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        irisPaint.setShader(new RadialGradient(cx, cy * 0.75f, r,
                new int[]{Color.rgb(124, 158, 118), LeonPalette.IRIS, Color.rgb(30, 46, 34)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, irisPaint);
        c.drawCircle(cx, cy, r * 0.44f, fill(LeonPalette.PUPIL));
        c.drawCircle(cx - r * 0.28f, cy - r * 0.32f, r * 0.22f, fill(Color.argb(225, 255, 255, 255)));
        c.drawCircle(cx + r * 0.30f, cy + r * 0.30f, r * 0.10f, fill(Color.argb(90, 255, 255, 255)));
    }

    private void eyelid(Canvas c, float w, float h) {
        // Pivoted at its top edge by the rig, so scaling this layer's height closes the eye.
        Path lid = new Path();
        lid.moveTo(0f, 0f);
        lid.lineTo(w, 0f);
        lid.lineTo(w, h * 0.66f);
        lid.cubicTo(w * 0.80f, h, w * 0.20f, h, 0f, h * 0.66f);
        lid.close();
        c.drawPath(lid, vertical(0f, h, LeonPalette.SKIN_HIGHLIGHT, LeonPalette.SKIN));
        c.save();
        c.clipPath(lid);
        c.drawRect(0f, 0f, w, h, vertical(h * 0.35f, h, Color.TRANSPARENT, Color.argb(76, 122, 68, 40)));
        c.restore();
        // Lash line along the closing edge — what makes a blink readable at overlay size.
        Paint lash = stroke(Color.argb(235, 22, 19, 18), h * 0.115f);
        Path edge = new Path();
        edge.moveTo(0f, h * 0.64f);
        edge.cubicTo(w * 0.20f, h * 0.98f, w * 0.80f, h * 0.98f, w, h * 0.64f);
        c.drawPath(edge, lash);
    }

    private void brow(Canvas c, float w, float h, boolean flip) {
        c.save();
        if (flip) mirror(c, w);
        Path b = new Path();
        // Heavy at the inner end, tapering to a point outboard.
        b.moveTo(w, h * 0.22f);
        b.cubicTo(w * 0.60f, h * 0.02f, w * 0.20f, h * 0.18f, 0f, h * 0.58f);
        b.cubicTo(w * 0.22f, h * 0.66f, w * 0.62f, h * 0.62f, w, h * 0.94f);
        b.close();
        c.drawPath(b, horizontal(0f, w, Color.rgb(30, 25, 23), Color.rgb(16, 13, 12)));
        c.restore();
    }

    private void nose(Canvas c, float w, float h) {
        // Shading only; the nose is a form on the head beneath it, not an outline.
        Path bridge = new Path();
        bridge.moveTo(w * 0.56f, 0f);
        bridge.cubicTo(w * 0.40f, h * 0.40f, w * 0.26f, h * 0.64f, w * 0.26f, h * 0.82f);
        c.drawPath(bridge, stroke(Color.argb(60, 112, 58, 34), w * 0.17f));
        Path tip = new Path();
        tip.moveTo(w * 0.18f, h * 0.80f);
        tip.cubicTo(w * 0.32f, h * 0.99f, w * 0.68f, h * 0.99f, w * 0.82f, h * 0.80f);
        tip.cubicTo(w * 0.68f, h * 0.62f, w * 0.32f, h * 0.62f, w * 0.18f, h * 0.80f);
        tip.close();
        c.drawPath(tip, fill(Color.argb(52, 156, 88, 54)));
        Paint nostril = fill(Color.argb(130, 68, 36, 24));
        c.drawOval(new RectF(w * 0.14f, h * 0.80f, w * 0.36f, h * 0.92f), nostril);
        c.drawOval(new RectF(w * 0.64f, h * 0.80f, w * 0.86f, h * 0.92f), nostril);
        // Highlight on the bridge, catching the same light as the scalp.
        c.drawLine(w * 0.56f, h * 0.10f, w * 0.48f, h * 0.60f,
                stroke(Color.argb(50, 255, 240, 224), w * 0.09f));
    }

    private void sunglasses(Canvas c, float w, float h) {
        // Temple arms first, so the lenses and frame sit over them.
        Paint arm = stroke(LeonPalette.FRAME, h * 0.12f);
        c.drawLine(w * 0.02f, h * 0.30f, w * 0.13f, h * 0.42f, arm);
        c.drawLine(w * 0.98f, h * 0.30f, w * 0.87f, h * 0.42f, arm);

        RectF left = new RectF(w * 0.065f, h * 0.14f, w * 0.465f, h * 0.88f);
        RectF right = new RectF(w * 0.535f, h * 0.14f, w * 0.935f, h * 0.88f);
        float r = h * 0.34f;
        Paint lens = fill(LeonPalette.LENS);
        c.drawRoundRect(left, r, r, lens);
        c.drawRoundRect(right, r, r, lens);

        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0f, h, w, 0f,
                new int[]{Color.TRANSPARENT, LeonPalette.LENS_SHEEN, Color.TRANSPARENT},
                new float[]{0.28f, 0.44f, 0.60f}, Shader.TileMode.CLAMP));
        c.save();
        Path lenses = new Path();
        lenses.addRoundRect(left, r, r, Path.Direction.CW);
        lenses.addRoundRect(right, r, r, Path.Direction.CW);
        c.clipPath(lenses);
        c.drawRect(0f, 0f, w, h, sheen);
        c.restore();

        Paint frame = stroke(LeonPalette.FRAME, h * 0.085f);
        c.drawRoundRect(left, r, r, frame);
        c.drawRoundRect(right, r, r, frame);
        // Bridge, sitting on the nose rather than floating between the lenses.
        c.drawLine(w * 0.465f, h * 0.40f, w * 0.535f, h * 0.40f, stroke(LeonPalette.FRAME, h * 0.13f));
    }

    private void earring(Canvas c, float w, float h) {
        Paint gold = stroke(LeonPalette.GOLD, w * 0.22f);
        c.drawOval(new RectF(w * 0.18f, h * 0.26f, w * 0.82f, h * 0.92f), gold);
        c.drawCircle(w * 0.5f, h * 0.14f, w * 0.15f, fill(LeonPalette.GOLD));
    }

    // ------------------------------------------------------------------ mouth / visemes

    /** @param curve 1 smile, 0 neutral, -1 frown. */
    private void mouthClosed(Canvas c, float w, float h, float curve) {
        float mid = h * 0.50f;
        float lift = h * 0.20f * curve;
        // Lower lip volume first, so the seam reads as sitting on top of it.
        Path lower = new Path();
        lower.moveTo(w * 0.16f, mid + lift * 0.4f);
        lower.cubicTo(w * 0.34f, mid + h * 0.30f, w * 0.66f, mid + h * 0.30f, w * 0.84f, mid + lift * 0.4f);
        lower.cubicTo(w * 0.66f, mid + h * 0.10f, w * 0.34f, mid + h * 0.10f, w * 0.16f, mid + lift * 0.4f);
        lower.close();
        c.drawPath(lower, fill(Color.argb(150, 168, 108, 94)));
        Path upper = new Path();
        upper.moveTo(w * 0.16f, mid + lift * 0.4f);
        upper.cubicTo(w * 0.34f, mid - h * 0.18f - lift, w * 0.66f, mid - h * 0.18f - lift,
                w * 0.84f, mid + lift * 0.4f);
        upper.cubicTo(w * 0.66f, mid - h * 0.02f, w * 0.34f, mid - h * 0.02f, w * 0.16f, mid + lift * 0.4f);
        upper.close();
        c.drawPath(upper, fill(Color.argb(130, 148, 92, 80)));
        // The seam.
        Path seam = new Path();
        seam.moveTo(w * 0.13f, mid + lift * 0.35f);
        seam.cubicTo(w * 0.34f, mid - lift * 1.1f, w * 0.66f, mid - lift * 1.1f, w * 0.87f, mid + lift * 0.35f);
        c.drawPath(seam, stroke(Color.argb(225, 78, 40, 36), h * 0.095f));
        if (curve > 0f) {
            Path teeth = new Path();
            teeth.moveTo(w * 0.28f, mid - lift * 0.35f);
            teeth.cubicTo(w * 0.42f, mid + h * 0.06f, w * 0.58f, mid + h * 0.06f, w * 0.72f, mid - lift * 0.35f);
            teeth.cubicTo(w * 0.58f, mid - lift * 0.70f, w * 0.42f, mid - lift * 0.70f, w * 0.28f, mid - lift * 0.35f);
            teeth.close();
            c.drawPath(teeth, fill(Color.argb((int) (185 * curve), 242, 240, 234)));
        }
    }

    /**
     * An open mouth aperture.
     *
     * @param widthK   horizontal extent, 0..1
     * @param openK    vertical aperture, 0..1
     * @param topTeeth show the upper teeth row
     * @param tongue   show the tongue
     */
    private void mouthOpen(Canvas c, float w, float h, float widthK, float openK,
                           boolean topTeeth, boolean tongue) {
        float cx = w * 0.5f;
        float cy = h * 0.52f;
        float hw = w * 0.42f * widthK;
        float hh = h * 0.40f * openK;
        RectF aperture = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);

        Path mouth = new Path();
        mouth.addOval(aperture, Path.Direction.CW);
        c.drawPath(mouth, fill(LeonPalette.MOUTH_INNER));

        c.save();
        c.clipPath(mouth);
        c.drawRect(aperture, vertical(aperture.top, aperture.bottom,
                Color.argb(185, 30, 12, 14), Color.argb(96, 62, 28, 30)));
        if (topTeeth) {
            RectF teeth = new RectF(aperture.left, aperture.top,
                    aperture.right, aperture.top + hh * 0.58f);
            c.drawRoundRect(teeth, hh * 0.16f, hh * 0.16f, fill(LeonPalette.TEETH));
            Paint gap = stroke(Color.argb(58, 150, 146, 138), Math.max(0.4f, hw * 0.03f));
            for (int i = 1; i < 4; i++) {
                float x = aperture.left + aperture.width() * (i / 4f);
                c.drawLine(x, teeth.top, x, teeth.bottom, gap);
            }
        }
        if (tongue) {
            RectF t = new RectF(cx - hw * 0.58f, aperture.bottom - hh * 0.75f,
                    cx + hw * 0.58f, aperture.bottom + hh * 0.35f);
            c.drawOval(t, fill(Color.rgb(172, 86, 90)));
        }
        c.restore();

        float lipWidth = h * (widthK < 0.5f ? 0.12f : 0.08f);
        c.drawPath(mouth, stroke(Color.argb(215, 122, 68, 58), lipWidth));
    }

    private void mouthPressed(Canvas c, float w, float h) {
        float mid = h * 0.50f;
        Path lips = new Path();
        lips.moveTo(w * 0.10f, mid);
        lips.cubicTo(w * 0.30f, mid - h * 0.20f, w * 0.70f, mid - h * 0.20f, w * 0.90f, mid);
        lips.cubicTo(w * 0.70f, mid + h * 0.22f, w * 0.30f, mid + h * 0.22f, w * 0.10f, mid);
        lips.close();
        c.drawPath(lips, fill(Color.argb(205, 168, 108, 94)));
        c.drawLine(w * 0.10f, mid, w * 0.90f, mid, stroke(Color.argb(230, 88, 44, 40), h * 0.075f));
    }

    private void mouthTeeth(Canvas c, float w, float h) {
        // Labiodental: upper teeth resting on the lower lip.
        float mid = h * 0.48f;
        RectF teeth = new RectF(w * 0.24f, mid - h * 0.22f, w * 0.76f, mid + h * 0.04f);
        c.drawRoundRect(teeth, h * 0.05f, h * 0.05f, fill(LeonPalette.TEETH));
        Paint gap = stroke(Color.argb(72, 150, 146, 138), w * 0.012f);
        for (int i = 1; i < 4; i++) {
            float x = teeth.left + teeth.width() * (i / 4f);
            c.drawLine(x, teeth.top, x, teeth.bottom, gap);
        }
        Path lip = new Path();
        lip.moveTo(w * 0.16f, mid + h * 0.02f);
        lip.cubicTo(w * 0.34f, mid + h * 0.32f, w * 0.66f, mid + h * 0.32f, w * 0.84f, mid + h * 0.02f);
        lip.cubicTo(w * 0.66f, mid + h * 0.10f, w * 0.34f, mid + h * 0.10f, w * 0.16f, mid + h * 0.02f);
        lip.close();
        c.drawPath(lip, fill(Color.argb(232, 176, 108, 94)));
    }

    // ------------------------------------------------------------------ neck / torso

    private void neckSkin(Canvas c, float w, float h) {
        // Narrow under the jaw, flaring into the trapezius. A straight-sided column reads as a
        // separate box sitting on the shoulders rather than as part of him.
        Path neck = new Path();
        neck.moveTo(w * 0.28f, 0f);
        neck.cubicTo(w * 0.25f, h * 0.38f, w * 0.13f, h * 0.62f, 0f, h * 0.88f);
        neck.lineTo(0f, h);
        neck.lineTo(w, h);
        neck.lineTo(w, h * 0.88f);
        neck.cubicTo(w * 0.87f, h * 0.62f, w * 0.75f, h * 0.38f, w * 0.72f, 0f);
        neck.close();
        c.drawPath(neck, vertical(0f, h, LeonPalette.SKIN_SHADOW, LeonPalette.SKIN));
        c.save();
        c.clipPath(neck);
        // Shadow cast by the jaw down the top of the neck.
        c.drawRect(0f, 0f, w, h, vertical(0f, h * 0.58f, Color.argb(165, 68, 35, 20), Color.TRANSPARENT));
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.44f, Color.argb(78, 74, 40, 22), Color.TRANSPARENT));
        // And a shadow where it disappears into the collar, so it recedes instead of stopping dead.
        c.drawRect(0f, 0f, w, h, vertical(h * 0.72f, h, Color.TRANSPARENT, Color.argb(140, 44, 24, 14)));
        Paint sinew = stroke(Color.argb(34, 120, 66, 40), w * 0.065f);
        c.drawLine(w * 0.40f, h * 0.24f, w * 0.30f, h * 0.90f, sinew);
        c.drawLine(w * 0.60f, h * 0.24f, w * 0.70f, h * 0.90f, sinew);
        c.restore();
    }

    private void neckTattoo(Canvas c, float w, float h) {
        // One deliberate motif low on the side of the neck, not a field of lines.
        Paint ink = stroke(Color.argb(120, 32, 44, 66), w * 0.045f);
        Path curve = new Path();
        curve.moveTo(w * 0.14f, h * 0.96f);
        curve.cubicTo(w * 0.20f, h * 0.62f, w * 0.34f, h * 0.48f, w * 0.48f, h * 0.44f);
        c.drawPath(curve, ink);
        Path curve2 = new Path();
        curve2.moveTo(w * 0.26f, h);
        curve2.cubicTo(w * 0.32f, h * 0.72f, w * 0.44f, h * 0.62f, w * 0.56f, h * 0.60f);
        c.drawPath(curve2, stroke(Color.argb(88, 32, 44, 66), w * 0.03f));
        Paint dot = fill(Color.argb(120, 30, 42, 62));
        c.drawCircle(w * 0.55f, h * 0.40f, w * 0.028f, dot);
        c.drawCircle(w * 0.64f, h * 0.55f, w * 0.020f, dot);
    }

    private void chestTattoo(Canvas c, float w, float h) {
        // Opaque skin plus ink: this layer sits behind the hoodie and fills its V-neck opening, so a
        // transparent version would leave a hole in his chest.
        Path chest = new Path();
        chest.moveTo(w * 0.02f, h * 0.10f);
        chest.cubicTo(w * 0.20f, h * 0.0f, w * 0.80f, h * 0.0f, w * 0.98f, h * 0.10f);
        chest.cubicTo(w * 0.96f, h * 0.56f, w * 0.76f, h * 0.90f, w * 0.5f, h);
        chest.cubicTo(w * 0.24f, h * 0.90f, w * 0.04f, h * 0.56f, w * 0.02f, h * 0.10f);
        chest.close();
        c.drawPath(chest, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));

        c.save();
        c.clipPath(chest);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.45f, Color.argb(88, 78, 42, 24), Color.TRANSPARENT));
        // Collarbones and the sternum line — muscled but proportionate.
        Paint clavicle = stroke(Color.argb(56, 128, 72, 44), w * 0.042f);
        Path cl = new Path();
        cl.moveTo(w * 0.08f, h * 0.14f);
        cl.cubicTo(w * 0.30f, h * 0.24f, w * 0.70f, h * 0.24f, w * 0.92f, h * 0.14f);
        c.drawPath(cl, clavicle);
        c.drawLine(w * 0.5f, h * 0.34f, w * 0.5f, h * 0.96f,
                stroke(Color.argb(70, 96, 52, 32), w * 0.035f));

        // A symmetric ornamental piece across the upper chest.
        for (int side = 0; side < 2; side++) {
            c.save();
            if (side == 1) mirror(c, w);
            Paint ink = stroke(Color.argb(112, 32, 44, 66), w * 0.038f);
            Path arc = new Path();
            arc.moveTo(w * 0.46f, h * 0.44f);
            arc.cubicTo(w * 0.30f, h * 0.34f, w * 0.16f, h * 0.44f, w * 0.10f, h * 0.62f);
            c.drawPath(arc, ink);
            Path arc2 = new Path();
            arc2.moveTo(w * 0.46f, h * 0.60f);
            arc2.cubicTo(w * 0.32f, h * 0.54f, w * 0.22f, h * 0.64f, w * 0.18f, h * 0.78f);
            c.drawPath(arc2, stroke(Color.argb(84, 32, 44, 66), w * 0.028f));
            c.restore();
        }
        c.restore();
    }

    private void necklace(Canvas c, float w, float h) {
        Paint chain = stroke(LeonPalette.SILVER, w * 0.022f);
        Path strand = new Path();
        strand.moveTo(w * 0.16f, 0f);
        strand.cubicTo(w * 0.22f, h * 0.44f, w * 0.40f, h * 0.66f, w * 0.5f, h * 0.70f);
        strand.cubicTo(w * 0.60f, h * 0.66f, w * 0.78f, h * 0.44f, w * 0.84f, 0f);
        c.drawPath(strand, chain);

        float[][] positions = {
                {0.185f, 0.18f}, {0.235f, 0.40f}, {0.325f, 0.575f}, {0.425f, 0.675f},
                {0.575f, 0.675f}, {0.675f, 0.575f}, {0.765f, 0.40f}, {0.815f, 0.18f},
        };
        for (int i = 0; i < positions.length; i++) {
            int colour = LeonPalette.GEMS[i % LeonPalette.GEMS.length];
            gem(c, w * positions[i][0], h * positions[i][1], w * 0.042f, colour);
        }
        // Central pendant, bezel-set in gold.
        float px = w * 0.5f;
        float py = h * 0.80f;
        float pr = w * 0.095f;
        c.drawCircle(px, py, pr * 1.3f, fill(LeonPalette.GOLD));
        c.drawCircle(px, py, pr * 1.3f, stroke(Color.argb(90, 120, 88, 30), w * 0.012f));
        gem(c, px, py, pr, LeonPalette.GEMS[0]);
    }

    private void gem(Canvas c, float cx, float cy, float r, int colour) {
        Paint facet = new Paint(Paint.ANTI_ALIAS_FLAG);
        facet.setShader(new RadialGradient(cx - r * 0.32f, cy - r * 0.36f, r * 1.6f,
                new int[]{Color.WHITE, colour, Color.argb(255,
                        (int) (Color.red(colour) * 0.4f),
                        (int) (Color.green(colour) * 0.4f),
                        (int) (Color.blue(colour) * 0.4f))},
                new float[]{0f, 0.40f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, facet);
    }

    // ------------------------------------------------------------------ clothing

    private void hoodieBody(Canvas c, float w, float h) {
        Path body = new Path();
        // Sloped shoulders into a lightly tapered body, so the silhouette is not a slab.
        body.moveTo(w * 0.13f, h * 0.075f);
        body.cubicTo(w * 0.26f, h * 0.008f, w * 0.38f, 0f, w * 0.5f, 0f);
        body.cubicTo(w * 0.62f, 0f, w * 0.74f, h * 0.008f, w * 0.87f, h * 0.075f);
        body.cubicTo(w * 0.99f, h * 0.20f, w * 1.0f, h * 0.46f, w * 0.955f, h * 0.80f);
        body.cubicTo(w * 0.945f, h * 0.92f, w * 0.94f, h * 0.97f, w * 0.935f, h);
        body.lineTo(w * 0.065f, h);
        body.cubicTo(w * 0.06f, h * 0.97f, w * 0.055f, h * 0.92f, w * 0.045f, h * 0.80f);
        body.cubicTo(w * 0.0f, h * 0.46f, w * 0.01f, h * 0.20f, w * 0.13f, h * 0.075f);
        body.close();

        // Punch a V-neck out of the garment so the tattooed chest behind it shows through.
        Path vNeck = new Path();
        vNeck.moveTo(w * 0.335f, 0f);
        vNeck.cubicTo(w * 0.355f, h * 0.10f, w * 0.44f, h * 0.20f, w * 0.5f, h * 0.235f);
        vNeck.cubicTo(w * 0.56f, h * 0.20f, w * 0.645f, h * 0.10f, w * 0.665f, 0f);
        vNeck.close();
        body.op(vNeck, Path.Op.DIFFERENCE);

        c.drawPath(body, vertical(0f, h, LeonPalette.HOODIE_HIGHLIGHT, LeonPalette.HOODIE_SHADOW));

        c.save();
        c.clipPath(body);
        // Light from the upper right.
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.40f, Color.argb(135, 0, 0, 0), Color.TRANSPARENT));
        c.drawRect(0f, 0f, w, h, horizontal(w * 0.74f, w, Color.TRANSPARENT, Color.argb(48, 150, 160, 180)));
        // Soft folds falling from the chest, widely spaced.
        Paint fold = stroke(Color.argb(38, 128, 134, 148), w * 0.012f);
        Path f1 = new Path();
        f1.moveTo(w * 0.33f, h * 0.30f);
        f1.cubicTo(w * 0.30f, h * 0.55f, w * 0.30f, h * 0.72f, w * 0.285f, h * 0.90f);
        c.drawPath(f1, fold);
        Path f2 = new Path();
        f2.moveTo(w * 0.67f, h * 0.30f);
        f2.cubicTo(w * 0.70f, h * 0.55f, w * 0.70f, h * 0.72f, w * 0.715f, h * 0.90f);
        c.drawPath(f2, fold);
        // Ribbed hem as a single band, not a fringe of lines.
        c.drawRect(0f, h * 0.935f, w, h, fill(Color.rgb(13, 14, 17)));
        c.drawLine(0f, h * 0.935f, w, h * 0.935f, stroke(Color.argb(55, 128, 134, 148), h * 0.006f));
        c.restore();

        // Collar and drawstrings.
        Paint collar = stroke(Color.rgb(34, 36, 43), w * 0.032f);
        Path collarPath = new Path();
        collarPath.moveTo(w * 0.325f, 0f);
        collarPath.cubicTo(w * 0.348f, h * 0.104f, w * 0.437f, h * 0.204f, w * 0.5f, h * 0.240f);
        collarPath.cubicTo(w * 0.563f, h * 0.204f, w * 0.652f, h * 0.104f, w * 0.675f, 0f);
        c.drawPath(collarPath, collar);

        Paint cord = stroke(Color.rgb(198, 200, 207), w * 0.013f);
        c.drawLine(w * 0.437f, h * 0.195f, w * 0.418f, h * 0.375f, cord);
        c.drawLine(w * 0.563f, h * 0.195f, w * 0.582f, h * 0.385f, cord);
        Paint tip = fill(Color.rgb(146, 148, 156));
        c.drawCircle(w * 0.418f, h * 0.385f, w * 0.016f, tip);
        c.drawCircle(w * 0.582f, h * 0.395f, w * 0.016f, tip);
    }

    private void hoodiePocket(Canvas c, float w, float h) {
        RectF pocket = new RectF(w * 0.06f, h * 0.18f, w * 0.94f, h * 0.96f);
        c.drawRoundRect(pocket, w * 0.06f, w * 0.06f, fill(Color.argb(120, 30, 31, 37)));
        // Just the top seam and the two hand openings; a filled slab reads as a panel stuck on.
        c.drawLine(w * 0.08f, h * 0.26f, w * 0.92f, h * 0.26f,
                stroke(Color.argb(60, 104, 110, 124), h * 0.04f));
        Paint opening = stroke(Color.argb(130, 9, 9, 11), h * 0.07f);
        c.drawLine(w * 0.09f, h * 0.44f, w * 0.23f, h * 0.32f, opening);
        c.drawLine(w * 0.91f, h * 0.44f, w * 0.77f, h * 0.32f, opening);
    }

    private void hoodDown(Canvas c, float w, float h) {
        // Worn DOWN: a bunched mass sitting behind the shoulders, never over the head.
        Path hood = new Path();
        hood.moveTo(w * 0.22f, 0f);
        hood.cubicTo(w * 0.05f, h * 0.24f, w * 0.0f, h * 0.66f, w * 0.13f, h * 0.90f);
        hood.cubicTo(w * 0.32f, h, w * 0.68f, h, w * 0.87f, h * 0.90f);
        hood.cubicTo(w, h * 0.66f, w * 0.95f, h * 0.24f, w * 0.78f, 0f);
        hood.close();
        c.drawPath(hood, vertical(0f, h, Color.rgb(28, 30, 36), Color.rgb(10, 11, 14)));

        c.save();
        c.clipPath(hood);
        Paint fold = stroke(Color.argb(70, 104, 110, 124), w * 0.011f);
        for (int i = 0; i < 3; i++) {
            float x = w * (0.30f + i * 0.20f);
            Path p = new Path();
            p.moveTo(x, h * 0.10f);
            p.cubicTo(x - w * 0.035f, h * 0.44f, x + w * 0.025f, h * 0.70f, x - w * 0.01f, h * 0.92f);
            c.drawPath(p, fold);
        }
        c.drawRect(0f, 0f, w, h, vertical(0f, h * 0.45f, Color.argb(120, 0, 0, 0), Color.TRANSPARENT));
        c.restore();
    }

    private void sleeve(Canvas c, float w, float h, boolean rightArm) {
        c.save();
        // Mirror so the three-stripe detail always sits on the arm's outer edge.
        if (rightArm) mirror(c, w);
        Path arm = limb(w, h, w * 0.92f, w * 0.70f, w * 0.04f);
        c.drawPath(arm, vertical(0f, h, LeonPalette.HOODIE_HIGHLIGHT, LeonPalette.HOODIE_SHADOW));
        coreShadow(c, arm, w, h, Color.argb(125, 0, 0, 0));
        rimLight(c, arm, w, h, true);
        c.save();
        c.clipPath(arm);
        // Generic three-stripe sleeve detail. No brand mark is reproduced.
        Paint stripe = fill(Color.argb(236, 228, 230, 235));
        for (int i = 0; i < 3; i++) {
            float x = w * (0.16f + i * 0.105f);
            c.drawRoundRect(new RectF(x, h * 0.10f, x + w * 0.055f, h * 0.62f),
                    w * 0.028f, w * 0.028f, stripe);
        }
        // Elbow crease.
        Path crease = new Path();
        crease.moveTo(w * 0.22f, h * 0.80f);
        crease.cubicTo(w * 0.45f, h * 0.86f, w * 0.60f, h * 0.86f, w * 0.80f, h * 0.82f);
        c.drawPath(crease, stroke(Color.argb(58, 112, 118, 132), w * 0.018f));
        c.restore();
        // Ribbed cuff.
        c.drawRoundRect(new RectF(w * 0.16f, h * 0.90f, w * 0.84f, h * 0.995f),
                w * 0.06f, w * 0.06f, fill(Color.rgb(24, 26, 31)));
        c.restore();
    }

    private void forearm(Canvas c, float w, float h, boolean rightArm) {
        c.save();
        if (rightArm) mirror(c, w);
        Path arm = limb(w, h, w * 0.82f, w * 0.56f, w * 0.045f);
        c.drawPath(arm, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));
        coreShadow(c, arm, w, h, Color.argb(100, 74, 40, 22));
        rimLight(c, arm, w, h, true);
        c.save();
        c.clipPath(arm);
        // Two soft muscle lines — lean and defined, not inflated.
        Paint sinew = stroke(Color.argb(38, 118, 64, 38), w * 0.06f);
        Path s1 = new Path();
        s1.moveTo(w * 0.62f, h * 0.10f);
        s1.cubicTo(w * 0.56f, h * 0.32f, w * 0.52f, h * 0.48f, w * 0.50f, h * 0.62f);
        c.drawPath(s1, sinew);

        // Tattoo sleeve: two bands and one flowing motif, rather than a field of scratches.
        Paint ink = stroke(Color.argb(118, 32, 44, 66), w * 0.055f);
        for (int i = 0; i < 2; i++) {
            float y = h * (0.15f + i * 0.085f);
            Path band = new Path();
            band.moveTo(w * 0.04f, y);
            band.cubicTo(w * 0.35f, y + h * 0.03f, w * 0.65f, y + h * 0.03f, w * 0.96f, y);
            c.drawPath(band, ink);
        }
        Paint fine = stroke(Color.argb(96, 32, 44, 66), w * 0.035f);
        Path motif = new Path();
        motif.moveTo(w * 0.24f, h * 0.44f);
        motif.cubicTo(w * 0.62f, h * 0.52f, w * 0.30f, h * 0.64f, w * 0.66f, h * 0.74f);
        c.drawPath(motif, fine);
        Path motif2 = new Path();
        motif2.moveTo(w * 0.30f, h * 0.58f);
        motif2.cubicTo(w * 0.58f, h * 0.66f, w * 0.34f, h * 0.76f, w * 0.60f, h * 0.86f);
        c.drawPath(motif2, stroke(Color.argb(72, 32, 44, 66), w * 0.028f));
        c.restore();
        c.restore();
    }

    private void hand(Canvas c, float w, float h, boolean rightHand) {
        c.save();
        if (rightHand) mirror(c, w);
        // Compact and slightly curled. Narrower than the wrist it hangs from, or it reads as a mitt.
        Path palm = new Path();
        palm.moveTo(w * 0.30f, 0f);
        palm.cubicTo(w * 0.12f, h * 0.14f, w * 0.10f, h * 0.48f, w * 0.22f, h * 0.74f);
        palm.cubicTo(w * 0.34f, h * 0.96f, w * 0.64f, h, w * 0.78f, h * 0.78f);
        palm.cubicTo(w * 0.90f, h * 0.54f, w * 0.88f, h * 0.16f, w * 0.72f, 0f);
        palm.close();
        c.drawPath(palm, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));
        coreShadow(c, palm, w, h, Color.argb(92, 74, 40, 22));
        rimLight(c, palm, w, h, true);

        c.save();
        c.clipPath(palm);
        // Curled fingers: grooves that stop short of the silhouette.
        Paint groove = stroke(Color.argb(78, 106, 56, 32), w * 0.055f);
        for (int i = 0; i < 3; i++) {
            float x = w * (0.36f + i * 0.17f);
            c.drawLine(x, h * 0.46f, x - w * 0.02f, h * 0.86f, groove);
        }
        c.drawLine(w * 0.26f, h * 0.26f, w * 0.18f, h * 0.56f,
                stroke(Color.argb(72, 106, 56, 32), w * 0.085f));
        // Ring, kept as jewellery rather than a painted dash.
        c.drawLine(w * 0.53f, h * 0.60f, w * 0.62f, h * 0.595f,
                stroke(Color.argb(165, 196, 160, 92), w * 0.05f));
        c.restore();
        c.restore();
    }

    private void pantLeg(Canvas c, float w, float h, boolean thigh) {
        Path leg = thigh
                ? limb(w, h, w * 0.96f, w * 0.76f, w * 0.045f)
                : limb(w, h, w * 0.88f, w * 0.66f, w * 0.03f);
        c.drawPath(leg, vertical(0f, h, LeonPalette.PANTS_HIGHLIGHT, LeonPalette.PANTS));
        coreShadow(c, leg, w, h, Color.argb(130, 0, 0, 0));
        rimLight(c, leg, w, h, true);
        c.save();
        c.clipPath(leg);
        // A couple of soft creases, not a ladder.
        Paint fold = stroke(Color.argb(40, 104, 110, 124), w * 0.022f);
        Path f1 = new Path();
        f1.moveTo(w * 0.22f, h * 0.44f);
        f1.cubicTo(w * 0.45f, h * 0.48f, w * 0.60f, h * 0.47f, w * 0.80f, h * 0.42f);
        c.drawPath(f1, fold);
        Path f2 = new Path();
        f2.moveTo(w * 0.24f, h * 0.70f);
        f2.cubicTo(w * 0.46f, h * 0.74f, w * 0.60f, h * 0.73f, w * 0.78f, h * 0.68f);
        c.drawPath(f2, fold);
        c.restore();
        if (!thigh) {
            c.drawRoundRect(new RectF(w * 0.14f, h * 0.91f, w * 0.86f, h * 0.995f),
                    w * 0.06f, w * 0.06f, fill(Color.rgb(22, 23, 28)));
        }
    }

    private void sneaker(Canvas c, float w, float h, boolean rightFoot) {
        c.save();
        if (rightFoot) mirror(c, w);
        // Upper: a low-profile trainer with a defined toe box and a heel collar.
        Path upper = new Path();
        upper.moveTo(w * 0.06f, h * 0.72f);
        upper.cubicTo(w * 0.05f, h * 0.34f, w * 0.14f, h * 0.10f, w * 0.34f, h * 0.10f);
        upper.cubicTo(w * 0.56f, h * 0.10f, w * 0.80f, h * 0.34f, w * 0.96f, h * 0.60f);
        upper.cubicTo(w * 0.99f, h * 0.66f, w * 0.99f, h * 0.72f, w * 0.97f, h * 0.76f);
        upper.lineTo(w * 0.06f, h * 0.76f);
        upper.close();
        c.drawPath(upper, vertical(0f, h, Color.rgb(252, 252, 254), LeonPalette.SNEAKER_SHADOW));

        c.save();
        c.clipPath(upper);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.30f, Color.argb(48, 150, 156, 170), Color.TRANSPARENT));
        // Toe cap seam and lace panel.
        Path toe = new Path();
        toe.moveTo(w * 0.70f, h * 0.22f);
        toe.cubicTo(w * 0.76f, h * 0.42f, w * 0.78f, h * 0.58f, w * 0.78f, h * 0.74f);
        c.drawPath(toe, stroke(Color.argb(105, 186, 190, 200), h * 0.045f));
        Paint lace = stroke(Color.argb(150, 176, 180, 192), h * 0.05f);
        for (int i = 0; i < 3; i++) {
            float x = w * (0.32f + i * 0.11f);
            c.drawLine(x, h * 0.22f + i * h * 0.035f, x + w * 0.10f, h * 0.32f + i * h * 0.035f, lace);
        }
        // Heel collar.
        Path heel = new Path();
        heel.moveTo(w * 0.06f, h * 0.30f);
        heel.cubicTo(w * 0.16f, h * 0.22f, w * 0.24f, h * 0.30f, w * 0.26f, h * 0.44f);
        c.drawPath(heel, stroke(Color.argb(95, 186, 190, 200), h * 0.05f));
        c.restore();

        // Midsole and outsole.
        RectF mid = new RectF(w * 0.03f, h * 0.70f, w * 0.99f, h * 0.88f);
        c.drawRoundRect(mid, h * 0.10f, h * 0.10f, fill(LeonPalette.SNEAKER));
        RectF sole = new RectF(w * 0.03f, h * 0.84f, w * 0.99f, h);
        c.drawRoundRect(sole, h * 0.09f, h * 0.09f, fill(LeonPalette.SNEAKER_SOLE));
        c.drawLine(w * 0.05f, h * 0.845f, w * 0.97f, h * 0.845f,
                stroke(Color.argb(90, 180, 184, 196), h * 0.025f));
        c.restore();
    }

    private void aura(Canvas c, float w, float h) {
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        int a = LeonPalette.AURA;
        glow.setShader(new RadialGradient(cx, cy, Math.min(cx, cy),
                new int[]{
                        Color.argb(0, Color.red(a), Color.green(a), Color.blue(a)),
                        Color.argb(44, Color.red(a), Color.green(a), Color.blue(a)),
                        Color.argb(82, Color.red(a), Color.green(a), Color.blue(a)),
                        Color.argb(0, Color.red(a), Color.green(a), Color.blue(a)),
                },
                new float[]{0f, 0.55f, 0.80f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, Math.min(cx, cy), glow);
    }
}
