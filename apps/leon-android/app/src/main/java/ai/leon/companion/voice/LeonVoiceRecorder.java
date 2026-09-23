package ai.leon.companion.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records one push-to-talk clip from the device mic as 16kHz mono 16-bit PCM, wrapped in a WAV
 * header so the backend's Whisper call receives a self-describing file. Runs its capture loop on a
 * dedicated thread; {@link Listener} callbacks are posted to the main thread.
 */
public final class LeonVoiceRecorder {
    public interface Listener {
        /** Recording produced at least one sample and stopped normally. */
        void onRecorded(byte[] wavBytes);

        /** Recording could not start or produced nothing usable. {@code message} is user-safe. */
        void onFailed(String message);
    }

    private static final int SAMPLE_RATE_HZ = 16000;
    // A conversational turn is a few seconds; this is a hard safety ceiling so a stuck button (or a
    // caller that forgets to call stop()) cannot record indefinitely and burn STT/LLM/TTS cost.
    private static final long MAX_DURATION_MS = 30_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean recording = new AtomicBoolean(false);

    private Thread captureThread;
    private Listener listener;

    /**
     * @return false if the mic could not be opened (permission missing, device busy, or the
     *         requested format is unsupported); the caller has nothing further to wait for.
     */
    public boolean start(Listener listener) {
        if (recording.get()) return false;
        this.listener = listener;

        final int minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) {
            notifyFailed("This device does not support the required audio format.");
            return false;
        }

        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBufferBytes * 4);
        } catch (SecurityException | IllegalArgumentException e) {
            notifyFailed("Could not start the microphone.");
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            notifyFailed("Could not start the microphone.");
            return false;
        }

        recording.set(true);
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop(record, minBufferBytes);
            }
        }, "leon-voice-record");
        captureThread.start();
        return true;
    }

    /** Stops capture; the pending {@link Listener} callback fires once buffered audio is flushed. */
    public void stop() {
        recording.set(false);
    }

    private void captureLoop(AudioRecord record, int chunkBytes) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] buffer = new byte[chunkBytes];
        long startedAt = System.currentTimeMillis();
        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                notifyFailed("Could not start the microphone.");
                return;
            }
            while (recording.get() && System.currentTimeMillis() - startedAt < MAX_DURATION_MS) {
                int read = record.read(buffer, 0, buffer.length);
                if (read > 0) pcm.write(buffer, 0, read);
            }
        } catch (RuntimeException e) {
            notifyFailed("Recording failed.");
            return;
        } finally {
            recording.set(false);
            try {
                record.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or never fully started; nothing further to release cleanly.
            }
            record.release();
        }

        byte[] pcmBytes = pcm.toByteArray();
        if (pcmBytes.length == 0) {
            notifyFailed("Didn't catch that -- no audio was recorded.");
            return;
        }
        final byte[] wav = wrapAsWav(pcmBytes);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onRecorded(wav);
            }
        });
    }

    private void notifyFailed(final String message) {
        recording.set(false);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onFailed(message);
            }
        });
    }

    /** Minimal 44-byte canonical WAV header around 16kHz mono 16-bit PCM samples. */
    private static byte[] wrapAsWav(byte[] pcm) {
        int byteRate = SAMPLE_RATE_HZ * 2;
        byte[] header = new byte[44];
        writeAscii(header, 0, "RIFF");
        writeLe32(header, 4, 36 + pcm.length);
        writeAscii(header, 8, "WAVE");
        writeAscii(header, 12, "fmt ");
        writeLe32(header, 16, 16);
        writeLe16(header, 20, (short) 1); // PCM
        writeLe16(header, 22, (short) 1); // mono
        writeLe32(header, 24, SAMPLE_RATE_HZ);
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
