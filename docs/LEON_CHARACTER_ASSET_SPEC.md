# Leon character asset specification

How to replace Leon's built-in artwork with custom art, and what a file set has
to contain.

**This is optional.** Leon ships complete: `ProceduralLeonArt` draws all 40
layers in code, and `docs/leon-preview/` shows what that looks like. This
document is for replacing him with artist-drawn work — a different style, a
licensed likeness, a higher-fidelity render.

The overlay, skeleton, state machine and lip-sync contract do not change when
the art is replaced. Custom files win per layer, so a partial set is fine: any
layer you do not supply keeps the built-in drawing.

## 1. Character identity — do not change

Leon is one specific person. The art must match him, not reinterpret him.

- Adult male.
- Bald / shaved head.
- Lean muscular physique. Muscular but **proportionate** — not an inflated
  upper body.
- Dark sunglasses, worn at all times.
- A tattoo on the side of the head that continues around the back of the skull
  and reaches the opposite side. It reads as one continuous piece, not two
  separate temple pieces.
- Neck tattoos.
- Chest tattoos.
- Arm tattoos.
- Jewellery: an earring and a ring.
- A multicolour gemstone necklace.
- A black Adidas hoodie, **hood down** behind the shoulders — never up, never
  over the head.
- Black pants.
- White sneakers.
- Slightly stylised premium animated / 3D-animation appearance.

### Branding note

The built-in artwork draws a generic three-stripe sleeve detail and **no brand
logo or wordmark**. Reproducing a trademark in shipped artwork is a product and
legal decision, not an engineering default. If licensed Adidas branding is to
appear, that call and the licence belong to the product owner; the rig itself is
indifferent either way.

## 2. Design space and framing

The rig is authored in a **384 x 768** box, origin top-left, +y pointing down.

| Landmark | y |
|---|---|
| Crown of the head | 46 |
| Head centre | 111 |
| Chin | ~176 |
| Base of neck | 172 |
| Shoulder line | 214 |
| Hips | 392 |
| Knees | ~547 |
| Ankles | ~697 |
| Sneaker sole | 738 |
| Ground line | 756 |

Horizontal centre is x = 192. The figure is a stylised ~5.3-head proportion.
Every layer must stay inside the box; `RigTest.everyLayerStaysInsideTheDesignBoxAtRest`
enforces this and will fail the build if a layer overruns it.

## 3. Deliverables

Each rig layer is **one PNG with a transparent background**, named after its art
key. Sizes below are in design units. Deliver at **2x or 3x** those pixel
dimensions — the renderer maps each bitmap onto its layer rectangle, so art
resolution is independent of the rig and higher is fine.

The pivot column is the normalised point within the image that the rig rotates
and scales the layer about. It is a property of the rig, not of the file, but
the art must be composed so that point lands in the anatomically correct place.

| File | Size (design units) | Pivot (x, y) |
|---|---|---|
| `aura.png` | 300 x 300 | 0.5, 0.5 |
| `brow_l.png` | 32 x 11 | 0.5, 0.5 |
| `brow_r.png` | 32 x 11 | 0.5, 0.5 |
| `chest_tattoo.png` | 58 x 72 | 0.5, 0.5 |
| `ear_l.png` | 20 x 34 | 0.5, 0.5 |
| `ear_r.png` | 20 x 34 | 0.5, 0.5 |
| `earring.png` | 11 x 18 | 0.5, 0.2 |
| `eye_white.png` | 30 x 18 | 0.5, 0.5 |
| `eyelid.png` | 32 x 22 | 0.5, 0 |
| `forearm_tattoo_l.png` | 42 x 112 | 0.5, 0.42 |
| `forearm_tattoo_r.png` | 42 x 112 | 0.5, 0.42 |
| `hand_l.png` | 42 x 52 | 0.5, 0.3 |
| `hand_r.png` | 42 x 52 | 0.5, 0.3 |
| `head_bald.png` | 104 x 130 | 0.5, 0.5 |
| `head_tattoo_wrap.png` | 104 x 92 | 0.5, 0.5 |
| `hood_down.png` | 150 x 100 | 0.5, 0.12 |
| `hoodie_body.png` | 176 x 200 | 0.5, 0.5 |
| `hoodie_pocket.png` | 110 x 46 | 0.5, 0.5 |
| `iris.png` | 14 x 14 | 0.5, 0.5 |
| `mouth_a.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_closed.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_e.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_frown.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_fv.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_mbp.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_neutral_open.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_o.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_smile.png` | 46 x 34 | 0.5, 0.25 |
| `mouth_u.png` | 46 x 34 | 0.5, 0.25 |
| `neck_skin.png` | 48 x 60 | 0.5, 0.5 |
| `neck_tattoo.png` | 48 x 58 | 0.5, 0.5 |
| `necklace.png` | 92 x 76 | 0.5, 0.08 |
| `nose.png` | 17 x 24 | 0.5, 0.4 |
| `pant_leg.png` | 54 x 150 | 0.5, 0.5 |
| `pant_shin.png` | 46 x 152 | 0.5, 0.5 |
| `sleeve_upper_l.png` | 50 x 124 | 0.5, 0.42 |
| `sleeve_upper_r.png` | 50 x 124 | 0.5, 0.42 |
| `sneaker_l.png` | 78 x 46 | 0.5, 0.5 |
| `sneaker_r.png` | 78 x 46 | 0.5, 0.5 |
| `sunglasses.png` | 112 x 40 | 0.5, 0.5 |

