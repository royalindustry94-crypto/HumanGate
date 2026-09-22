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
 * Draws the development rig's artwork procedurally, one bitmap per logical art key.
 *
 * <p>This exists so the animation runtime can be demonstrated on a real device before the finished
 * Leon artwork is available. It is <em>not</em> a fallback that hides a missing feature: every layer
 * it produces is a genuinely separate bitmap on its own bone, so every independent motion the rig
 * supports — blinking, breathing, head turn, shoulder and arm movement, viseme swaps — is visible.
 * Replacing it with {@link AssetDirArtProvider} swaps the art without touching any other class.
 *
 * <p>Each key is drawn once, on demand, at the layer's own design size multiplied by
 * {@code artScale}, and cached. Author coordinates are design units; the canvas is pre-scaled.
 *
 * <p>The three-stripe sleeve detail is generic. No brand logo or wordmark is reproduced — see
 * {@code docs/LEON_CHARACTER_ASSET_SPEC.md} for what the production art owner must supply.
 */
public final class DevRigArt implements LeonArtProvider {
    /** Cap so an unusual density cannot allocate an unreasonable amount of bitmap memory. */
    private static final float MAX_SCALE = 2.5f;

    private final Map<String, float[]> sizes = new HashMap<>();
    private final Map<String, Bitmap> cache = new HashMap<>();
    private final float artScale;
    private boolean released;

