# Leon Production Mesh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Leon's fragile runtime photo slicing with a single exact full-body transparent texture deformed by a low-density weighted mesh, then gate the APK with deterministic visual tests and an Android emulator screenshot test.

**Architecture:** The build pipeline keys the bundled green-screen source into one transparent production texture and emits a manifest with source hash, alpha bounds, mesh size, canonical vertices, and bone weights. Android loads that texture through a production-only contract and `LeonPuppetRenderer` deforms it with `Canvas.drawBitmapMesh` using the existing solved rig bones. CI renders the exact production path, verifies rest-pose fidelity and animation snapshots, boots the APK in an emulator, captures the real screen, and only uploads the APK when all gates pass.

**Tech Stack:** Android Java 17, Android SDK 35, Gradle 8.9, Python 3 + Pillow for build-time asset authoring, JUnit 4, GitHub Actions, Android emulator/ADB.

**Spec:** `docs/superpowers/specs/2026-09-23-leon-renderer-redesign-design.md`

## Global Constraints

- Production Leon must be the exact real reference artwork, never mixed with procedural/cartoon character art.
- Production visual asset is one continuous full-body texture; there are no runtime rectangular body crops.
- Rest pose must remain pixel-equivalent to the keyed master within a tiny filtering tolerance.
- Full alpha bounds must include crown and soles; no missing lower body.
- No visible chroma-green halo around the character.
- Independent motion must be visible through head, torso, arms and lower-body weight shifts.
- Existing `TYPE_APPLICATION_OVERLAY`, drag, minimise/restore, quick controls, foreground service, screen-off pause, unlock restore, reboot/update restore and stable test signing must remain.
- Voice, microphone, speech-to-text, live LLM calls and lip-sync are out of scope for this milestone.
- PR #167 stays unmerged until the exact-head APK passes the Oppo device acceptance gate.
- No new third-party animation runtime is added.

## Review Focus

- Master image dimensions/hash drift: CI must fail before Gradle packages a mismatched production texture.
- Mesh weight corruption: every vertex must have finite weights that sum to 1.0 ± 0.001 and reference known bones.
- Full-body regression: automated render must prove alpha bounds include head and shoes in every snapshot.
- Green spill: keyed output and rendered snapshots must fail when green-dominant fringe exceeds the threshold.
- Android-only regressions: emulator must boot the exact APK, show Leon in MainActivity, enable the overlay with app-ops, and capture an overlay screenshot before APK upload.

---

### Task 1: Build-time production texture + manifest

**Files:**
- Create: `apps/leon-android/tools/build_leon_production.py`
- Create: `apps/leon-android/tools/test_build_leon_production.py`
- Modify: `apps/leon-android/app/build.gradle`
- Output at build time: `apps/leon-android/app/build/generated/leonAssets/leon/production/leon.png`
- Output at build time: `apps/leon-android/app/build/generated/leonAssets/leon/production/manifest.json`

**Interfaces:**
- Consumes: `app/src/main/assets/leon/leon-front.webp`, `app/src/main/assets/leon/leon-front.txt`.
- Produces: deterministic transparent PNG and manifest consumed by Task 2.

- [ ] **Step 1: Write failing Python tests for exact source parsing and chroma keying**

Create `test_build_leon_production.py` with tests that call:

```python
from build_leon_production import read_landmarks, key_green, build_manifest

def test_landmarks_are_current_master_values(tmp_path):
    p = tmp_path / "leon-front.txt"
    p.write_text("11 612 180\n", encoding="utf-8")
    assert read_landmarks(p) == (11.0, 612.0, 180.0)

def test_edge_connected_green_is_transparent_but_internal_green_is_preserved():
    # 5x5 RGBA image: green border + isolated green center.
    # key_green must clear the border and keep center opaque.
    ...

def test_manifest_pins_360x640_source_and_alpha_bounds():
    manifest = build_manifest(...)
    assert manifest["source_width"] == 360
    assert manifest["source_height"] == 640
    assert manifest["mesh_cols"] == 16
    assert manifest["mesh_rows"] == 28
    assert manifest["source_sha256"]
    assert manifest["alpha_bounds"]["bottom"] > 600
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
cd apps/leon-android/tools
python3 -m unittest -v test_build_leon_production.py
```

