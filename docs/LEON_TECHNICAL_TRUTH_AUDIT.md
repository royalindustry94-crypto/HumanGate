# Leon Android — technical-truth audit (2026-09-26)

Independent audit of `main` @ `d6bcb4d`, from the code and assets in the
repository, not from earlier reports. Every claim below cites the file that
proves it.

## 1. Renderer actually running

- **Live path:** `LeonOverlayService` / `MainActivity` → `ProductionLeonTexture.load()`
  → `LeonCharacterView` → `render/LeonPuppetRenderer.draw()` →
  `Canvas.drawBitmapMesh(texture.bitmap(), mesh.meshCols(), mesh.meshRows(), mesh.deformedVertices(), …)`.
- Vertices come from `render/LeonMeshRig.updateFromSolvedRig()` (linear-blend
  skinning of a regular grid against the solved `rig/LeonRig` skeleton).
- It is plain Android `Canvas` on a hardware-accelerated `View`. There is no
  OpenGL, Filament, Unity, WebView or Live2D runtime; `app/build.gradle` has no
  runtime dependency at all (`testImplementation junit` only).
- **Dead, unreachable render path (before this audit):** `render/LeonRenderer`,
  `asset/LeonAssetRepository`, `asset/ProceduralLeonArt`,
  `asset/AssetDirArtProvider`, `asset/PhotoLayerArtProvider`,
  `asset/LeonArtProvider`. No production class constructs any of them;
  `LeonPuppetRendererContractTest` already asserts that production must not.

## 2. 3D or 2D?

**2D weighted-mesh deformation of one bitmap.** It is not a 3D rigged mesh.

- Asset: `docs/leon-reference/leon-front-source.png` (941x1672 photo), which
  `tools/build_leon_production.py` crops and keys at build time (`preBuild` →
  `generateLeonProduction`) into `leon/production/leon.png` (360x640 RGBA) plus
  `manifest.json` (`mesh_cols` 16, `mesh_rows` 28, landmarks crown 20 / sole 619 /
  centre 180). No `.glb`, `.vrm`, `.fbx` or `.gltf` exists anywhere in the repo.
- Skeleton: `rig/LeonRig` has 27 2D bones in a 384x768 design space.
- Consequence: the character is a single front view. There is no out-of-plane
  rotation or true leg lift; "walk" is limited to what 2D bending can show.

## 3. Backend

*Updated after `main` moved to `5125df7`.* At `d6bcb4d` there was no backend:
no `INTERNET` permission and `NO_BACKEND`. PR #180 has since merged one:

- `POST /leon/voice-turn` and `GET /leon/voice-status` in
  `apps/api/app/api/routes/leon_voice.py`, with the logic in
  `app/services/leon_voice.py`. The routes are gated by a shared bearer token
  (`LEON_VOICE_APP_TOKEN`).
- Spend is capped and fails closed: each provider call reserves against the
  Leon system workspace's cap (`app/services/leon_voice_spend.py`, migration
  `0059`).
- The Android manifest now requests `INTERNET` and `RECORD_AUDIO`.

## 4. Voice provider

- Speech to text: OpenAI Whisper (`/v1/audio/transcriptions`).
- Reply: an Anthropic model (`leon_anthropic_model`).
- Speech: OpenAI TTS (`/v1/audio/speech`).
- Keys are read from the deployment's existing variable names, `anthkey` and
  `ANTHTOPIC_APO_KEY`. The second one holds the **OpenAI** key
  (`app/core/config.py`).
- Wire format, from `LeonVoiceRecorder` and `LeonVoiceApiClient`:
  `multipart/form-data`, with part `audio` (`speech.wav`, `audio/wav`, 16 kHz
  mono 16-bit PCM in a RIFF container) and an optional `history` JSON part.
- On-device `TextToSpeech` (`voice/LeonSpeechController`) is still present.

## 5. Why builds could not upgrade in place

`applicationId` has always been `ai.leon.companion`, so that was never the
problem. The problem is the signing certificate:

| Period | Commit | Signing | versionCode |
|---|---|---|---|
| Prototype | `5e903f5` | CI runner's auto-generated `~/.android/debug.keystore` (new key on every runner) | 1 |
| Rig rewrite | `9b2ca57` | same, a new key per runner | 2 |
| Stable key | `0b81697` | committed `leon-ci-debug.keystore` (first version) | `run_number` |
| Key replaced | `b56f3c1` | committed keystore replaced, `SHA-256 D5:A9:8A:B8:…:6B:10` | `run_number` |

Every APK built before `b56f3c1` is signed by a different certificate from the
current builds. Android refuses that update (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)
and no code change can fix it: key rotation (APK Signature Scheme v3) needs the
old private key, and those runner keys no longer exist. **One uninstall of any
pre-`b56f3c1` build is unavoidable.** After that, every later build signed with
the same key upgrades in place.

Problems that remained at `d6bcb4d` (fixed on this branch, see below):

- `release` had **no `signingConfig`**, so `assembleRelease` produced an
  unsigned APK that could not be installed at all.
- `versionCode` fell back to a hard-coded `3` when `-PleonVersionCode` was not
  passed. A local build was therefore a *downgrade* against any CI build
  (`run_number` > 3) and was refused.

## 6. Idle hips compressed: root cause

Measured on the generated 360x640 texture mapped into design space (script and
numbers in the PR). The pelvis and thighs span **±80 to ±93** design units from
the centre line. Rows 398–444 have the hands at ±92 to ±131, only 4 to 12 units
outside the hip edge. `LeonMeshRig` bound skin by hard-coded `x` thresholds on a
16-column grid (one column = 25.8 design units):