    public DevRigArt(Rig rig, float artScale) {
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
        return "Bundled development rig (procedural, " + sizes.size() + " layers @" + artScale + "x)";
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
            case LeonRig.Art.HOOD_DOWN: hoodDown(c, w, h); break;
            case LeonRig.Art.PANT_LEG: pantLeg(c, w, h, true); break;
            case LeonRig.Art.PANT_SHIN: pantLeg(c, w, h, false); break;
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

    /** Symmetric tapered limb: a rounded quad, wider at the top. */
    private static Path limb(float w, float h, float topWidth, float bottomWidth, float bulge) {
        float cx = w * 0.5f;
        Path path = new Path();
        path.moveTo(cx - topWidth * 0.5f, 0f);
        path.cubicTo(cx - topWidth * 0.5f - bulge, h * 0.35f,
                cx - bottomWidth * 0.5f - bulge * 0.3f, h * 0.7f,
                cx - bottomWidth * 0.5f, h);
        path.lineTo(cx + bottomWidth * 0.5f, h);
        path.cubicTo(cx + bottomWidth * 0.5f + bulge * 0.3f, h * 0.7f,
                cx + topWidth * 0.5f + bulge, h * 0.35f,
                cx + topWidth * 0.5f, 0f);
        path.close();
        return path;
    }

    // ------------------------------------------------------------------ head

    private void headBald(Canvas c, float w, float h) {
        float cx = w * 0.5f;
        Path skull = new Path();
        // Crown, temples, cheekbones, jaw, chin.
        skull.moveTo(cx, h * 0.015f);
        skull.cubicTo(w * 0.93f, h * 0.02f, w * 0.99f, h * 0.30f, w * 0.965f, h * 0.46f);
        skull.cubicTo(w * 0.94f, h * 0.62f, w * 0.87f, h * 0.76f, w * 0.72f, h * 0.90f);
        skull.cubicTo(w * 0.64f, h * 0.975f, w * 0.57f, h, cx, h);
        skull.cubicTo(w * 0.43f, h, w * 0.36f, h * 0.975f, w * 0.28f, h * 0.90f);
        skull.cubicTo(w * 0.13f, h * 0.76f, w * 0.06f, h * 0.62f, w * 0.035f, h * 0.46f);
        skull.cubicTo(w * 0.01f, h * 0.30f, w * 0.07f, h * 0.02f, cx, h * 0.015f);
        skull.close();
        c.drawPath(skull, vertical(0f, h, LeonPalette.SKIN_HIGHLIGHT, LeonPalette.SKIN_SHADOW));

        // Shade the screen-left side so the head reads as a volume, not a flat cut-out.
        c.save();
        c.clipPath(skull);
        Paint sideShade = horizontal(0f, w * 0.55f, Color.argb(90, 70, 40, 22), Color.TRANSPARENT);
        c.drawRect(0f, 0f, w, h, sideShade);
        // A bald head has a broad specular highlight on the crown.
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new RadialGradient(w * 0.38f, h * 0.17f, w * 0.34f,
                new int[]{Color.argb(120, 255, 244, 232), Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, w, h, gloss);
        // Jaw shadow and a hint of stubble along the jawline.
        Paint jawShade = vertical(h * 0.72f, h, Color.TRANSPARENT, Color.argb(80, 60, 34, 20));
        c.drawRect(0f, h * 0.7f, w, h, jawShade);
        Paint stubble = stroke(Color.argb(46, 40, 34, 34), h * 0.012f);
        for (int i = 0; i < 3; i++) {
            float y = h * (0.80f + i * 0.045f);
            c.drawLine(w * (0.26f + i * 0.02f), y, w * (0.74f - i * 0.02f), y + h * 0.012f, stubble);
        }
        // Cheekbones.
        Paint cheek = fill(Color.argb(38, 190, 96, 70));
        c.drawOval(new RectF(w * 0.10f, h * 0.55f, w * 0.34f, h * 0.68f), cheek);
        c.drawOval(new RectF(w * 0.66f, h * 0.55f, w * 0.90f, h * 0.68f), cheek);
        c.restore();

        c.drawPath(skull, stroke(Color.argb(60, 90, 52, 30), w * 0.012f));
    }

    private void headTattoo(Canvas c, float w, float h) {
        // One continuous band sweeping from the screen-left temple, over the side and back of the
        // skull, to the opposite temple, plus a geometric motif down each temple.
        Paint band = stroke(LeonPalette.INK_SOFT, h * 0.022f);
        for (int i = 0; i < 5; i++) {
            float y = h * (0.06f + i * 0.055f);
            Path p = new Path();
            p.moveTo(w * 0.04f, y + h * 0.12f);
            p.cubicTo(w * 0.22f, y - h * 0.045f, w * 0.78f, y - h * 0.045f, w * 0.96f, y + h * 0.12f);
            c.drawPath(p, band);
        }
        // Angular temple motif, mirrored, sitting above and beside the brow line.
        Paint motif = stroke(LeonPalette.INK_SOFT, h * 0.018f);
        for (int side = 0; side < 2; side++) {
            c.save();
            if (side == 1) mirror(c, w);
            for (int i = 0; i < 4; i++) {
                float x = w * (0.045f + i * 0.026f);
                Path p = new Path();
                p.moveTo(x, h * 0.40f);
                p.lineTo(x + w * 0.035f, h * 0.55f);
                p.lineTo(x, h * 0.70f);
                p.lineTo(x + w * 0.028f, h * 0.86f);
                c.drawPath(p, motif);
            }
            // A few dots to break up the line work.
            Paint dot = fill(LeonPalette.INK_SOFT);
            for (int i = 0; i < 3; i++) {
                c.drawCircle(w * 0.155f, h * (0.46f + i * 0.14f), h * 0.014f, dot);
            }
            c.restore();
        }
    }

    private void ear(Canvas c, float w, float h, boolean flip) {
        c.save();
        if (flip) mirror(c, w);
        RectF outer = new RectF(w * 0.08f, h * 0.05f, w * 0.95f, h * 0.95f);
        c.drawOval(outer, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));
        Paint inner = stroke(Color.argb(110, 120, 66, 40), w * 0.09f);
        Path curl = new Path();
        curl.moveTo(w * 0.62f, h * 0.22f);
        curl.cubicTo(w * 0.28f, h * 0.30f, w * 0.30f, h * 0.66f, w * 0.60f, h * 0.76f);
        c.drawPath(curl, inner);
        c.restore();
    }

    private void eyeWhite(Canvas c, float w, float h) {
        Path almond = new Path();
        almond.moveTo(0f, h * 0.55f);
        almond.cubicTo(w * 0.18f, h * 0.02f, w * 0.82f, h * 0.02f, w, h * 0.5f);
        almond.cubicTo(w * 0.80f, h * 0.99f, w * 0.20f, h * 0.99f, 0f, h * 0.55f);
        almond.close();
        c.drawPath(almond, fill(LeonPalette.EYE_WHITE));
        c.save();
        c.clipPath(almond);
        // Socket shadow along the top so the eye sits inside the face.
        c.drawRect(0f, 0f, w, h, vertical(0f, h * 0.6f, Color.argb(105, 74, 48, 34), Color.TRANSPARENT));
        c.restore();
        // Upper lash line.
        Paint lash = stroke(Color.argb(210, 26, 22, 20), h * 0.13f);
        Path lashPath = new Path();
        lashPath.moveTo(0f, h * 0.52f);
        lashPath.cubicTo(w * 0.2f, h * 0.02f, w * 0.8f, h * 0.02f, w, h * 0.48f);
        c.drawPath(lashPath, lash);
    }

    private void iris(Canvas c, float w, float h) {
        float r = Math.min(w, h) * 0.5f;
        float cx = w * 0.5f;
        float cy = h * 0.5f;
        Paint irisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        irisPaint.setShader(new RadialGradient(cx, cy * 0.8f, r,
                new int[]{Color.rgb(112, 146, 108), LeonPalette.IRIS, Color.rgb(38, 54, 40)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, irisPaint);
        c.drawCircle(cx, cy, r * 0.46f, fill(LeonPalette.PUPIL));
        c.drawCircle(cx - r * 0.3f, cy - r * 0.34f, r * 0.2f, fill(Color.argb(210, 255, 255, 255)));
        c.drawCircle(cx, cy, r, stroke(Color.argb(140, 22, 26, 22), Math.max(0.5f, r * 0.1f)));
    }

    private void eyelid(Canvas c, float w, float h) {
        // Pivoted at its top edge by the rig, so scaling this layer's height closes the eye.
        Path lid = new Path();
        lid.moveTo(0f, 0f);
        lid.lineTo(w, 0f);
        lid.lineTo(w, h * 0.72f);
        lid.cubicTo(w * 0.78f, h, w * 0.22f, h, 0f, h * 0.72f);
        lid.close();
        c.drawPath(lid, vertical(0f, h, LeonPalette.SKIN_HIGHLIGHT, LeonPalette.SKIN));
        c.save();
        c.clipPath(lid);
        c.drawRect(0f, 0f, w, h, vertical(h * 0.4f, h, Color.TRANSPARENT, Color.argb(70, 120, 68, 42)));
        c.restore();
        // Lash line along the closing edge, which is what makes a blink readable at small sizes.
        Paint lash = stroke(Color.argb(225, 24, 20, 18), h * 0.1f);
        Path edge = new Path();
        edge.moveTo(0f, h * 0.70f);
        edge.cubicTo(w * 0.22f, h * 0.99f, w * 0.78f, h * 0.99f, w, h * 0.70f);
        c.drawPath(edge, lash);
    }

    private void brow(Canvas c, float w, float h, boolean flip) {
        c.save();
        if (flip) mirror(c, w);
        Path b = new Path();
        // Thick at the inner end, tapering outwards.
        b.moveTo(w, h * 0.30f);
        b.cubicTo(w * 0.62f, 0f, w * 0.22f, h * 0.12f, 0f, h * 0.52f);
        b.cubicTo(w * 0.24f, h * 0.62f, w * 0.64f, h * 0.60f, w, h * 0.88f);
        b.close();
        c.drawPath(b, horizontal(0f, w, Color.rgb(34, 28, 26), Color.rgb(18, 15, 14)));
        c.restore();
    }

    private void nose(Canvas c, float w, float h) {
        // Shading only — the nose reads as a form on the head beneath it.
        Path bridge = new Path();
        bridge.moveTo(w * 0.5f, 0f);
        bridge.cubicTo(w * 0.30f, h * 0.45f, w * 0.14f, h * 0.70f, w * 0.20f, h * 0.88f);
        c.drawPath(bridge, stroke(Color.argb(72, 118, 64, 38), w * 0.14f));
        Path tip = new Path();
        tip.moveTo(w * 0.16f, h * 0.84f);
        tip.cubicTo(w * 0.34f, h, w * 0.66f, h, w * 0.84f, h * 0.84f);
        tip.cubicTo(w * 0.70f, h * 0.70f, w * 0.30f, h * 0.70f, w * 0.16f, h * 0.84f);
        tip.close();
        c.drawPath(tip, fill(Color.argb(58, 150, 84, 52)));
        Paint nostril = fill(Color.argb(150, 72, 40, 28));
        c.drawOval(new RectF(w * 0.16f, h * 0.80f, w * 0.38f, h * 0.94f), nostril);
        c.drawOval(new RectF(w * 0.62f, h * 0.80f, w * 0.84f, h * 0.94f), nostril);
    }

    private void sunglasses(Canvas c, float w, float h) {
        // Temple arms first, so the lenses and frame sit over them.
        Paint arm = stroke(LeonPalette.FRAME, h * 0.13f);
        c.drawLine(w * 0.03f, h * 0.34f, w * 0.14f, h * 0.44f, arm);
        c.drawLine(w * 0.97f, h * 0.34f, w * 0.86f, h * 0.44f, arm);

        RectF left = new RectF(w * 0.08f, h * 0.16f, w * 0.455f, h * 0.90f);
        RectF right = new RectF(w * 0.545f, h * 0.16f, w * 0.92f, h * 0.90f);
        float r = h * 0.3f;
        Paint lens = fill(LeonPalette.LENS);
        c.drawRoundRect(left, r, r, lens);
        c.drawRoundRect(right, r, r, lens);

        // Diagonal sheen across each lens.
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setShader(new LinearGradient(0f, h, w, 0f,
                new int[]{Color.TRANSPARENT, LeonPalette.LENS_SHEEN, Color.TRANSPARENT},
                new float[]{0.25f, 0.45f, 0.62f}, Shader.TileMode.CLAMP));
        c.save();
        Path lenses = new Path();
        lenses.addRoundRect(left, r, r, Path.Direction.CW);
        lenses.addRoundRect(right, r, r, Path.Direction.CW);
        c.clipPath(lenses);
        c.drawRect(0f, 0f, w, h, sheen);
        c.restore();

        Paint frame = stroke(LeonPalette.FRAME, h * 0.075f);
        c.drawRoundRect(left, r, r, frame);
        c.drawRoundRect(right, r, r, frame);
        c.drawLine(w * 0.455f, h * 0.36f, w * 0.545f, h * 0.36f, stroke(LeonPalette.FRAME, h * 0.1f));
    }

    private void earring(Canvas c, float w, float h) {
        Paint gold = stroke(LeonPalette.GOLD, w * 0.24f);
        c.drawOval(new RectF(w * 0.16f, h * 0.24f, w * 0.84f, h * 0.94f), gold);
        c.drawCircle(w * 0.5f, h * 0.12f, w * 0.16f, fill(LeonPalette.GOLD));
    }

    // ------------------------------------------------------------------ mouth / visemes

    /** @param curve 1 smile, 0 neutral, -1 frown. */
    private void mouthClosed(Canvas c, float w, float h, float curve) {
        float mid = h * 0.52f;
        float lift = h * 0.16f * curve;
        Path lips = new Path();
        lips.moveTo(w * 0.12f, mid + lift * 0.4f);
        lips.cubicTo(w * 0.3f, mid - lift, w * 0.7f, mid - lift, w * 0.88f, mid + lift * 0.4f);
        c.drawPath(lips, stroke(Color.argb(190, 104, 58, 50), h * 0.075f));
        // Lower lip volume, so a closed mouth is not just a line.
        Path lower = new Path();
        lower.moveTo(w * 0.16f, mid + lift * 0.4f);
        lower.cubicTo(w * 0.34f, mid + h * 0.2f, w * 0.66f, mid + h * 0.2f, w * 0.84f, mid + lift * 0.4f);
        lower.cubicTo(w * 0.66f, mid + h * 0.06f, w * 0.34f, mid + h * 0.06f, w * 0.16f, mid + lift * 0.4f);
        lower.close();
        c.drawPath(lower, fill(Color.argb(105, LeonPalette.LIP >> 16 & 0xFF,
                LeonPalette.LIP >> 8 & 0xFF, LeonPalette.LIP & 0xFF)));
        if (curve > 0f) {
            // A smile shows a sliver of teeth.
            Path teeth = new Path();
            teeth.moveTo(w * 0.26f, mid - lift * 0.3f);
            teeth.cubicTo(w * 0.4f, mid + h * 0.05f, w * 0.6f, mid + h * 0.05f, w * 0.74f, mid - lift * 0.3f);
            teeth.cubicTo(w * 0.6f, mid - lift * 0.55f, w * 0.4f, mid - lift * 0.55f, w * 0.26f, mid - lift * 0.3f);
            teeth.close();
            c.drawPath(teeth, fill(Color.argb((int) (170 * curve), 240, 238, 232)));
        }
    }

    /**
     * An open mouth aperture.
     *
     * @param widthK  horizontal extent, 0..1
     * @param openK   vertical aperture, 0..1
     * @param topTeeth show the upper teeth row
     * @param tongue   show the tongue
     */
    private void mouthOpen(Canvas c, float w, float h, float widthK, float openK,
                           boolean topTeeth, boolean tongue) {
        float cx = w * 0.5f;
        float cy = h * 0.55f;
        float hw = w * 0.42f * widthK;
        float hh = h * 0.38f * openK;
        RectF aperture = new RectF(cx - hw, cy - hh, cx + hw, cy + hh);

        Path mouth = new Path();
        mouth.addOval(aperture, Path.Direction.CW);
        c.drawPath(mouth, fill(LeonPalette.MOUTH_INNER));

        c.save();
        c.clipPath(mouth);
        // Darker towards the throat.
        c.drawRect(aperture, vertical(aperture.top, aperture.bottom,
                Color.argb(170, 34, 14, 16), Color.argb(90, 58, 26, 28)));
        if (topTeeth) {
            RectF teeth = new RectF(aperture.left, aperture.top,
                    aperture.right, aperture.top + hh * 0.62f);
            c.drawRoundRect(teeth, hh * 0.18f, hh * 0.18f, fill(LeonPalette.TEETH));
            Paint gap = stroke(Color.argb(70, 150, 146, 138), Math.max(0.4f, hw * 0.035f));
            for (int i = 1; i < 4; i++) {
                float x = aperture.left + aperture.width() * (i / 4f);
                c.drawLine(x, teeth.top, x, teeth.bottom, gap);
            }
        }
        if (tongue) {
            RectF t = new RectF(cx - hw * 0.6f, aperture.bottom - hh * 0.8f,
                    cx + hw * 0.6f, aperture.bottom + hh * 0.3f);
            c.drawOval(t, fill(Color.rgb(176, 88, 92)));
        }
        c.restore();

        // Lip ring around the aperture; thicker for rounded shapes.
        float lipWidth = h * (widthK < 0.5f ? 0.11f : 0.075f);
        c.drawPath(mouth, stroke(Color.argb(205, 118, 66, 58), lipWidth));
    }

    private void mouthPressed(Canvas c, float w, float h) {
        // Bilabial stop: lips compressed together and pushed slightly wider.
        float mid = h * 0.52f;
        Path upper = new Path();
        upper.moveTo(w * 0.08f, mid);
        upper.cubicTo(w * 0.3f, mid - h * 0.15f, w * 0.7f, mid - h * 0.15f, w * 0.92f, mid);
        upper.cubicTo(w * 0.7f, mid - h * 0.02f, w * 0.3f, mid - h * 0.02f, w * 0.08f, mid);
        upper.close();
        c.drawPath(upper, fill(Color.argb(190, 150, 96, 86)));
        Path lower = new Path();
        lower.moveTo(w * 0.08f, mid);
        lower.cubicTo(w * 0.3f, mid + h * 0.19f, w * 0.7f, mid + h * 0.19f, w * 0.92f, mid);
        lower.cubicTo(w * 0.7f, mid + h * 0.03f, w * 0.3f, mid + h * 0.03f, w * 0.08f, mid);
        lower.close();
        c.drawPath(lower, fill(Color.argb(200, 166, 108, 96)));
        c.drawLine(w * 0.08f, mid, w * 0.92f, mid, stroke(Color.argb(220, 92, 48, 44), h * 0.06f));
    }

    private void mouthTeeth(Canvas c, float w, float h) {
        // Labiodental: upper teeth resting on the lower lip.
        float mid = h * 0.5f;
        RectF teeth = new RectF(w * 0.22f, mid - h * 0.2f, w * 0.78f, mid + h * 0.04f);
        c.drawRoundRect(teeth, h * 0.05f, h * 0.05f, fill(LeonPalette.TEETH));
        Paint gap = stroke(Color.argb(80, 150, 146, 138), w * 0.012f);
        for (int i = 1; i < 4; i++) {
            float x = teeth.left + teeth.width() * (i / 4f);
            c.drawLine(x, teeth.top, x, teeth.bottom, gap);
        }
        Path lip = new Path();
        lip.moveTo(w * 0.14f, mid + h * 0.02f);
        lip.cubicTo(w * 0.34f, mid + h * 0.28f, w * 0.66f, mid + h * 0.28f, w * 0.86f, mid + h * 0.02f);
        lip.cubicTo(w * 0.66f, mid + h * 0.08f, w * 0.34f, mid + h * 0.08f, w * 0.14f, mid + h * 0.02f);
        lip.close();
        c.drawPath(lip, fill(Color.argb(225, 170, 104, 92)));
        c.drawPath(lip, stroke(Color.argb(130, 100, 54, 48), h * 0.04f));
    }

    // ------------------------------------------------------------------ neck / torso

    private void neckSkin(Canvas c, float w, float h) {
        Path neck = limb(w, h, w * 0.66f, w * 0.96f, w * 0.03f);
        c.drawPath(neck, vertical(0f, h, LeonPalette.SKIN_SHADOW, LeonPalette.SKIN));
        c.save();
        c.clipPath(neck);
        // Shadow cast by the jaw, and the trapezius spreading towards the shoulders.
        c.drawRect(0f, 0f, w, h, vertical(0f, h * 0.4f, Color.argb(140, 78, 42, 26), Color.TRANSPARENT));
        Paint sinew = stroke(Color.argb(56, 120, 66, 40), w * 0.06f);
        c.drawLine(w * 0.34f, h * 0.2f, w * 0.24f, h * 0.9f, sinew);
        c.drawLine(w * 0.66f, h * 0.2f, w * 0.76f, h * 0.9f, sinew);
        c.restore();
    }

    private void neckTattoo(Canvas c, float w, float h) {
        Paint ink = stroke(LeonPalette.INK_SOFT, w * 0.045f);
        // Vertical script column down one side of the neck plus a band across the base.
        for (int i = 0; i < 4; i++) {
            float y = h * (0.18f + i * 0.16f);
            Path p = new Path();
            p.moveTo(w * 0.16f, y);
            p.cubicTo(w * 0.32f, y - h * 0.05f, w * 0.44f, y + h * 0.06f, w * 0.58f, y);
            c.drawPath(p, ink);
        }
        Path band = new Path();
        band.moveTo(w * 0.06f, h * 0.84f);
        band.cubicTo(w * 0.35f, h * 0.94f, w * 0.65f, h * 0.94f, w * 0.94f, h * 0.84f);
        c.drawPath(band, stroke(LeonPalette.INK_SOFT, w * 0.07f));
        Paint dot = fill(LeonPalette.INK_SOFT);
        for (int i = 0; i < 3; i++) {
            c.drawCircle(w * (0.70f + i * 0.08f), h * (0.30f + i * 0.10f), w * 0.035f, dot);
        }
    }

    private void chestTattoo(Canvas c, float w, float h) {
        // Skin first: this layer sits behind the hoodie and fills its V-neck opening, so it must be
        // opaque or a gap shows through the neckline.
        Path chest = new Path();
        chest.moveTo(w * 0.04f, 0f);
        chest.lineTo(w * 0.96f, 0f);
        chest.cubicTo(w * 0.94f, h * 0.55f, w * 0.74f, h * 0.9f, w * 0.5f, h);
        chest.cubicTo(w * 0.26f, h * 0.9f, w * 0.06f, h * 0.55f, w * 0.04f, 0f);
        chest.close();
        c.drawPath(chest, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));

        c.save();
        c.clipPath(chest);
        // Pectoral separation and collarbones, so the chest reads as muscled but proportionate.
        c.drawLine(w * 0.5f, h * 0.30f, w * 0.5f, h, stroke(Color.argb(92, 96, 52, 32), w * 0.05f));
        Paint clavicle = stroke(Color.argb(70, 124, 70, 44), w * 0.045f);
        Path cl = new Path();
        cl.moveTo(w * 0.1f, h * 0.16f);
        cl.cubicTo(w * 0.3f, h * 0.26f, w * 0.7f, h * 0.26f, w * 0.9f, h * 0.16f);
        c.drawPath(cl, clavicle);

        // Symmetric ink across both pectorals, mirrored about the sternum.
        Paint ink = stroke(LeonPalette.INK_SOFT, w * 0.042f);
        for (int side = 0; side < 2; side++) {
            c.save();
            if (side == 1) mirror(c, w);
            for (int i = 0; i < 4; i++) {
                Path p = new Path();
                float y = h * (0.36f + i * 0.14f);
                p.moveTo(w * 0.46f, y);
                p.cubicTo(w * 0.30f, y - h * 0.08f, w * 0.18f, y + h * 0.06f, w * 0.08f, y - h * 0.02f);
                c.drawPath(p, ink);
            }
            c.restore();
        }
        c.restore();
    }

    private void necklace(Canvas c, float w, float h) {
        // Two chain strands meeting at a pendant, gemstones cycling through the palette.
        Paint chain = stroke(LeonPalette.SILVER, w * 0.026f);
        Path strand = new Path();
        strand.moveTo(w * 0.14f, h * 0.04f);
        strand.cubicTo(w * 0.22f, h * 0.48f, w * 0.40f, h * 0.70f, w * 0.5f, h * 0.74f);
        strand.cubicTo(w * 0.60f, h * 0.70f, w * 0.78f, h * 0.48f, w * 0.86f, h * 0.04f);
        c.drawPath(strand, chain);
        Path inner = new Path();
        inner.moveTo(w * 0.22f, h * 0.05f);
        inner.cubicTo(w * 0.30f, h * 0.38f, w * 0.42f, h * 0.55f, w * 0.5f, h * 0.58f);
        inner.cubicTo(w * 0.58f, h * 0.55f, w * 0.70f, h * 0.38f, w * 0.78f, h * 0.05f);
        c.drawPath(inner, chain);

        // Gems along the outer strand.
        float[][] positions = {
                {0.17f, 0.20f}, {0.22f, 0.40f}, {0.31f, 0.58f}, {0.42f, 0.70f},
                {0.58f, 0.70f}, {0.69f, 0.58f}, {0.78f, 0.40f}, {0.83f, 0.20f},
        };
        for (int i = 0; i < positions.length; i++) {
            int colour = LeonPalette.GEMS[i % LeonPalette.GEMS.length];
            gem(c, w * positions[i][0], h * positions[i][1], w * 0.048f, colour);
        }
        // Central pendant, larger and bezel-set.
        float px = w * 0.5f;
        float py = h * 0.82f;
        float pr = w * 0.10f;
        c.drawCircle(px, py, pr * 1.28f, fill(LeonPalette.GOLD));
        gem(c, px, py, pr, LeonPalette.GEMS[0]);
    }

    private void gem(Canvas c, float cx, float cy, float r, int colour) {
        Paint facet = new Paint(Paint.ANTI_ALIAS_FLAG);
        facet.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
                new int[]{Color.WHITE, colour, Color.argb(255,
                        (int) (Color.red(colour) * 0.45f),
                        (int) (Color.green(colour) * 0.45f),
                        (int) (Color.blue(colour) * 0.45f))},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, facet);
        c.drawCircle(cx, cy, r, stroke(Color.argb(150, 255, 255, 255), r * 0.16f));
    }

    // ------------------------------------------------------------------ clothing

    private void hoodieBody(Canvas c, float w, float h) {
        Path body = new Path();
        // Wide shoulder line, slight taper to the hem.
        body.moveTo(w * 0.06f, h * 0.10f);
        body.cubicTo(w * 0.16f, h * 0.015f, w * 0.34f, h * 0.0f, w * 0.5f, h * 0.0f);
        body.cubicTo(w * 0.66f, h * 0.0f, w * 0.84f, h * 0.015f, w * 0.94f, h * 0.10f);
        body.cubicTo(w * 0.99f, h * 0.40f, w * 0.96f, h * 0.74f, w * 0.92f, h);
        body.lineTo(w * 0.08f, h);
        body.cubicTo(w * 0.04f, h * 0.74f, w * 0.01f, h * 0.40f, w * 0.06f, h * 0.10f);
        body.close();

        // Punch a V-neck out of the garment so the tattooed chest behind it shows through.
        Path vNeck = new Path();
        vNeck.moveTo(w * 0.335f, 0f);
        vNeck.cubicTo(w * 0.36f, h * 0.12f, w * 0.44f, h * 0.22f, w * 0.5f, h * 0.245f);
        vNeck.cubicTo(w * 0.56f, h * 0.22f, w * 0.64f, h * 0.12f, w * 0.665f, 0f);
        vNeck.close();
        body.op(vNeck, Path.Op.DIFFERENCE);

        c.drawPath(body, vertical(0f, h, LeonPalette.HOODIE_HIGHLIGHT, LeonPalette.HOODIE_SHADOW));

        c.save();
        c.clipPath(body);
        // Side shading, so the torso has volume under the breathing scale.
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.4f, Color.argb(120, 0, 0, 0), Color.TRANSPARENT));
        c.drawRect(0f, 0f, w, h, horizontal(w * 0.68f, w, Color.TRANSPARENT, Color.argb(95, 0, 0, 0)));