Expected: FAIL because `build_leon_production.py` does not exist.

- [ ] **Step 3: Implement deterministic chroma key + manifest**

`build_leon_production.py` must expose these exact functions:

```python
def read_landmarks(path: pathlib.Path) -> tuple[float, float, float]: ...
def key_green(image: PIL.Image.Image) -> PIL.Image.Image: ...
def alpha_bounds(image: PIL.Image.Image) -> tuple[int, int, int, int]: ...
def build_manifest(source: PIL.Image.Image, keyed: PIL.Image.Image,
                   source_bytes: bytes, landmarks: tuple[float, float, float]) -> dict: ...
def build(source_path: pathlib.Path, landmarks_path: pathlib.Path,
          output_dir: pathlib.Path) -> None: ...
```

Keying rule:
- flood-fill only green-dominant pixels connected to the image boundary;
- candidate green requires `g >= 95`, `g-r >= 22`, `g-b >= 22`;
- run a two-pixel de-spill band;
- preserve isolated green jewellery/detail not connected to the boundary.

Manifest constants:
- `mesh_cols = 16`
- `mesh_rows = 28`
- SHA-256 of the original source bytes
- source dimensions
- crown/sole/centre landmarks
- keyed alpha bounds
- design size `384 x 768`.

- [ ] **Step 4: Wire generation into Gradle before tests/build**

Modify `app/build.gradle`:

```groovy
def generatedLeonAssets = layout.buildDirectory.dir("generated/leonAssets")

tasks.register("generateLeonProduction", Exec) {
    inputs.file("src/main/assets/leon/leon-front.webp")
    inputs.file("src/main/assets/leon/leon-front.txt")
    inputs.file("../tools/build_leon_production.py")
    outputs.dir(generatedLeonAssets)
    commandLine "python3", "../tools/build_leon_production.py",
            "--source", "src/main/assets/leon/leon-front.webp",
            "--landmarks", "src/main/assets/leon/leon-front.txt",
            "--out", generatedLeonAssets.get().asFile.absolutePath + "/leon/production"
}

android.sourceSets.main.assets.srcDir(generatedLeonAssets)
tasks.named("preBuild").configure { dependsOn("generateLeonProduction") }
```

- [ ] **Step 5: Run RED→GREEN verification**

Run:

```bash
cd apps/leon-android/tools
python3 -m unittest -v test_build_leon_production.py
cd ..
gradle :app:generateLeonProduction --stacktrace
```

Expected: all Python tests PASS and generated `leon.png` + `manifest.json` exist.

- [ ] **Step 6: Commit**

```bash
git add apps/leon-android/tools apps/leon-android/app/build.gradle
git commit -m "feat(leon): generate keyed production mesh assets"
```

---

### Task 2: Production texture contract + weighted mesh

**Files:**
- Create: `apps/leon-android/app/src/main/java/ai/leon/companion/render/ProductionLeonTexture.java`
- Create: `apps/leon-android/app/src/main/java/ai/leon/companion/render/LeonMeshRig.java`
- Create: `apps/leon-android/app/src/test/java/ai/leon/companion/LeonMeshRigTest.java`
- Replace: `apps/leon-android/app/src/test/java/ai/leon/companion/PhotoAssetContractTest.java` with `ProductionAssetContractTest.java`

**Interfaces:**
- Consumes: generated `leon/production/leon.png` and `manifest.json`.
- Produces: `ProductionLeonTexture.load(Context)`, `LeonMeshRig.create(Rig, manifest)`, canonical/deformed vertex arrays.

- [ ] **Step 1: Write failing JVM tests for mesh invariants**