### Notes on specific layers

- **`eyelid.png`** — pivot is the **top edge**. The rig closes a blink by
  scaling this layer's height from ~0.05 to 1.0, so it must be drawn as a lid
  that fully covers the eye at full height, with the lash line along its
  **bottom** edge. This is the single most important layer to get right; a lid
  drawn centred will not blink correctly.
- **`sunglasses.png`** — the lenses must be **translucent**, around 80%
  opacity (the built-in artwork uses `argb(206, 12, 14, 20)`). Fully opaque
  lenses hide the blink and the gaze, which the acceptance test requires to be
  visible on a phone.
- **`hoodie_body.png`** — must carry a **transparent V-neck cut-out** at the
  top centre, roughly from x 0.335w to 0.665w and down to 0.245h. The neck and
  chest tattoo layers draw *behind* the hoodie and show through this opening.
  Without the cut-out the chest tattoo is invisible.
- **`chest_tattoo.png`** — must be **opaque skin plus ink** across its whole
  area, not ink on transparency. It fills the hoodie's neckline opening; a
  transparent version leaves a hole in Leon's chest.
- **`neck_skin.png` / `neck_tattoo.png`** — also draw behind the hoodie. The
  top of the neck is hidden behind the head, so its upper edge need not be
  finished.
- **`sleeve_upper_l` / `_r`, `forearm_tattoo_l` / `_r`, `sneaker_l` / `_r`,
  `hand_l` / `_r`, `ear_l` / `_r`, `brow_l` / `_r`** — mirrored pairs. Supply
  both; do not assume the renderer flips anything.
- **`eye_white.png` and `iris.png`** are shared by both eyes, as is
  `eyelid.png`. One file each.
- **`pant_leg.png`** (thigh) and **`pant_shin.png`** are each shared by both
  legs.
- **The ten mouth layers** all occupy the same slot on the jaw bone and are
  cross-faded by alpha. They must be drawn to register exactly on top of one
  another, or the mouth will jitter as visemes change. Required set: closed,
  neutral-open, A, E, O, U, MBP, FV, smile, frown.
- **`aura.png`** is a presentation-only state glow behind Leon. It is the one
  layer allowed to extend past the design box, and it never carries character
  animation. It may be omitted, in which case nothing is drawn for it.

## 4. Colour reference

`ai.leon.companion.asset.LeonPalette` holds the built-in artwork's exact values.
Treat them as a starting point to match, not as a constraint — but keep the
hoodie, pants and lenses genuinely dark, and the sneakers genuinely white, so
Leon's silhouette stays readable at the minimised size (62 x 124 dp).

## 5. Rig controls the art must support

The art does not need to *encode* these — the skeleton drives them — but it must
be drawn so they read correctly. Full definitions are in
`ai.leon.companion.anim.LeonChannel`.

- **Head** — yaw, pitch, roll. Yaw is a 2.5D solve: the head bone shifts and
  foreshortens while the face layers slide inside it, so the head art should be
  drawn front-on with a little headroom at the sides.
- **Face** — per-eye blink, gaze, per-side brow raise and lower.
- **Expressions** — neutral, slight smile, serious, thinking, listening,
  sleepy.
- **Mouth** — the ten viseme layers above, plus a jaw bone that rotates.
- **Body** — chest breathing, per-side shoulders, upper-body posture, torso
  twist, weight shift.
- **Arms** — relaxed, small conversational gesture, hand-to-chin, point.

## 6. Installing the art

Two locations, checked in this order:

1. **`<app files dir>/leon-art/`** — for trying art on a device without
   rebuilding:
   ```
   adb push head_bald.png /sdcard/Download/
   adb shell run-as ai.leon.companion mkdir -p files/leon-art
   adb shell run-as ai.leon.companion cp /sdcard/Download/head_bald.png files/leon-art/
   ```
2. **`apps/leon-android/app/src/main/assets/leon/`** — the shipped location.
   Create the directory and drop the PNGs in; no code or Gradle change is
   needed.

`.webp` is accepted as well as `.png`.

## 7. Mixed sets are reported, not hidden

`LeonAssetRepository` resolves each layer independently: a custom art file wins,
otherwise the built-in artwork draws that layer. `report()` names how many layers
came from each source and lists any the custom set does not cover, and the
control centre shows that line on screen — so a half-replaced Leon is visibly
half-replaced rather than silently mixed.