        // Sleeve seams at the shoulders.
        Paint seam = stroke(Color.argb(120, 88, 90, 100), w * 0.012f);
        Path seamL = new Path();
        seamL.moveTo(w * 0.14f, h * 0.045f);
        seamL.cubicTo(w * 0.08f, h * 0.18f, w * 0.07f, h * 0.30f, w * 0.06f, h * 0.42f);
        c.drawPath(seamL, seam);
        Path seamR = new Path();
        seamR.moveTo(w * 0.86f, h * 0.045f);
        seamR.cubicTo(w * 0.92f, h * 0.18f, w * 0.93f, h * 0.30f, w * 0.94f, h * 0.42f);
        c.drawPath(seamR, seam);

        // Ribbed hem.
        RectF hem = new RectF(w * 0.06f, h * 0.92f, w * 0.94f, h);
        c.drawRect(hem, fill(LeonPalette.HOODIE_SHADOW));
        Paint rib = stroke(Color.argb(60, 110, 112, 122), w * 0.008f);
        for (int i = 1; i < 14; i++) {
            float x = hem.left + hem.width() * (i / 14f);
            c.drawLine(x, hem.top, x, hem.bottom, rib);
        }

        // Fabric folds falling from the chest.
        Paint fold = stroke(Color.argb(46, 120, 124, 136), w * 0.010f);
        c.drawLine(w * 0.34f, h * 0.30f, w * 0.29f, h * 0.88f, fold);
        c.drawLine(w * 0.66f, h * 0.30f, w * 0.71f, h * 0.88f, fold);
        c.drawLine(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.60f, fold);
        c.restore();