```java
@Test public void everyVertexWeightSumsToOne() {
    LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
    for (LeonMeshRig.VertexWeights w : mesh.weights()) {
        assertEquals(1f, w.totalWeight(), 0.001f);
        assertTrue(w.allFinite());
    }
}

@Test public void restPoseVerticesAreCanonical() {
    LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
    float[] rest = mesh.canonicalVertices();
    float[] deformed = mesh.deformIdentityForTest();
    assertArrayEquals(rest, deformed, 0.0001f);
}

@Test public void fullMeshHasExpectedVertexCount() {
    LeonMeshRig mesh = LeonMeshRig.createForTest(16, 28);
    assertEquals((16 + 1) * (28 + 1) * 2, mesh.canonicalVertices().length);
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest --tests '*LeonMeshRigTest' --stacktrace
```

Expected: FAIL because production mesh classes do not exist.

- [ ] **Step 3: Implement `ProductionLeonTexture`**

Exact public API:

```java
public final class ProductionLeonTexture implements AutoCloseable {
    public static ProductionLeonTexture load(Context context);
    public Bitmap bitmap();
    public Manifest manifest();
    public String report();
    public boolean isValid();
    @Override public void close();

    public static final class Manifest {
        public final int sourceWidth, sourceHeight, meshCols, meshRows;
        public final float crownY, soleY, centreX;
        public final String sourceSha256;
        public final Rect alphaBounds;
    }
}
```

Rules:
- load only `assets/leon/production/leon.png` + `manifest.json`;
- reject missing/empty/invalid data with `IllegalStateException`;
- no fallback to `ProceduralLeonArt`.

- [ ] **Step 4: Implement `LeonMeshRig`**

Exact public API:

```java
public final class LeonMeshRig {
    public static LeonMeshRig create(Rig rig, ProductionLeonTexture.Manifest manifest);
    public float[] canonicalVertices();
    public float[] deformedVertices();
    public int meshCols();
    public int meshRows();
    public void updateFromSolvedRig();
}
```

Weight rules:
- head region blends to `HEAD`;
- neck transition blends `HEAD/NECK/CHEST`;
- torso blends `CHEST/SPINE/HIPS`;
- far-left/far-right upper-body vertices blend into the matching arm chain;
- lower-left blends `HIPS/THIGH_L/SHIN_L/FOOT_L`;
- lower-right blends `HIPS/THIGH_R/SHIN_R/FOOT_R`;
- every vertex weight sum is exactly normalized.

- [ ] **Step 5: Replace old photo asset contract test**

`ProductionAssetContractTest` must assert:
- manifest source dimensions are `360 x 640`;
- landmarks are `11, 612, 180`;
- mesh is `16 x 28`;
- keyed alpha bounds include the sole region;
- production asset exists;
- no production dependency mentions `PhotoLayerArtProvider` or `ProceduralLeonArt`.

- [ ] **Step 6: Verify GREEN**

Run:

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest --stacktrace
```

Expected: complete JUnit suite PASS.

- [ ] **Step 7: Commit**

```bash
git add apps/leon-android/app/src/main/java/ai/leon/companion/render \
        apps/leon-android/app/src/test/java/ai/leon/companion
