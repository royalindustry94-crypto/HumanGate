# Leon Companion — Android

Leon is an animated companion that lives on the user's Android screen: a
transparent overlay above other apps that can be dragged and minimised and is
continuously animated. He can hold a voice conversation through the HumanGate
API.

## How he is drawn

One photograph, deformed by a 2D skeleton. There is no 3D model.

- `docs/leon-reference/leon-front-source.png` is the only source image. At build
  time, `tools/build_leon_production.py` (Gradle task `generateLeonProduction`,
  which runs before `preBuild`) crops and keys it into
  `leon/production/leon.png` (360x640 RGBA). It also writes `manifest.json`
  (mesh size 48x84, plus the landmarks from `assets/leon/leon-front.txt`).
- `render/LeonPuppetRenderer` draws that one bitmap with
  `Canvas.drawBitmapMesh`. `render/LeonMeshRig` skins the mesh vertices to 18
  body bones of the 27-bone `rig/LeonRig`, using a body/arm boundary measured
  from the texture's own alpha.
- Joint pivots (shoulder, elbow, wrist) sit on the photo's real joints. See the
  constants in `LeonRig`.

**Known limitation:** the jaw, eye, eyelid and mouth parts are animated in the
rig but never drawn. The mesh binds the whole head to one bone, so blinks and
lip-sync have no visible effect in this build.

## Behaviour

- Nine states: idle, listening, thinking, speaking, happy, serious, sleepy,
  attention and minimised. Transitions cross-fade and timings are randomised.
- Idle is always moving: breathing, head drift, weight shift and shoulder
  movement, plus an occasional walk.
- Drag to move, double-tap to minimise or restore, long-press for quick
  controls.
- Position and state survive rotation, process death, unlock and reboot.
- Rendering stops while the screen is off: 20 fps when idle, 60 fps while
  something is moving.

## Voice

`voice/LeonVoiceRecorder` captures 16 kHz mono 16-bit PCM and wraps it in a WAV
(RIFF) header. `voice/LeonVoiceApiClient` posts it as `multipart/form-data`
(part `audio`, `speech.wav`, `audio/wav`, plus an optional `history` JSON part)
to `POST {base_url}/leon/voice-turn`. The `Authorization: Bearer <app token>`
header carries the app token. The base URL and token are entered in the control
centre, and the token is kept in `LeonSecureTokenStore`.

The server side is `apps/api/app/api/routes/leon_voice.py`: Whisper for speech
to text, an Anthropic model for the reply, OpenAI for speech. It needs
`LEON_VOICE_APP_TOKEN`, `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`, and is
spend-capped by `LEON_VOICE_DAILY_SPEND_CAP_USD` and
`LEON_VOICE_MONTHLY_SPEND_CAP_USD` (fail-closed). `GET /leon/voice-status`
reports whether the server is configured, without making any paid call.

## Build and test

```bash
cd apps/leon-android
gradle :app:testDebugUnitTest
gradle :app:lintDebug
gradle :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Mesh tests read the generated texture, so they need `generateLeonProduction`
to have run (any Gradle build does it first). CI (`leon-android-apk.yml`) also
boots the APK on an emulator and checks a visual matrix of states.

## Installing and upgrading

Debug and CI builds are signed with the committed, public, debug-only
`app/leon-ci-debug.keystore`, and CI sets `versionCode` to the workflow run
number. A newer CI APK therefore installs over an older one. Any APK built
before commit `b56f3c1` used a different key and has to be uninstalled once.
Release signing is described in `app/build.gradle`.

1. Install the APK, open **Leon Companion** and tap **Enable Leon**.
2. Grant **Display over other apps** and, to talk to him, **Microphone**.

Android hides every overlay over secure surfaces such as the lock screen and
permission dialogs. Some OEMs (Xiaomi, Oppo, Vivo, Huawei, strict Samsung
battery modes) also need auto-start allowed by hand, or they kill the
foreground service after a reboot.

## Further reading

`docs/LEON_TECHNICAL_TRUTH_AUDIT.md` has the evidence-based audit of the
renderer, voice, signing and the mesh defects fixed so far.