- For `y ≥ 395`, every vertex beyond `|x−centre| > 72` was bound to
  **hand → root**, not hips/thigh. The grid column at ±77.6 lies *inside* the
  outer hip and thigh. So the outer ~15 units of each hip and thigh followed the
  hand and then stayed pinned to the static root, while the inner columns
  followed `HIPS`/`THIGH`. Idle weight shift moves `HIPS`, so one side of the
  pelvis squeezed and the other stretched. That is the "compressed hips / loose
  leg attachment".
- For `345 ≤ y < 395` (lower torso and belt line), the torso/limb blend started at
  `|x−centre| = 0`. That put about **29%** forearm/hand weight on the column at ±51.7
  inside the belly, and 56% on the hip edge at ±77.6.
- At 16 columns, no vertex fits in the 4–12-unit gap between the hip edge and the
  hand. So any per-vertex binding had to mis-bind either the hip or the hand. The
  earlier fixes changed weights and reduced animation amplitude. Neither can
  work at that resolution.

## 7. Hip fix: what changed and how it was verified

The fix is on `claude/leon-avatar-audit-79aywc` (commit `41d0c1e`).

- The skinning boundary now follows the measured transparent gap between body
  and arms (`LeonMeshRig.BODY_EDGE_*`). A test checks it against the generated
  texture on every half design row from y=274 to y=446.
- The production grid goes from 16x28 to 48x84 (one cell ≈ 8.6 design units).
- `PostureBehaviour.IDLE_SWAY_SCALE = 0.2` is removed. It shrank idle sway to
  hide the defect, and idle now runs at its original amplitude.
- New metric: the length change of every mesh edge that runs through visible
  pixels. The old tests measured vertex ratios that included the transparent
  gap. They tolerated 7x and never detected the hip squeeze.

| Case (visible pixels, worst edge ratio) | Before | After |
|---|---|---|
| Idle trajectory, hips/thighs (5 seeds) | 4.25 (15.6 in one pose) | < 1.05 |
| `WEIGHT_SHIFT` ±1, hips | 3.75 | < 1.1 |
| Arm swing, drag on hip/thigh | 5.5–7.0 | < 1.02 |
| Idle + walk 120 s, hips | — | 1.63 |
| Idle + walk 120 s, whole body | 162x+ (vertex metric, PR #188) | 3.3 |

Mutation check: when the old `> 72` leg rule is restored, 6 of the 11 mesh
tests fail.

**Not verified:** a Gradle build, lint and the emulator matrix. `dl.google.com`
is blocked in this sandbox, so all tests ran through `javac` + JUnit on the
Android-free sources. CI run `36243088450` on `41d0c1e` is the first real build.
No test has run on a real device.

**Known residual:** at y≈424 the thumb comes within about 3 design units of the
hip. That is narrower than one mesh cell, so one cell per side spans both the
thumb and the hip. It smears by less than one cell width under large hand
gestures.

## 8. Follow-up work (2026-09-26, second pass)

Every item below was measured on visible pixels of the production texture.
Each comes with regression tests that fail when the old behaviour is restored.

1. **Arm joint pivots: fixed** (`17b193d`). They now sit on the photo's
   joints: shoulder x 95, elbow (±103, 285), wrist (±112, 355), all defined as
   constants in `LeonRig`. The mesh's elbow and wrist blend bands are centred
   on the same rows. Results:

   | Case | Before | After |
   |---|---|---|
   | 12% idle elbow bend | 1.55x | 1.36x |
   | Hand raise, wrist | 1.41x | 1.11x |
   | HAPPY trajectory | 3.21x | 2.10x |
   | Idle trajectory | 2.35x | 1.83x |
   | Walk trajectory | 2.38x | 1.83x |

   The remaining ~1.36x (idle) and ~2.1x (full flexion) are the inner crook of
   the elbow compressing. That is real anatomy: a 12.6° bend over the 30-unit
   blend band predicts 1.37x. The rotation clamps in `LeonRigBinder` have not
   been revisited yet.
2. **Dead code: removed** (`45fbaf4`). That covers seven classes, the unused
   `leon-front.webp` that shipped in the APK, and the stale docs
   (`docs/leon-preview`, `LEON_CHARACTER_ASSET_SPEC.md`). The README was
   rewritten to match the code.
3. **Release signing: fail closed** (`1485080`). Before this change there was
   no release pipeline and `assembleRelease` produced an unsigned APK. Now:
   - The release key comes only from `LEON_RELEASE_*` environment variables.
   - A release build without that key or without `-PleonVersionCode` stops
     with an error.
   - `versionName` is now `0.3.<versionCode>`.

   Still open: storing a release key in CI secrets and adding a release job.
   That is a Founder decision about keys and repository secrets. A
   release-signed APK cannot upgrade a debug-signed one, so switching channels
   needs one uninstall.
4. **Voice 500s: explicit** (`02a6c1c`). Reproduced first: a database error in
   the spend reservation returned a bare `internal server error`. Now:
   - Every turn logs the payload's shape (never its content).
   - Unexpected errors return a 500 that names the stage, the error type and
     the request id.
   - The phone shows the stage for a 500.
5. **Not rendered:** blinks and lip-sync. The mesh binds the whole head to one
   bone, and the jaw, eye, lid and mouth parts are never drawn.
6. **Still open:**
   - Walking vs PR #188: needs a Founder decision.
   - Adidas branding in the source photo.
   - Phase 4 (overlay lifecycle, touch pass-through, profiling the 48x84 mesh
     on a device).