git commit -m "feat(leon): add production texture and weighted mesh"
```

---

### Task 3: Replace layered photo renderer with full-body mesh renderer

**Files:**
- Create: `apps/leon-android/app/src/main/java/ai/leon/companion/render/LeonPuppetRenderer.java`
- Modify: `apps/leon-android/app/src/main/java/ai/leon/companion/render/LeonCharacterView.java`
- Modify: `apps/leon-android/app/src/main/java/ai/leon/companion/MainActivity.java`
- Modify: `apps/leon-android/app/src/main/java/ai/leon/companion/overlay/LeonOverlayService.java`
- Test: `apps/leon-android/app/src/test/java/ai/leon/companion/LeonPuppetRendererContractTest.java`

**Interfaces:**
- Consumes: solved existing `Rig`, `LeonAnimationController`, `ProductionLeonTexture`, `LeonMeshRig`.
- Produces: the only production draw path used by preview and overlay.

- [ ] **Step 1: Write failing renderer contract tests**

Tests must prove:
- production view construction does not accept `LeonArtProvider`;
- renderer uses exactly one production bitmap;
- rest-pose mesh update does not change canonical vertices;
- non-zero head rotation changes only the expected mesh region beyond tolerance;
- minimised state still uses the same mesh renderer.

- [ ] **Step 2: Verify RED**

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest --tests '*LeonPuppetRendererContractTest' --stacktrace
```

Expected: FAIL because `LeonPuppetRenderer` does not exist.

- [ ] **Step 3: Implement `LeonPuppetRenderer`**

Exact API:

```java
public final class LeonPuppetRenderer {
    public LeonPuppetRenderer(Rig rig, ProductionLeonTexture texture);
    public void setViewport(int width, int height);
    public void draw(Canvas canvas);
    public LeonMeshRig mesh();
}
```

Draw path:

```java
mesh.updateFromSolvedRig();
canvas.save();
canvas.concat(viewMatrix);
canvas.drawBitmapMesh(
        texture.bitmap(),
        mesh.meshCols(),
        mesh.meshRows(),
        mesh.deformedVertices(),
        0,
        null,
        0,
        paint);
canvas.restore();
```

- [ ] **Step 4: Convert `LeonCharacterView` to production renderer**

Constructor becomes:

```java
public LeonCharacterView(Context context, Rig rig,
        LeonAnimationController controller, ProductionLeonTexture texture)
```

Keep adaptive frame pacing, pause/resume and lifecycle exactly as they are.

- [ ] **Step 5: Switch MainActivity + overlay to production texture only**

Both surfaces must:
1. build their own `Rig`;
2. load `ProductionLeonTexture`;
3. create the existing `LeonAnimationController`;
4. construct `LeonCharacterView` with the production texture.

Production code must not instantiate:
- `LeonAssetRepository`
- `PhotoLayerArtProvider`
- `ProceduralLeonArt`
- `LeonRenderer`.

Keep those classes only until deletion in Task 5.

- [ ] **Step 6: Disable fake viseme art for this milestone**

Keep `LeonVisemeBus` APIs intact for future voice work, but production mesh rendering ignores procedural viseme bitmaps. Speaking state uses posture/head/torso motion only.

- [ ] **Step 7: Verify GREEN**

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add apps/leon-android/app/src/main/java apps/leon-android/app/src/test
git commit -m "feat(leon): render exact character through weighted mesh"
```

---

### Task 4: Deterministic visual regression + emulator screenshot gate

**Files:**
- Create: `apps/leon-android/tools/render_contract.py`
- Create: `apps/leon-android/tools/test_render_contract.py`
- Modify: `.github/workflows/leon-android-apk.yml`
- Create: `apps/leon-android/app/src/main/java/ai/leon/companion/DebugTestHooks.java`
- Modify: `apps/leon-android/app/src/main/java/ai/leon/companion/MainActivity.java`

**Interfaces:**
- Consumes: generated production texture/manifest and built APK.
- Produces: CI preview PNGs and ADB screenshots; blocks APK upload on visual/device failures.

- [ ] **Step 1: Write failing Python visual-contract tests**

```python
def test_rest_pose_matches_keyed_master():
    result = compare_rest_pose(...)
    assert result.max_channel_error <= 2
    assert result.changed_pixel_fraction <= 0.001

def test_green_spill_is_below_threshold():
    assert green_spill_fraction(...) <= 0.0005

def test_alpha_bounds_include_full_body():
    bounds = rendered_alpha_bounds(...)
    assert bounds.top <= 15
    assert bounds.bottom >= 610
