# Leon Rico — character reference

The look Leon must match. He is a **photoreal / 3D-render** character, not a stylised drawing.

| File | What it is |
|---|---|
| `leon-character-sheet.png` | The master character sheet: hero shot, 4-way turnaround, head-tattoo detail, 8 expression frames, 8 natural-movement frames. |
| `leon-reference.png` / `.webp` | The original 180x224 reference that shipped inside the PR #164 prototype. |

## Identity, from the sheet

Bald, with an ornate filigree tattoo covering the whole crown and wrapping the sides and back of the
skull. Dark angular sunglasses, opaque. Heavy neck and throat tattoos, chest tattoos, full arm and
hand tattoos. A multicolour gemstone chain — round stones in gold settings, alternating colours.
Silver watch. Black Adidas zip hoodie with white three-stripe sleeves and the trefoil on the chest,
worn with the hood down; black cargo joggers; white sneakers. Lean and muscular, proportionate.

## Why the sheet cannot be used as source art

Measured from `leon-character-sheet.png` (1223x1286):

- The turnaround FRONT figure is only about **100 x 330 px**. The overlay needs roughly 396 px wide
  at 3x density, so it would need a 4x upscale.
- Worse, the figure cannot be cut out. The black hoodie and the dark background are the same value:

  | | median luminance |
  |---|---|
  | Hoodie | 21 |
  | Background | 28 |
  | Hoodie, 95th percentile | 26.7 |
  | Background, 5th percentile | 26.3 |

  Any mask that removes the background also eats the garment. Attempting it produced a figure with
  the hoodie and joggers punched out.

This is a contact sheet, which is the right thing for reviewing a character and the wrong thing for
rigging one.

## What would rig Leon properly

One file is enough:

**`leon-front.png` — the FRONT turnaround pose, full resolution, on transparency.**

- Front-facing, standing, arms down, the neutral pose from the turnaround.
- At least **800 px tall**; 1600 is better. The whole figure, crown to soles, nothing cropped.
- **Alpha channel**, background removed at the source. This is the important part — it cannot be
  recovered afterwards from a dark background, as measured above.
- Any render size works; the rig scales it.

Drop it into `apps/leon-android/app/src/main/assets/leon/` next to a `leon-front.txt` holding three
numbers — the source y of the crown, the source y of the soles, and the source x of the centre line.
`PhotoLayerArtProvider` then slices it into the rig's layers automatically and Leon is photoreal,
still animated, with no other change.

### Useful but optional

- The 8 **expression frames** at full resolution, cropped to the head. These give real mouth shapes
  for the viseme set instead of synthesised ones.
- The **head-tattoo detail** views, if the head is ever to turn far enough to show the sides.
