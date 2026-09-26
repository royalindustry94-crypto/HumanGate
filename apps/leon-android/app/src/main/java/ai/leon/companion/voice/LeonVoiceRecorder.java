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
        // Claim the recording slot atomically: the old `if (recording.get()) return false; ...
        // recording.set(true)` left a window where two concurrent start() calls could both pass the
        // check and both open the mic -- two AudioRecords contending for the same hardware, two
        // capture threads racing to set `listener`, and (if both finish) two uploads of the same
        // turn. compareAndSet closes that window; every early return below must reset it back to
        // false so a rejected start() does not permanently wedge the recorder as "recording".
        if (!recording.compareAndSet(false, true)) return false;
        this.listener = listener;

        final int minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) {
            recording.set(false);
            notifyFailed("This device does not support the required audio format.");
            return false;
        }

        final AudioRecord record;
        try {
            // Sized larger than the minimum (not the minimum itself) so the hardware has slack to
            // buffer audio if the read loop is delayed by scheduling jitter or a GC pause -- Android's
            // own guidance is to avoid the bare minimum here, since it makes an under/overrun (dropped
            // or corrupted samples) more likely, not less. This buffer size is unrelated to how much
            // audio one read() call returns (that is bounded by minBufferBytes in captureLoop below),
            // so it has no bearing on the MAX_DURATION_MS ceiling.
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBufferBytes * 4);
        } catch (SecurityException | IllegalArgumentException e) {
            recording.set(false);
            notifyFailed("Could not start the microphone.");
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            recording.set(false);
            notifyFailed("Could not start the microphone.");
            return false;
        }

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
        final byte[] wav = LeonWavEncoder.wrapAsWav(pcmBytes, SAMPLE_RATE_HZ);
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
}
