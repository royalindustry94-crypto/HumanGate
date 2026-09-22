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

Use a **self-contained preprocessed sprite + mesh puppet renderer** inside the existing Android app, borrowing the proven concepts from Rive: clean transparent raster layers, bones, weighted/deformable regions, state-driven animation and deterministic asset contracts.

No third-party animation runtime is required for this repair.

### Principle 1 — Never slice Leon at runtime

The green-screen master is converted before runtime into a set of transparent production assets. Runtime code only loads already-clean assets.

Required production assets:

- head
- torso/hood
- left upper arm
- left forearm/hand
- right upper arm
- right forearm/hand
- left thigh
- left shin
- left shoe
- right thigh
- right shin
- right shoe
- seam/backfill layer containing only pixels that must remain fixed to preserve the exact rest-pose silhouette

The part masks must be a partition of the keyed source. No two production layers may independently contain the same face/jewellery/tattoo region.

### Principle 2 — Exact rest-pose reconstruction is a release gate

The build pipeline must reconstruct Leon from all production layers at the canonical rest pose and compare it against the keyed master.

The test fails when:

- the full-body alpha bounding box does not reach the expected crown and soles;
- there are uncovered opaque pixels;
- there is excessive overlap;
- reconstruction differs materially from the keyed master;
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

### Principle 5 — Mesh deformation only where it improves seams

Use Android bitmap meshes for soft deformation around torso/shoulders/neck where rigid rotations would expose gaps. Limbs remain rigid or lightly deformed unless a visible artifact requires more.

Keep mesh density low for battery and overlay performance.

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
4. Generate deterministic alpha masks for all body regions.
5. Feather only internal seam boundaries, never exterior silhouette boundaries.
6. Export transparent WebP/PNG layer assets into `assets/leon/production/`.
7. Generate an asset manifest containing source dimensions, source hash, layer bounds, pivots and expected rest-pose coordinates.
8. Reconstruct the rest pose from exported layers.
9. Produce preview PNGs as CI artifacts.

The Android app must not perform steps 2–8 on every launch.

## Renderer changes

Create a new production provider and renderer path:

- `ProductionLeonArtProvider` — loads preprocessed assets and manifest.
- `LeonPuppetRenderer` — draws the layer stack, applies bone transforms and limited mesh deformation.
- `LeonVisualContract` — validates required production assets and rejects mixed/fallback mode.
- Existing `LeonStateController` / `LeonAnimationController` remain the animation source.

`PhotoLayerArtProvider` is removed from the production path after parity tests pass.

## Required automated verification

### Asset contract tests

- bundled source dimensions/hash match manifest;
- every required layer exists;
- no production layer is empty;
- left/right limbs use distinct assets;
- full reconstructed alpha bounds include head and shoes;
- green-spill threshold passes;
- no production art request resolves to `ProceduralLeonArt`.

### Rest-pose image test

Build a full-frame transparent Leon PNG from the actual production layers and compare it with the keyed master.

The allowed difference is restricted to deliberately feathered internal seams.

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
