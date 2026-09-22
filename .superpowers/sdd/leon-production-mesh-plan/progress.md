# SDD ledger — plan: docs/superpowers/plans/2026-09-23-leon-production-mesh-plan.md

Execution mode: inline (no subagent runtime available).

Pre-flight interfaces:
- Task 1 -> Task 2: generated leon.png + manifest.json consumed by ProductionLeonTexture; clean.
- Task 2 -> Task 3: ProductionLeonTexture + LeonMeshRig consumed by LeonPuppetRenderer; clean.
- Task 3 -> Task 4: production renderer path consumed by visual/emulator gates; clean.
- Task 4 -> Task 5: exact-head CI artifacts consumed by final audit/handoff; clean.
- Tasks 3/5 share MainActivity, LeonOverlayService, LeonCharacterView; Task 5 intentionally removes old production references after Task 3 migration.

Task 1 Ruling: the plan's sample test uses pytest's tmp_path while the required command is unittest. Use tempfile.TemporaryDirectory with unittest so the prescribed runner is valid. Cost if wrong: test helper only; no production behavior changes.
