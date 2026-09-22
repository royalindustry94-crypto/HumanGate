# SDD ledger — plan: docs/superpowers/plans/2026-09-23-leon-production-mesh-plan.md

Execution mode: inline (no subagent runtime available).

Pre-flight interfaces:
- Task 1 -> Task 2: generated leon.png + manifest.json consumed by ProductionLeonTexture; clean.
- Task 2 -> Task 3: ProductionLeonTexture + LeonMeshRig consumed by LeonPuppetRenderer; clean.
- Task 3 -> Task 4: production renderer path consumed by visual/emulator gates; clean.
- Task 4 -> Task 5: exact-head CI artifacts consumed by final audit/handoff; clean.
- Tasks 3/5 share MainActivity, LeonOverlayService, LeonCharacterView; Task 5 intentionally removes old production references after Task 3 migration.

Task 1 Ruling: the plan's sample test uses pytest's tmp_path while the required command is unittest. Use tempfile.TemporaryDirectory with unittest so the prescribed runner is valid. Cost if wrong: test helper only; no production behavior changes.

Task 1 Ruling: bundled leon-front.webp is corrupt/truncated (RIFF declares 15,360 bytes, repository blob is 7,501 bytes; Pillow fails with "could not create decoder object"). Use the valid 1,223x1,286 character-sheet front turnaround as the authoritative full-body source and deterministically extract it to a 360x640 transparent production master. Cost if wrong: source fidelity is limited to the turnaround artwork resolution; device acceptance remains the final gate.
Task 1: complete (commits 585eb52..caec2ec, tests: production asset contract 4/4 + JUnit + lint + debug APK -> PASS in Leon Android APK run 71)\n