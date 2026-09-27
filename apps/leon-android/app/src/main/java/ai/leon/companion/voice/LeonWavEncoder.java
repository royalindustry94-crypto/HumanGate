package ai.leon.companion.voice;

/**
 * Wraps raw mono 16-bit PCM samples in a minimal canonical WAV header. Shared by every recorder in
 * this package ({@link LeonVoiceRecorder}, {@link LeonVoiceActivityRecorder}) so the backend's
 * Whisper call always receives the same self-describing container, byte for byte, regardless of
 * which one produced the clip.
 */
final class LeonWavEncoder {
    private LeonWavEncoder() {}

    static byte[] wrapAsWav(byte[] pcm, int sampleRateHz) {
        int byteRate = sampleRateHz * 2;
        byte[] header = new byte[44];
        writeAscii(header, 0, "RIFF");
        writeLe32(header, 4, 36 + pcm.length);
        writeAscii(header, 8, "WAVE");
        writeAscii(header, 12, "fmt ");
        writeLe32(header, 16, 16);
        writeLe16(header, 20, (short) 1); // PCM
        writeLe16(header, 22, (short) 1); // mono
        writeLe32(header, 24, sampleRateHz);
        writeLe32(header, 28, byteRate);
        writeLe16(header, 32, (short) 2); // block align
        writeLe16(header, 34, (short) 16); // bits per sample
        writeAscii(header, 36, "data");
        writeLe32(header, 40, pcm.length);

        byte[] out = new byte[header.length + pcm.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(pcm, 0, out, header.length, pcm.length);
        return out;
    }

    private static void writeAscii(byte[] out, int offset, String value) {
        for (int i = 0; i < value.length(); i++) out[offset + i] = (byte) value.charAt(i);
    }

    private static void writeLe16(byte[] out, int offset, short value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static void writeLe32(byte[] out, int offset, int value) {
        out[offset] = (byte) (value & 0xff);
        out[offset + 1] = (byte) ((value >> 8) & 0xff);
        out[offset + 2] = (byte) ((value >> 16) & 0xff);
        out[offset + 3] = (byte) ((value >> 24) & 0xff);
    }
}
