# Codex Leon Remediation Handoff

Start SHA: `51394255fb8263ed4a6e27ef0ccb3ff013309061`

Codex is the Builder for this bounded Leon Android remediation.

## Goal

Audit the current Leon avatar visually using the existing offline renderer and Android emulator pipeline, fix every reproducible visual defect, add regression coverage, and keep APK delivery fail-closed until all exact-head gates pass.

## Required inspection matrix

- IDLE
- THINKING / chin gesture
- SPEAKING / visemes
- blink
- head turn
- arm swing
- elbow bend
- wrist / hand motion

Inspect real rendered pixels for mesh folding, seam smears, stretched limbs, bad joint deformation, cropping, transparency defects, and unnatural motion.

## Required verification

- render_contract.py
- full JVM / Android unit suite
- lint
- exact APK build
- API 35 emulator screenshots
- validate_emulator_screenshot.py
- visual inspection of generated PNGs and emulator screenshots

For every defect fixed, add a regression test that fails before and passes after the fix.

Preserve the committed Leon reference identity. Do not replace/redraw the character to hide rig defects.

The APK artifact must remain downstream of every required test and emulator gate. Do not expose a new APK until the exact final head passes.

Keep changes limited to apps/leon-android/**, the Leon workflow if needed, and Leon-specific docs/tests.

When implementation is complete, update this PR with exact head SHA, defects/root causes, before/after evidence, test results, emulator evidence, and residual real-device limitations. Then request independent Codex code and security review on the final head. Do not merge your own application-code changes.