```

- [ ] **Step 2: Verify RED**

```bash
cd apps/leon-android/tools
python3 -m unittest -v test_render_contract.py
```

Expected: FAIL because `render_contract.py` does not exist.

- [ ] **Step 3: Implement deterministic preview generation**

Generate these files into `app/build/reports/leon-preview/`:
- `rest.png`
- `idle.png`
- `head-left.png`
- `head-right.png`
- `breath-peak.png`
- `listening.png`
- `thinking.png`
- `speaking.png`
- `minimised.png`

Every preview must run the same mesh math/manifest contract as Android. No hand-authored preview images.

- [ ] **Step 4: Add debug-only deterministic launch hook**

`DebugTestHooks` exact API:

```java
public final class DebugTestHooks {
    public static final String EXTRA_TEST_POSE = "ai.leon.companion.TEST_POSE";
    public static final String EXTRA_DISABLE_RANDOM_BEHAVIOURS = "ai.leon.companion.DISABLE_RANDOM";
    public static void apply(Intent intent, LeonAnimationController controller);
}
```

Only active when `BuildConfig.DEBUG` is true.

- [ ] **Step 5: Add Python/Pillow setup and visual contract to workflow**

Before Gradle tests:

```yaml
- name: Python image tooling
  run: python3 -m pip install --disable-pip-version-check pillow==11.3.0

- name: Production asset tests
  working-directory: apps/leon-android/tools
  run: python3 -m unittest -v test_build_leon_production.py test_render_contract.py
