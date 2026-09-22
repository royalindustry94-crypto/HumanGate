# Leon Android companion — audit and engine decision

Audit of `apps/leon-android/` as merged in PR #164, and the technical decision
that follows from it. Written before any code in this change was modified.

## 1. What already worked

| Area | Assessment |
|---|---|
| Overlay persistence | Correct. Foreground service with `specialUse` type, `TYPE_APPLICATION_OVERLAY`, `START_STICKY`, and `FLAG_NOT_FOCUSABLE`/`FLAG_LAYOUT_IN_SCREEN`. Leon genuinely stayed above other apps. |
| Boot restoration | `BootReceiver` handled `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`, guarded on `Settings.canDrawOverlays`, and caught the OEM auto-start failure rather than crashing. |
| Unlock restoration | A dynamically registered receiver on `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` re-showed the overlay. |
| Dragging | Worked, via raw touch coordinates and `updateViewLayout`. |
| Permission flow | `ACTION_MANAGE_OVERLAY_PERMISSION` plus a `POST_NOTIFICATIONS` request on API 33+. |
| Build | Clean AGP 8.7.3 / compileSdk 35 / minSdk 26 setup, and the workflow produced an installable debug APK. |

The Android *plumbing* was sound. That part has been kept and extended.

## 2. What was prototype or placeholder

1. **The character was one flat bitmap.** A 5 KB WebP, base64-encoded into
   `res/raw/leon_base64.txt`, decoded at runtime and pushed into an `ImageView`
   with `CENTER_CROP`. One layer, zero independently movable parts.
2. **The "animation" transformed the entire view.** `startIdleAnimation()` ran
   `ObjectAnimator` on the host `FrameLayout`'s `TRANSLATION_Y`, `SCALE_X` and
   `SCALE_Y`. Leon did not move; the whole rectangle containing him bobbed and
   pulsed. This is precisely the approach the milestone forbids.
3. **There was no state model.** No idle / listening / thinking / speaking
   distinction, no transitions, nothing for a UI or an AI layer to drive.
4. **No face, no mouth, no blink.** Nothing in the codebase could have
   produced them — there was no eyelid, jaw or mouth to move.
5. **A visible dark card.** An opaque `GradientDrawable` at
   `argb(235,12,12,16)` with a 24dp radius and a border, plus a status dot.
   Leon read as a chat-head tile, not a character standing on the screen.
6. **Nothing persisted.** Position was hardcoded to `(12dp, 150dp)` on every
   start; there was no minimised form and no saved state.
7. **The activity auto-started the service** from `onResume` whenever the
   permission was held, which takes the decision away from the user.
8. **No tests at all**, and no test dependency in `app/build.gradle`.
9. **The conversation shell returned a hardcoded string** regardless of input.

## 3. Why the `ImageView` approach cannot meet the requirement

