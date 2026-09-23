# Prompts for generating Leon's rig source art

The character sheet was generated in ChatGPT. These are the prompts to get assets that can actually
be rigged. **Attach `leon-character-sheet.png` to the conversation with each prompt** — without it
the character will drift.

## Why the background colour matters

Image generators do not output a real alpha channel. A request for "transparent PNG" returns an
opaque image, sometimes with a checkerboard pattern painted into it.

So the background has to be a colour that can be removed afterwards, which means it must be far away
from every colour Leon is wearing. Measured from the existing sheet:

| | median luminance |
|---|---|
| Hoodie | 21 |
| Background of the sheet | 28 |

Those are the same value, which is why the sheet cannot be cut out. The requirement is therefore:

- **Chroma green `#00FF00`** — first choice. Nothing on Leon is green.
- **Vivid magenta `#FF00FF`** — fallback.
- **Never white** (his sneakers are white), **never grey or black** (his outfit is black), never a
  gradient, never a studio backdrop.

## Prompt 1 — the body. This is the one that unblocks everything

> Using the attached character sheet as the exact reference, generate a single full-body image of
> this same man — same face, same head tattoo, same sunglasses, same neck and chest tattoos, same
> arm and hand tattoos, same multicolour gemstone chain, same silver watch, same black Adidas hoodie
> with white three-stripe sleeves worn with the hood DOWN, same black cargo joggers, same white
> sneakers. Photorealistic, identical style to the reference. Do not restyle him.
>
> Pose: standing straight, facing the camera dead-on, symmetrical, no perspective or angle. Arms
> hanging down and held slightly away from the body so there is a clear gap of background visible
> between each arm and the torso. Hands out of the pockets, open and relaxed, fingers visible. Feet
> flat, legs slightly apart with background visible between them. Neutral expression, mouth closed.
>
> Background: flat solid chroma green #00FF00, filling the entire frame edge to edge. No shadow, no
> floor, no reflection, no vignette, no gradient, no props, no text, no logos or watermarks added.
>
> Framing: portrait orientation, the complete figure from the top of the head to the soles of the
> shoes, fully inside the frame with a small even margin. Nothing cropped — especially not the feet.
> Even, soft, frontal lighting with no deep shadows. Highest resolution available.

Then download it at **full size** as PNG.

### What to check before sending it over

1. **Feet not cropped.** The most common failure.
2. **A visible gap of green between each arm and the torso**, and between the legs. If the arms
   touch the body, the arm layers crop torso pixels with them.
3. **Hands visible**, not in pockets — the turnaround has them pocketed, which leaves no hand art.
4. **Hood down**, flat green background, no drop shadow under him.

If any of those are wrong, regenerate rather than accept it. Everything downstream inherits it.

## Prompt 2 — expression heads. Optional, makes the mouth real

Worth doing, because without it the mouth shapes are synthesised rather than his.

> Using the attached character sheet as the exact reference, generate eight head-and-shoulders
> images of this same man, one per image, all framed identically: facing the camera dead-on, head
> the same size and in the same position in every frame, flat solid chroma green #00FF00 background,
> even frontal lighting, photorealistic, identical style to the reference.
>
> The eight: 1 neutral mouth closed, 2 mouth open mid-speech showing upper teeth, 3 wide open mouth
> saying "ah", 4 lips rounded saying "oo", 5 lips pressed together, 6 lower lip touching the upper
> teeth saying "f", 7 smiling with teeth, 8 serious with lips pressed and brows lowered.
>
> Keep the head at exactly the same scale and position across all eight so they can be overlaid.

The last sentence is the one that matters. If the head moves or changes size between frames, the
mouth will jump when Leon speaks.

## What happens when the files arrive

Drop the body render in as `apps/leon-android/app/src/main/assets/leon/leon-front.png`.
`PhotoLayerArtProvider` slices it into the rig's layers and Leon becomes photoreal while staying
animated — the head still turns, the chest still breathes, the shoulders still move independently.
No other change is needed.