        // Collar around the neckline and the two drawstring ends hanging from it.
        Paint collar = stroke(Color.rgb(32, 34, 40), w * 0.035f);
        Path collarPath = new Path();
        collarPath.moveTo(w * 0.325f, 0f);
        collarPath.cubicTo(w * 0.355f, h * 0.125f, w * 0.44f, h * 0.225f, w * 0.5f, h * 0.252f);
        collarPath.cubicTo(w * 0.56f, h * 0.225f, w * 0.645f, h * 0.125f, w * 0.675f, 0f);
        c.drawPath(collarPath, collar);

        Paint cord = stroke(Color.rgb(196, 198, 205), w * 0.014f);
        c.drawLine(w * 0.435f, h * 0.20f, w * 0.415f, h * 0.40f, cord);
        c.drawLine(w * 0.565f, h * 0.20f, w * 0.585f, h * 0.41f, cord);
        Paint tip = fill(Color.rgb(150, 152, 160));
        c.drawCircle(w * 0.415f, h * 0.41f, w * 0.018f, tip);
        c.drawCircle(w * 0.585f, h * 0.42f, w * 0.018f, tip);
    }

    private void hoodiePocket(Canvas c, float w, float h) {
        RectF pocket = new RectF(0f, h * 0.12f, w, h);
        c.drawRoundRect(pocket, w * 0.06f, w * 0.06f, fill(Color.argb(210, 26, 27, 32)));
        c.drawLine(0f, h * 0.18f, w, h * 0.18f, stroke(Color.argb(120, 96, 98, 108), h * 0.05f));
        Paint opening = stroke(Color.argb(160, 10, 10, 12), h * 0.07f);
        c.drawLine(w * 0.02f, h * 0.30f, w * 0.20f, h * 0.24f, opening);
        c.drawLine(w * 0.98f, h * 0.30f, w * 0.80f, h * 0.24f, opening);
    }

    private void hoodDown(Canvas c, float w, float h) {
        // The hood is worn DOWN: a bunched mass sitting behind the shoulders, never over the head.
        Path hood = new Path();
        hood.moveTo(w * 0.18f, 0f);
        hood.cubicTo(w * 0.02f, h * 0.30f, w * 0.0f, h * 0.72f, w * 0.14f, h * 0.94f);
        hood.cubicTo(w * 0.34f, h, w * 0.66f, h, w * 0.86f, h * 0.94f);
        hood.cubicTo(w, h * 0.72f, w * 0.98f, h * 0.30f, w * 0.82f, 0f);
        hood.close();
        c.drawPath(hood, vertical(0f, h, Color.rgb(30, 32, 38), Color.rgb(11, 12, 15)));

        c.save();
        c.clipPath(hood);
        // Gathered folds, which is what makes it read as fabric rather than a slab.
        Paint fold = stroke(Color.argb(90, 104, 108, 120), w * 0.013f);
        for (int i = 0; i < 5; i++) {
            float x = w * (0.20f + i * 0.15f);
            Path p = new Path();
            p.moveTo(x, h * 0.06f);
            p.cubicTo(x - w * 0.04f, h * 0.42f, x + w * 0.03f, h * 0.70f, x - w * 0.01f, h * 0.94f);
            c.drawPath(p, fold);
        }
        c.drawRect(0f, 0f, w, h, vertical(0f, h * 0.5f, Color.argb(110, 0, 0, 0), Color.TRANSPARENT));
        c.restore();

        // Hood opening edge and eyelets.
        c.drawPath(hood, stroke(Color.argb(140, 62, 64, 74), w * 0.016f));
        Paint eyelet = fill(Color.rgb(178, 180, 188));
        c.drawCircle(w * 0.40f, h * 0.14f, w * 0.016f, eyelet);
        c.drawCircle(w * 0.60f, h * 0.14f, w * 0.016f, eyelet);
    }

    private void sleeve(Canvas c, float w, float h, boolean rightArm) {
        c.save();
        // Mirror so the three-stripe detail always sits on the arm's outer edge.
        if (rightArm) mirror(c, w);
        Path arm = limb(w, h, w * 0.94f, w * 0.68f, w * 0.05f);
        c.drawPath(arm, vertical(0f, h, LeonPalette.HOODIE_HIGHLIGHT, LeonPalette.HOODIE_SHADOW));
        c.save();
        c.clipPath(arm);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.45f, Color.argb(115, 0, 0, 0), Color.TRANSPARENT));
        // Generic three-stripe sleeve detail. No brand mark is reproduced.
        Paint stripe = fill(LeonPalette.SLEEVE_STRIPE);
        for (int i = 0; i < 3; i++) {
            float x = w * (0.13f + i * 0.115f);
            c.drawRoundRect(new RectF(x, h * 0.08f, x + w * 0.062f, h * 0.70f),
                    w * 0.03f, w * 0.03f, stripe);
        }
        // Elbow crease.
        c.drawLine(w * 0.2f, h * 0.80f, w * 0.8f, h * 0.86f,
                stroke(Color.argb(70, 110, 114, 126), w * 0.02f));
        c.restore();
        // Ribbed cuff.
        c.drawRoundRect(new RectF(w * 0.14f, h * 0.90f, w * 0.86f, h),
                w * 0.05f, w * 0.05f, fill(Color.rgb(26, 28, 33)));
        c.restore();
    }

    private void forearm(Canvas c, float w, float h, boolean rightArm) {
        c.save();
        if (rightArm) mirror(c, w);
        Path arm = limb(w, h, w * 0.92f, w * 0.62f, w * 0.06f);
        c.drawPath(arm, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));
        c.save();
        c.clipPath(arm);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.4f, Color.argb(95, 70, 38, 22), Color.TRANSPARENT));
        // Forearm muscle definition, kept subtle so he stays proportionate rather than inflated.
        Paint sinew = stroke(Color.argb(52, 118, 64, 38), w * 0.055f);
        c.drawLine(w * 0.62f, h * 0.10f, w * 0.50f, h * 0.62f, sinew);
        c.drawLine(w * 0.34f, h * 0.14f, w * 0.40f, h * 0.58f, sinew);

        // Tattoo sleeve: bands plus geometric line work.
        Paint ink = stroke(LeonPalette.INK_SOFT, w * 0.05f);
        for (int i = 0; i < 3; i++) {
            float y = h * (0.16f + i * 0.09f);
            Path band = new Path();
            band.moveTo(w * 0.05f, y);
            band.cubicTo(w * 0.35f, y + h * 0.035f, w * 0.65f, y + h * 0.035f, w * 0.95f, y);
            c.drawPath(band, ink);
        }
        Paint fine = stroke(LeonPalette.INK_SOFT, w * 0.03f);
        for (int i = 0; i < 4; i++) {
            float x = w * (0.20f + i * 0.18f);
            Path zig = new Path();
            zig.moveTo(x, h * 0.50f);
            zig.lineTo(x + w * 0.09f, h * 0.62f);
            zig.lineTo(x, h * 0.74f);
            zig.lineTo(x + w * 0.07f, h * 0.86f);
            c.drawPath(zig, fine);
        }
        c.restore();
        c.restore();
    }

    private void hand(Canvas c, float w, float h, boolean rightHand) {
        c.save();
        if (rightHand) mirror(c, w);
        Path palm = new Path();
        palm.moveTo(w * 0.18f, 0f);
        palm.cubicTo(w * 0.03f, h * 0.22f, w * 0.05f, h * 0.66f, w * 0.20f, h * 0.90f);
        palm.cubicTo(w * 0.38f, h, w * 0.66f, h, w * 0.82f, h * 0.86f);
        palm.cubicTo(w * 0.97f, h * 0.62f, w * 0.95f, h * 0.20f, w * 0.80f, 0f);
        palm.close();
        c.drawPath(palm, vertical(0f, h, LeonPalette.SKIN, LeonPalette.SKIN_SHADOW));

        c.save();
        c.clipPath(palm);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.4f, Color.argb(85, 70, 38, 22), Color.TRANSPARENT));
        // Relaxed, slightly curled fingers.
        Paint groove = stroke(Color.argb(105, 108, 58, 34), w * 0.045f);
        for (int i = 0; i < 3; i++) {
            float x = w * (0.34f + i * 0.17f);
            c.drawLine(x, h * 0.34f, x - w * 0.02f, h * 0.94f, groove);
        }
        // Thumb along the near edge.
        c.drawLine(w * 0.20f, h * 0.28f, w * 0.12f, h * 0.64f, stroke(Color.argb(95, 108, 58, 34), w * 0.07f));
        // Knuckle tattoo dots, tying the hand to the arm ink.
        Paint ink = fill(LeonPalette.INK_SOFT);
        for (int i = 0; i < 3; i++) {
            c.drawCircle(w * (0.36f + i * 0.17f), h * 0.30f, w * 0.032f, ink);
        }
        c.restore();

        // Ring, matching the jewellery in the character spec.
        c.drawLine(w * 0.505f, h * 0.52f, w * 0.63f, h * 0.52f, stroke(LeonPalette.GOLD, w * 0.075f));
        c.restore();
    }

    private void pantLeg(Canvas c, float w, float h, boolean thigh) {
        Path leg = thigh
                ? limb(w, h, w * 0.98f, w * 0.78f, w * 0.05f)
                : limb(w, h, w * 0.92f, w * 0.70f, w * 0.03f);
        c.drawPath(leg, vertical(0f, h, LeonPalette.PANTS_HIGHLIGHT, LeonPalette.PANTS));
        c.save();
        c.clipPath(leg);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.42f, Color.argb(120, 0, 0, 0), Color.TRANSPARENT));
        Paint fold = stroke(Color.argb(58, 104, 108, 120), w * 0.026f);
        for (int i = 0; i < 4; i++) {
            float y = h * (0.28f + i * 0.17f);
            c.drawLine(w * 0.18f, y, w * 0.82f, y + h * 0.022f, fold);
        }
        // Outer seam.
        c.drawLine(w * 0.78f, 0f, w * 0.72f, h, stroke(Color.argb(70, 118, 122, 134), w * 0.02f));
        c.restore();
        if (!thigh) {
            // Gathered cuff at the ankle.
            c.drawRoundRect(new RectF(w * 0.12f, h * 0.90f, w * 0.88f, h),
                    w * 0.06f, w * 0.06f, fill(Color.rgb(24, 25, 30)));
        }
    }

    private void sneaker(Canvas c, float w, float h, boolean rightFoot) {
        c.save();
        if (rightFoot) mirror(c, w);
        // Upper.
        Path upper = new Path();
        upper.moveTo(w * 0.10f, h * 0.70f);
        upper.cubicTo(w * 0.12f, h * 0.18f, w * 0.34f, h * 0.02f, w * 0.52f, h * 0.10f);
        upper.cubicTo(w * 0.76f, h * 0.22f, w * 0.96f, h * 0.48f, w * 0.98f, h * 0.70f);
        upper.lineTo(w * 0.10f, h * 0.70f);
        upper.close();
        c.drawPath(upper, vertical(0f, h, LeonPalette.SNEAKER, LeonPalette.SNEAKER_SHADOW));

        c.save();
        c.clipPath(upper);
        c.drawRect(0f, 0f, w, h, horizontal(0f, w * 0.3f, Color.argb(60, 140, 145, 155), Color.TRANSPARENT));
        // Toe cap and lace panel.
        c.drawLine(w * 0.70f, h * 0.22f, w * 0.80f, h * 0.66f,
                stroke(Color.argb(120, 188, 192, 200), h * 0.05f));
        Paint lace = stroke(Color.argb(190, 170, 174, 184), h * 0.045f);
        for (int i = 0; i < 4; i++) {
            float x = w * (0.28f + i * 0.10f);
            c.drawLine(x, h * 0.20f + i * h * 0.03f, x + w * 0.09f, h * 0.30f + i * h * 0.03f, lace);
        }
        c.restore();

        // Midsole and outsole.
        RectF mid = new RectF(w * 0.04f, h * 0.62f, w, h * 0.86f);
        c.drawRoundRect(mid, h * 0.16f, h * 0.16f, fill(LeonPalette.SNEAKER));
        RectF sole = new RectF(w * 0.04f, h * 0.80f, w, h);
        c.drawRoundRect(sole, h * 0.14f, h * 0.14f, fill(LeonPalette.SNEAKER_SOLE));
        c.drawLine(w * 0.06f, h * 0.80f, w * 0.98f, h * 0.80f,
                stroke(Color.argb(110, 176, 180, 190), h * 0.035f));
        // Heel tab.
        c.drawRoundRect(new RectF(0f, h * 0.44f, w * 0.16f, h * 0.72f),
                h * 0.1f, h * 0.1f, fill(LeonPalette.SNEAKER_SHADOW));
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
                        Color.argb(52, Color.red(a), Color.green(a), Color.blue(a)),
                        Color.argb(96, Color.red(a), Color.green(a), Color.blue(a)),
                        Color.argb(0, Color.red(a), Color.green(a), Color.blue(a)),
                },
                new float[]{0f, 0.55f, 0.80f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, Math.min(cx, cy), glow);
    }
}