The requirement is independently controllable parts. A single `ImageView`
offers exactly one transform (`View`'s matrix) plus one bitmap. From that you
can derive translate, scale, rotate and alpha — all of which apply to *every
pixel at once*.

Concretely, none of the following is expressible:

- **Blinking** needs the eyelids to move while the head does not.
- **Breathing** needs the chest to expand while the head stays put.
- **A head turn** needs the head to rotate and foreshorten while the torso,
  hips and feet stay planted.
- **Shoulder and arm motion** needs four or more limb segments on independent
  transforms, with the correct depth ordering relative to the torso.
- **Visemes** need the mouth region to change shape while the rest of the
  face is unchanged.

Layering more `ImageView`s would technically allow it, but `View` transforms
do not compose hierarchically the way bones do — a child `View`'s transform is
not concatenated with its parent's in a usable rig sense, every part costs a
full `View` with measure/layout, and there is nowhere to express a skeleton. A
rig is the right structure, not a workaround.

## 4. Android constraints that apply to a persistent overlay

- **`SYSTEM_ALERT_WINDOW` is a special permission.** It must be granted
  through `ACTION_MANAGE_OVERLAY_PERMISSION`; it is not a runtime permission
  and cannot be requested inline. It is also revocable at any time, so every
  entry point re-checks `Settings.canDrawOverlays`.
- **`TYPE_APPLICATION_OVERLAY` is the only legal type** since API 26. The old
  `TYPE_PHONE` / `TYPE_SYSTEM_ALERT` types are blocked.
- **The system hides overlays over secure surfaces** — the lock screen, some
  system dialogs, the permission dialogs themselves — and over windows marked
  `FLAG_SECURE`. This is correct behaviour and is not something to defeat.
  Since Android 12, overlays are also hidden while a permission dialog is
  showing, which is why the code never assumes it is visible.
- **A foreground service is required** to keep the process alive reliably. On
  API 34+ it needs a declared type; `specialUse` with a
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` is the applicable one for a persistent
  user-visible companion, and it needs
  `FOREGROUND_SERVICE_SPECIAL_USE`. The ongoing notification is not optional
  and is not dismissible.
- **Background FGS starts are restricted** from API 31. `BOOT_COMPLETED` is an
  allowed exemption, but many OEMs (Xiaomi, Oppo, Vivo, Huawei, Samsung's
  aggressive battery modes) additionally require auto-start to be enabled by
  hand and will kill the service otherwise. This cannot be solved in code; the
  app surfaces it and links to the app-info screen.
- **`registerReceiver` needs an export flag** from API 34 for dynamically
  registered receivers, so the screen receiver passes
  `Context.RECEIVER_NOT_EXPORTED`.
- **Display cutouts and system bars** must be honoured via
  `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` plus real inset values.
  `WindowMetrics` provides them on API 30+; below that the platform dimens are
  the best available source.
- **`SurfaceView` cannot be transparent inside an overlay window** — it
  punches a hole through the window. A hardware-accelerated custom `View` is
  the correct surface for a transparent animated character.
- **Battery and thermals.** An always-on renderer that takes every vsync for
  hours is not acceptable, and drawing while the screen is off is pure waste.

## 5. Animation technologies evaluated

| Option | Verdict |
|---|---|
| **Rive** (`app.rive.runtime.kotlin:rive-android`) | Technically the strongest fit — bones, mesh deformation, a real state machine, numeric/boolean/trigger inputs, good Android performance. **Rejected because the runtime can only play a `.riv` file, which is a binary produced by the Rive editor.** No `.riv` can be authored from code or by hand, so adopting Rive today would mean shipping the runtime with no character in it, and the only way to show anything would be to fall back to the static bitmap — explicitly ruled out. Rive remains the recommended destination once an artist can deliver a `.riv`; the channel-based control surface in this change maps onto Rive inputs almost one-to-one. |
| **Live2D Cubism** | Same blocker, plus worse. Models are `.moc3` files authored only in Cubism Editor, the SDK is not distributed through Maven Central (a manual zip download, which a CI build cannot pin), and commercial use is licence-gated. Rejected. |
| **Spine** (`spine-android`) | Skeleton JSON *is* hand-authorable, which is a real advantage. But the Spine Runtimes License requires a valid paid Spine editor licence for anyone using the runtime, and it needs a packed atlas. Rejected on licensing, not on capability. |
| **Frame-by-frame sprite sheets / GIF / video loop** | Cannot satisfy independent part control, state blending or visemes, and explicitly excluded by the brief. Rejected. |
| **Custom layered skeletal rig on hardware-accelerated Android Canvas** | **Chosen.** Bone hierarchy with transform inheritance, one bitmap per layer with its own matrix and z-order, a channel-based control surface, a state machine with cross-fades, and a viseme set. No external runtime, no licence, no editor dependency, and the rig and its placeholder art can both be authored in this repository — so the APK demonstrates genuine independent motion now. `drawBitmap` with a matrix for ~45 layers on a hardware-accelerated canvas is inexpensive on any modern device. |

### What "chosen" commits us to

The rig, the pose channels, the behaviours and the state machine are all
independent of how pixels reach the screen. `LeonArtProvider` is the only
interface the renderer uses for artwork, and `LeonChannel` is the only way
anything reaches a bone. If Rive later becomes viable, the state machine,
behaviours, lip-sync contract and overlay are unchanged — only the binder and
renderer are replaced.

## 6. Performance decisions taken as a result

- Adaptive frame pacing: 60fps while anything is moving, 20fps when idle,
  achieved by scheduling the next frame on a delay instead of taking every
  vsync.
- Rendering stops entirely on `ACTION_SCREEN_OFF` and resumes on unlock with a
  fresh clock, so time with the screen off is never integrated in one frame.
- The preview in the control centre pauses in `onPause`.
- No allocation in the render loop: bones, layers, matrices, paints and the
  matrix value buffer are all created once.
- Art is rasterised at roughly the pixel size it will be drawn at, and the
  aura gradient at a fraction of it.
- No wake locks are taken anywhere.

## 7. Known gaps at the end of this milestone

- **The production Leon artwork does not exist yet.** The character is drawn by
  `DevRigArt`, which renders all 41 art keys procedurally from the character
  spec. This is a development rig, and the control centre says so on screen.
  See `LEON_CHARACTER_ASSET_SPEC.md` for exactly what is needed to replace it.
- **No voice and no AI backend**, by instruction.
  `LeonConversationController` reports `NO_BACKEND` rather than fabricating a
  reply, and `LeonLipSyncController.onViseme(viseme, intensity, durationMs)` is
  the contract a speech pipeline will call.
- **The SPEAKING state currently uses a synthetic viseme generator** so the
  mouth has something real to animate. It is a test signal source feeding the
  same channels through the same path, and it is disabled the moment an
  external producer calls `onViseme`.
- **The branded sleeve detail is generic.** A three-stripe sleeve is drawn; no
  brand logo or wordmark is reproduced. Whether licensed branding appears in
  the shipped art is the product owner's call, not an engineering default.
- **Nothing in this change has been run on a physical device by the author** —
  there is no device or emulator in the build environment. The device
  acceptance checklist is in the pull request and must be walked through on
  real hardware before the milestone is signed off.
