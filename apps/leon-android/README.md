# Leon Companion — Android

Leon is an animated character who lives on the user's Android screen: visible
above other apps, draggable, minimisable, and continuously animated by a real
skeletal rig and state machine rather than by moving a picture around.

## What it does

- Stays on screen above normal apps, on a fully transparent overlay window —
  no card, no rounded rectangle, no visible container.
- Animates independently while idle: breathing, natural blinking, head drift,
  eye saccades, posture and weight shifts, shoulder movement.
- Nine states — idle, listening, thinking, speaking, happy, serious, sleepy,
  attention, minimised — with cross-faded transitions and randomised timing, so
  there is no robotic loop.
- Ten mouth shapes covering the viseme set (closed, neutral-open, A, E, O, U,
  M/B/P, F/V, smile, frown), driven by
  `LeonLipSyncController.onViseme(viseme, intensity, durationMs)` — the contract
  a speech pipeline will call.
- Reacts to being tapped by looking at where it was touched.
- Drag to move, double tap to minimise or restore, long press for quick
  controls (Chat, Minimise, Hide for a while, Settings).
- Remembers position and state across a rotation, a process kill, an unlock and
  a reboot.
- Stops rendering entirely while the screen is off; 20fps idle, 60fps when
  something is actually moving.

Voice and the AI brain are deliberately not in this build.
`LeonConversationController` reports `NO_BACKEND` rather than inventing a reply.

## Architecture

```
rig/      Skeleton: Mat2D, Bone, RigPart, Rig, LeonRig (27 bones, 45 layers)
anim/     LeonChannel + LeonPose control surface, LeonRigBinder, behaviours
          (breath, blink, head drift, gaze, posture, gesture, nod),
          LeonStateProfile, LeonAnimationController, LeonLipSyncController
state/    LeonState, LeonStateController, LeonConversationController
asset/    LeonArtProvider, DevRigArt, AssetDirArtProvider, LeonAssetRepository
render/   LeonRenderer, LeonCharacterView
overlay/  LeonOverlayService, LeonQuickControls, LeonPrefs, OverlayPlacement,
          ScreenMetrics
```

`rig/`, `anim/`, `state/`, `util/` and `OverlayPlacement` contain **no Android
types**, so the whole animation system is covered by plain JVM unit tests.

Two indirections keep the character replaceable:

- **`LeonChannel`** is the only way anything reaches a bone. States,
  behaviours, expressions and lip sync all write channels; `LeonRigBinder` is
  the only class that knows how a channel becomes a transform.
- **`LeonArtProvider`** is the only way the renderer sees artwork — a logical
  art key in, a bitmap out.

## Character artwork

Leon is drawn in code by `ProceduralLeonArt`, which renders all 40 layers from
his character specification — bald head with a wrap-around skull tattoo,
translucent dark sunglasses, neck / chest / arm tattoos, earring and ring, a
multicolour gemstone necklace, a black hoodie worn hood-down, black pants and
white sneakers. No PNGs ship with the app, so he is resolution-independent, adds
nothing to the APK, and his appearance is under version control.

See **`docs/leon-preview/`** for frames rendered straight from the shipped rig,
including a blink strip and the viseme set, so he can be reviewed without a
device.

Custom artwork can replace him layer by layer — drop PNGs into
`app/src/main/assets/leon/` and they win per layer, no code change.
**`docs/LEON_CHARACTER_ASSET_SPEC.md`** gives every file, size and pivot, plus
the layers with non-obvious requirements (the eyelid's top pivot, the hoodie's
transparent V-neck, translucent lenses).

## Build and test

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest     # 98 unit tests
gradle :app:lintDebug
gradle :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

CI runs all three on pushes to the Leon branches and on pull requests, and
uploads the APK plus the test and lint reports.

## Installing

1. Install the debug APK.
2. Open **Leon Companion** and tap **Enable Leon**.
3. Grant **Display over other apps** when asked.

Leon appears at the right-hand side of the screen. Tap him to open the control
centre, which has a live preview, the current state, and a button per animation
state.

### Device notes

- **Display over other apps** is a special permission and is revocable; the app
  re-checks it on every entry point.
- Android hides all overlays over secure surfaces such as the lock screen and
  permission dialogs. That is correct behaviour and is not worked around.
- Several OEMs (Xiaomi, Oppo, Vivo, Huawei, and Samsung's stricter battery
  modes) additionally require **auto-start** to be allowed by hand, or they will
  kill the foreground service after a reboot. The control centre links to the
  app-info screen; this cannot be fixed in code.

## Design decisions

`docs/LEON_ANDROID_AUDIT.md` records the audit of the previous prototype, why a
single `ImageView` cannot meet the requirement, the Android constraints that
apply to a persistent overlay, and why Rive, Live2D and Spine were each
evaluated and set aside in favour of a custom skeletal rig — along with what
would have to change to adopt Rive later.
