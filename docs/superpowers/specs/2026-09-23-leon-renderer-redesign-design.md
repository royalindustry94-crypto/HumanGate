# Leon Renderer Redesign — Production Design

## Goal

Ship one final Android test APK that shows the full Leon character from head to shoes, preserves his real reference appearance, animates independent body regions naturally, has no cartoon fallback, no detached facial pieces, no green-screen halo, and keeps the existing always-on Android overlay behavior.

## Why the current renderer is being replaced

The current `PhotoLayerArtProvider` crops rectangular regions from one flat source image at runtime. Repeated fixes have exposed structural problems rather than isolated bugs:

- rectangular slices do not partition the source silhouette cleanly;
- overlapping slices duplicate face, jewellery and tattoo pixels;
- independent resampling introduces seams and scale drift;
- source landmarks and image dimensions can get out of sync;
- procedural eye/mouth fallbacks visibly turn Leon into a cartoon;
- lower-body completeness can pass unit tests while still failing visually on-device.

This is an architecture problem. No more coordinate-tuning patches will be used as the primary solution.

## External references evaluated

### Rive Android
Rive is the strongest reference architecture for this job: open-source Android runtime, bones, raster meshes, mesh-to-bone binding, state machines, inverse kinematics and image assets. Its runtime is MIT licensed. The Rive authoring/export step, however, requires the Rive Editor and a production export plan, so the final APK must not depend on us having an external editor available during this repair.

### Live2D Cubism
Live2D is purpose-built for 2D character deformation and supports Android, but its Core is proprietary and its publishing/license model is more complex. It is not selected for this repair.

### Spine
Spine has mature skeletal runtimes, but its authoring/runtime licensing and editor dependency add friction without solving our immediate asset pipeline problem.

## Selected architecture

Use a **self-contained full-body weighted bitmap-mesh puppet renderer** inside the existing Android app, borrowing the proven concepts from Rive raster meshes: one exact transparent Leon texture, a low-density deformation mesh, bone-driven vertex weights, state-driven animation and deterministic visual contracts.

This is deliberately better than sprite slicing for Leon: the full character remains one continuous texture, so the rest pose is pixel-identical to the keyed master and there are no neck/shoulder/hip/knee seams to hide. No third-party animation runtime is required for this repair.

### Principle 1 — Never slice Leon into body sprites

The green-screen master is converted at build time into **one transparent production texture**. Runtime code never crops rectangular body parts and never mixes overlapping sprite slices.

The production visual payload is:

- one keyed full-body Leon texture;
- one mesh definition (grid topology + canonical vertex positions);
- one vertex-weight definition mapping mesh vertices to the existing Leon bones;
- one manifest containing source dimensions/hash, canonical bounds and validation values.

At rest, the mesh vertices are exactly their canonical positions, so the rendered character is the same continuous image as the production texture.

### Principle 2 — Exact rest-pose reconstruction is a release gate

The build pipeline renders the production mesh at its canonical rest pose and compares it against the keyed master.

The test fails when:

- the full-body alpha bounding box does not reach the expected crown and soles;
- reconstruction differs materially from the keyed master;
- any mesh cell folds/inverts at rest;
- the texture/manifest dimensions or hash do not match;
- green spill exceeds the allowed threshold.

This prevents another APK where CI is green but Leon is missing half his body.

### Principle 3 — No procedural character art in production mode

`ProceduralLeonArt` may remain only as a developer diagnostic fallback. It must never mix with the real Leon art when the production asset pack is present.

In production-photo mode:

- no procedural eyes;
- no cartoon mouth;
- no procedural sunglasses;
- no procedural body parts;
- no blue aura behind Leon.

Because voice/lip-sync is explicitly a later milestone, the production character should keep the real photographed face intact rather than add fake viseme art now.

### Principle 4 — Real independent motion without destroying identity

The current bone/state system is retained where useful, but it drives clean sprite/mesh regions rather than live crops.

Minimum independent motion for this milestone:

- torso breathing;
- subtle shoulder counter-motion;
- head tilt/turn;
- left/right arm micro-motion;
- left/right leg weight shift;
- posture changes for Idle, Listening, Thinking, Speaking, Happy and Serious;
- whole character minimisation only when the user explicitly minimises Leon.

A single whole-body scale/translate loop does not count as character animation.

### Principle 5 — Weighted full-body mesh deformation

