# Leon — rendered preview

Frames rendered straight from the shipped rig and artwork, so Leon can be reviewed without
installing the APK. Nothing here is hand-drawn or touched up: an offline harness compiles the real
`ProceduralLeonArt` and `LeonRenderer` against a Java2D stand-in for `android.graphics` and draws the
same layers the phone draws.

| | |
|---|---|
| `leon-idle.png` | Full figure, IDLE, after three seconds of animation. |
| `leon-states-a.png` | IDLE · LISTENING · THINKING · SPEAKING |
| `leon-states-b.png` | HAPPY · SERIOUS · SLEEPY · ATTENTION |
| `leon-blink-visemes.png` | A blink at 0% / 50% / 100%, then the A, E, O, M-B-P and F-V mouth shapes. |
| `leon-head-turn.png` | Head yaw left / centre / right and pitch up, showing the 2.5D turn. |

Two things worth looking for, because they are what separate a rig from a moving picture:

- In the blink strip, only the **eyelids** change. The head, brows and mouth are identical across
  all three frames.
- In the head-turn strip, the head shifts and foreshortens while the face layers slide the other way
  inside it. The torso, hips and feet do not move at all.

These are a snapshot of one commit, not a build artifact. Regenerate them if the artwork changes.
