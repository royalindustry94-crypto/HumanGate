package ai.leon.companion.anim;

/**
 * Mouth shapes Leon can form. Each viseme is expressed as a blend of mouth {@link LeonChannel}s
 * rather than a hard-coded frame, so audio-driven lip sync can later cross-fade between them and
 * scale them by loudness without any new rig work.
 *
 * <p>Covers the set the milestone requires: closed, neutral/open, A, E, O, U, M/B/P and F/V.
 */
public enum Viseme {
    /** Silence. Lips together, relaxed. */
    CLOSED(0f, 0f, 0f, 0f, 0f),
    /** Generic open vowel, used when no phoneme detail is available. */
    NEUTRAL_OPEN(0.42f, 0.1f, 0.18f, 0f, 0f),
    /** "ah" — wide open jaw. */
    A(0.92f, 0.34f, 0.05f, 0f, 0f),
    /** "eh"/"ee" — stretched corners, moderate aperture. */
    E(0.44f, 0.86f, 0f, 0f, 0f),
    /** "oh" — rounded and open. */
    O(0.62f, 0f, 0.88f, 0f, 0f),
    /** "oo" — tightly rounded, nearly closed. */
    U(0.24f, 0f, 1f, 0f, 0f),
    /** Bilabial stop — lips pressed shut. */
    MBP(0f, 0.12f, 0f, 1f, 0f),
    /** Labiodental — lower lip against the upper teeth. */
    FV(0.18f, 0.3f, 0f, 0.25f, 0.9f);

    public final float jawDrop;
    public final float wide;
    public final float round;
    public final float press;
    public final float teeth;

    Viseme(float jawDrop, float wide, float round, float press, float teeth) {
        this.jawDrop = jawDrop;
        this.wide = wide;
        this.round = round;
        this.press = press;
        this.teeth = teeth;
    }

    /** Writes this viseme into the mouth channels of {@code pose}, scaled by {@code intensity}. */
    public void applyTo(LeonPose pose, float intensity) {
        float k = ai.leon.companion.util.Mathx.clamp01(intensity);
        pose.set(LeonChannel.JAW_DROP, jawDrop * k);
        pose.set(LeonChannel.MOUTH_WIDE, wide * k);
        pose.set(LeonChannel.MOUTH_ROUND, round * k);
        pose.set(LeonChannel.MOUTH_PRESS, press * k);
        pose.set(LeonChannel.MOUTH_TEETH, teeth * k);
    }

    /** Case-insensitive lookup that also accepts the common aliases used by TTS phoneme sets. */
    public static Viseme fromName(String name) {
        if (name == null) return null;
        String n = name.trim().toUpperCase(java.util.Locale.US);
        switch (n) {
            case "SIL":
            case "SILENCE":
            case "X":
            case "CLOSED":
                return CLOSED;
            case "NEUTRAL":
            case "NEUTRAL_OPEN":
            case "OPEN":
                return NEUTRAL_OPEN;
            case "AA":
            case "AH":
            case "A":
                return A;
            case "EE":
            case "EH":
            case "IY":
            case "I":
            case "E":
                return E;
            case "OH":
            case "OW":
            case "O":
                return O;
            case "OO":
            case "UW":
            case "U":
                return U;
            case "M":
            case "B":
            case "P":
            case "MBP":
                return MBP;
            case "F":
            case "V":
            case "FV":
                return FV;
            default:
                return null;
        }
    }
}