Use Android `Canvas.drawBitmapMesh` for the entire keyed Leon texture. The mesh is low-density and each vertex blends the transforms of nearby Leon bones. Head vertices follow the head, torso vertices blend chest/spine/hips, side upper-body vertices blend the appropriate arm chain, and lower-body vertices blend hips/thigh/shin/foot.

Motion amplitudes remain intentionally subtle. The goal is a living companion, not rubber-body deformation. Mesh density and frame cadence stay low enough for an always-on overlay.

### Principle 6 — Animation state contract remains stable

The UI and state controller keep the current states:

- Idle
- Listening
- Thinking
- Speaking
- Happy
- Serious
- Minimised

The renderer consumes state outputs. Voice remains out of scope until the visual avatar passes device verification.

## Android overlay behavior to preserve

- `TYPE_APPLICATION_OVERLAY`
- explicit Android overlay permission
- transparent host window
- draggable position
- minimise/restore
- quick controls
- foreground service
- pause rendering when screen is off
- restore after unlock when previously enabled
- restore after package update/reboot when permission still exists
- no attempt to bypass secure system surfaces
- stable debug signing so future APKs update in place

## Asset pipeline

1. Decode the bundled Leon master.
2. Chroma-key only background-connected green.
3. De-spill edge contamination.
4. Export one transparent production texture into the generated Android assets directory.
5. Generate a deterministic mesh/weight manifest containing source hash, dimensions, canonical alpha bounds, grid dimensions, canonical vertex positions and bone weights.
6. Render the canonical mesh at rest and compare it with the keyed production texture.
7. Render deterministic state previews from the same production renderer path.
8. Publish previews as CI artifacts.

The Android app must not perform chroma keying or asset authoring on launch.

## Renderer changes

Create a new production texture + mesh renderer path:

- `ProductionLeonTexture` — loads the generated transparent full-body texture and manifest.
- `LeonMeshRig` — owns canonical mesh vertices and per-vertex bone weights.
- `LeonPuppetRenderer` — deforms the mesh from solved bone transforms and draws it with `Canvas.drawBitmapMesh`.
- `LeonVisualContract` — validates that production mode has the real texture/manifest and cannot fall through to procedural character art.
- Existing `LeonStateController` / `LeonAnimationController` remain the animation source.

`PhotoLayerArtProvider` and layered `LeonRenderer` are removed from the production path after parity tests pass.

## Required automated verification

### Asset contract tests

- bundled source dimensions/hash match manifest;
- production texture exists and is non-empty;
- manifest source hash/dimensions match the bundled master;
- mesh grid dimensions and vertex count are exact;
- every vertex's bone weights sum to 1 within tolerance;
- full reconstructed alpha bounds include head and shoes;
- green-spill threshold passes;
- production mode never resolves to `ProceduralLeonArt`.

### Rest-pose image test

Render a full-frame transparent Leon PNG through the production mesh at canonical rest pose and compare it with the keyed master.

At rest the allowed difference is effectively zero apart from bitmap filtering/encoding tolerance.

### Animation snapshot tests

Render at least:

- idle;
- head turn left/right;
- breathing peak;
- listening;
- thinking;
- speaking posture;
- minimised.

Each snapshot must retain the full body and must not expose green gaps or duplicated face parts.

### Android checks

- unit tests;
- lint;
- debug APK;
- stable signing;
- overlay permission path;
- foreground-service lifecycle;
- screen-off pause;
- app-update restore.

## Device acceptance gate

No merge and no claim of completion until the exact-head APK is installed on the Oppo and visually confirms:

1. Full Leon visible head-to-shoes.
2. Exact real Leon artwork, not procedural/cartoon art.
3. No detached eyes/mouth.
4. No green halo.
5. No missing lower body.
6. No obvious seams at neck, shoulders, hips or knees.
7. Independent movement is visible.
8. Overlay can be enabled and appears above another normal app.
9. Drag/minimise/restore work.
10. App update installs over the current baseline without uninstalling.

## Repository scope

For this repair, work stays on PR #167 so no code is lost and the current APK workflow remains usable. After this milestone is accepted on-device, Leon should be migrated into a standalone Leon Companion repository and removed from HumanGate in a separate change.

## Non-goals for this milestone

- voice;
- microphone capture;
- speech-to-text;
- live LLM connection;
- lip-sync/viseme generation;
- cloud backend;
- publishing to Play Store.

Those begin only after the visual companion passes the device acceptance gate.