```

After APK build, upload `app/build/reports/leon-preview/*.png` as `Leon-visual-contract`.

- [ ] **Step 6: Add Android emulator boot + screenshot gate**

Use the SDK tools already on `ubuntu-latest`:

```bash
yes | sdkmanager "platform-tools" "emulator" "system-images;android-35;google_apis;x86_64"
echo no | avdmanager create avd -n leon-ci -k "system-images;android-35;google_apis;x86_64"
sudo chmod 666 /dev/kvm
emulator -avd leon-ci -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
adb shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done'
adb install -r apps/leon-android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n ai.leon.companion/.MainActivity \
  --es ai.leon.companion.TEST_POSE idle \
  --ez ai.leon.companion.DISABLE_RANDOM true
sleep 2
adb exec-out screencap -p > apps/leon-android/app/build/reports/leon-preview/emulator-main.png
adb shell appops set ai.leon.companion SYSTEM_ALERT_WINDOW allow
adb shell am start-foreground-service -n ai.leon.companion/.overlay.LeonOverlayService
sleep 2
adb shell input keyevent KEYCODE_HOME
adb exec-out screencap -p > apps/leon-android/app/build/reports/leon-preview/emulator-overlay.png
```

- [ ] **Step 7: Add screenshot validator**

Validator must fail CI unless emulator screenshots contain a non-background Leon alpha/color bounding region whose height is at least 55% of the preview/overlay host and whose detected body reaches both upper and lower thirds of the expected Leon viewport.

- [ ] **Step 8: Verify workflow locally as far as possible, then push and inspect Actions**

Commands:

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
cd tools
python3 -m unittest -v test_build_leon_production.py test_render_contract.py
```

Then inspect the exact-head GitHub Actions run and downloaded screenshot artifacts before accepting the task.

- [ ] **Step 9: Commit**

```bash
git add apps/leon-android .github/workflows/leon-android-apk.yml
git commit -m "test(leon): gate APK on visual and emulator verification"
```

---

### Task 5: Remove production fallback path, audit lifecycle, final exact-head build

**Files:**
- Delete from production path: `PhotoLayerArtProvider.java`
- Delete from production path: `LeonAssetRepository.java`
- Delete from production path: `LeonRenderer.java`
- Keep `ProceduralLeonArt.java` only if referenced by explicit debug diagnostics; otherwise delete it.
- Modify: `apps/leon-android/README.md`
- Modify: `docs/LEON_ANDROID_AUDIT.md`
- Modify: `docs/leon-preview/README.md`

**Interfaces:**
- Consumes: completed production renderer and CI gates.
- Produces: clean PR #167 exact-head candidate APK.

- [ ] **Step 1: Write a failing forbidden-reference test**

Create/extend a test that scans production Java sources and fails if these strings appear in `MainActivity`, `LeonOverlayService`, `LeonCharacterView`, or `LeonPuppetRenderer`:

```text
PhotoLayerArtProvider
LeonAssetRepository
ProceduralLeonArt
LeonRenderer
```

- [ ] **Step 2: Verify RED before cleanup**

Run the test and confirm it fails on any remaining old production reference.

- [ ] **Step 3: Remove old production path and dead code**

Delete or isolate old renderer code so production can only reach:
`ProductionLeonTexture -> LeonMeshRig -> LeonPuppetRenderer -> LeonCharacterView`.

- [ ] **Step 4: Audit overlay lifecycle against existing behavior**

Verify by code + tests:
- explicit overlay permission check remains;
- service remains non-exported;
- foreground service starts only after enable/permission;
- screen-off pauses render loop;
- screen-on/user-present resets animation timers;
- drag persists normalized position;
- minimise/restore changes size but not renderer identity;
- boot/package-replaced restore respects prior enabled state;
- stable debug signing and CI versionCode remain unchanged.

- [ ] **Step 5: Run complete local/CI verification**

Local commands:

```bash
cd apps/leon-android/tools
python3 -m unittest -v
cd ..
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --stacktrace
```

GitHub exact-head requirements:
- Leon Android APK workflow: SUCCESS;
- production asset tests: SUCCESS;
- JUnit: SUCCESS;
- lint: SUCCESS;
- visual contract: SUCCESS;
- emulator boot/install: SUCCESS;
- emulator main screenshot: SUCCESS;
- emulator overlay screenshot: SUCCESS;
- APK artifact uploaded.

- [ ] **Step 6: Inspect CI images before handing APK to the phone**

Download and inspect:
- `rest.png`
- `idle.png`
- `head-left.png`
- `head-right.png`
- `emulator-main.png`
- `emulator-overlay.png`

Do not provide the APK if:
- character is not full body;
- character is cartoon/procedural;
- lower body is absent;
- green halo is visible;
- head/body are detached;
- overlay screenshot does not show Leon.

- [ ] **Step 7: Build final test APK and hand off without merging**

Download exact-head `Leon-Companion-debug-apk` artifact. PR #167 remains open.

The Oppo acceptance gate is exactly:
1. full Leon head-to-shoes;
2. exact reference appearance;
3. no detached facial pieces;
4. no green halo;
5. no missing lower body;
6. no obvious neck/shoulder/hip/knee artifacts;
7. visible independent head/body/limb motion;
8. overlay above a normal app;
9. drag/minimise/restore work;
10. next same-signed APK updates in place.

- [ ] **Step 8: Commit audit/docs**

```bash
git add apps/leon-android docs/LEON_ANDROID_AUDIT.md docs/leon-preview
git commit -m "docs(leon): record production mesh verification gate"
```

---

## Self-review

- Spec coverage: full-body exact art, no runtime slicing, no procedural mixing, weighted deformation, overlay preservation, CI visual gate, emulator gate and Oppo acceptance are all mapped to tasks.
- Placeholder scan: no TBD/TODO/“implement later” steps remain.
- Type consistency: Task 2 produces `ProductionLeonTexture` + `LeonMeshRig`; Task 3 consumes those exact types; Task 4 tests that exact renderer; Task 5 removes the old path.
- Review-focus coverage:
  - source drift → Task 1 + Task 2 contract tests;
  - mesh-weight corruption → Task 2;
  - missing full body → Task 4 image tests + emulator screenshots;
  - green spill → Task 1 keying + Task 4 threshold;
  - Android-only failure → Task 4 emulator + Task 5 exact-head gate.
