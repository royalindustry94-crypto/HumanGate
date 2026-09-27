package ai.leon.companion.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands-free voice capture: listens continuously and hands back one clip per utterance, instead of
 * {@link LeonVoiceRecorder}'s push-to-talk model. Detects speech by simple amplitude (RMS) energy
 * over short windows -- no wake word, no on-device speech model, so nothing is sent anywhere and no
 * provider cost is incurred until the user has actually said something.
 *
 * <p>Runs one continuous capture thread from {@link #start} to {@link #stop}. Within that, a small
 * state machine decides when an utterance begins and ends:
 * <ul>
 *   <li><b>Waiting</b> -- discarding audio, watching energy. A short rolling pre-roll buffer is
 *       kept so the very start of a word is not clipped once speech is confirmed.</li>
 *   <li><b>Capturing</b> -- buffering audio (starting from the pre-roll) once energy has stayed
 *       above the threshold for {@link #SPEECH_CONFIRM_MS}, filtering out brief noise blips.</li>
 * </ul>
 * Capture ends after {@link #TRAILING_SILENCE_MS} of low energy (a natural pause), or at the
 * {@link #MAX_DURATION_MS} safety ceiling. A clip shorter than {@link #MIN_CAPTURE_MS} once
 * trimmed is treated as a false trigger and dropped rather than sent anywhere.
 *
 * <p>Whether a new utterance may begin at all is up to the caller via {@link ArmGate}: this class
 * has no idea whether Leon is mid-turn or already speaking a reply -- capturing while Leon's own
 * voice is playing back through the speaker would let him hear (and reply to) himself.
 */
public final class LeonVoiceActivityRecorder {
    /** Decides whether a new utterance may begin right now. Checked continuously while waiting. */
    public interface ArmGate {
        boolean isArmed();
    }

    public interface Listener {
        /**
         * Energy crossed the threshold for long enough to confirm the user has started talking.
         * Always followed, once the trailing pause is detected, by exactly one of
         * {@link #onSpeechCaptured} or {@link #onSpeechRejected} -- a caller that puts Leon into a
         * listening posture here must be able to rely on one of those two arriving to move him out
         * of it again.
         */
        void onSpeechStarted();

        /** The utterance was long enough to be real speech, trimmed to speech plus a little lead/trail. */
        void onSpeechCaptured(byte[] wavBytes);

        /** Confirmed speech turned out shorter than {@link #MIN_CAPTURE_MS} once it ended -- a blip, not an utterance. */
        void onSpeechRejected();

        /** The mic could not be opened. Fires once; the recorder is not running after this. */
        void onFailed(String message);
    }

    private static final int SAMPLE_RATE_HZ = 16000;
    // ~30ms per chunk: fine enough to react quickly, coarse enough that RMS over one chunk is a
    // stable amplitude estimate rather than reacting to single-sample noise.
    private static final int CHUNK_SAMPLES = 480;
    // Below this, the room is being treated as silent. Chosen well above typical phone mic noise
    // floor with VOICE_RECOGNITION's noise suppression, well below a normal speaking voice close to
    // the device -- deliberately conservative (a bit of background noise should not self-trigger).
    private static final short SPEECH_AMPLITUDE_THRESHOLD = 900;
    // Energy must stay up for this long before an utterance is confirmed, so a cough, a tap, or a
    // door closing does not start a capture (and a provider call) on its own.
    private static final long SPEECH_CONFIRM_MS = 150L;
    // A natural pause once talking stops before the clip is finalised and sent.
    private static final long TRAILING_SILENCE_MS = 900L;
    // Same hard ceiling as LeonVoiceRecorder's push-to-talk: nothing here can record forever.
    private static final long MAX_DURATION_MS = 30_000L;
    // Below this once trimmed, the "utterance" was too short to be real speech (the confirm window
    // itself, plus a sliver) -- almost certainly a blip that crossed the threshold briefly.
    private static final long MIN_CAPTURE_MS = 400L;
    // How much audio strictly before the confirmed start is kept, so the first syllable is not cut.
    private static final long PRE_ROLL_MS = 300L;

    private static final int CHUNK_BYTES = CHUNK_SAMPLES * 2; // 16-bit mono
    private static final long CHUNK_MS = CHUNK_SAMPLES * 1000L / SAMPLE_RATE_HZ;
    private static final int PRE_ROLL_CHUNKS =
            (int) Math.max(1, PRE_ROLL_MS / Math.max(1, CHUNK_MS));

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread captureThread;
    private ArmGate gate;
    private Listener listener;

    /**
     * @return false if the mic could not be opened; the caller has nothing further to wait for.
     */
    public boolean start(ArmGate gate, Listener listener) {
        if (!running.compareAndSet(false, true)) return false;
        this.gate = gate;
        this.listener = listener;

        final int minBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) {
            running.set(false);
            notifyFailed("This device does not support the required audio format.");
            return false;
        }

        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufferBytes * 4, CHUNK_BYTES * 8));
        } catch (SecurityException | IllegalArgumentException e) {
            running.set(false);
            notifyFailed("Could not start the microphone.");
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            running.set(false);
            notifyFailed("Could not start the microphone.");
            return false;
        }

        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                listenLoop(record);
            }
        }, "leon-voice-activity");
        captureThread.start();
        return true;
    }

    /** Stops listening entirely and releases the mic. Any utterance mid-capture is discarded. */
    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void listenLoop(AudioRecord record) {
        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                notifyFailed("Could not start the microphone.");
                return;
            }

            Deque<byte[]> preRoll = new ArrayDeque<>(PRE_ROLL_CHUNKS);
            ByteArrayOutputStream capture = null;
            long aboveThresholdMs = 0L;
            long belowThresholdMs = 0L;
            long capturedMs = 0L;
            byte[] buffer = new byte[CHUNK_BYTES];

            while (running.get()) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                byte[] chunk = read == buffer.length ? buffer.clone()
                        : java.util.Arrays.copyOf(buffer, read);
                boolean loud = rmsAmplitude(chunk, read) >= SPEECH_AMPLITUDE_THRESHOLD;

                if (capture == null) {
                    // Waiting: only ever arm a new utterance when the caller says it is safe to.
                    if (!(gate == null || gate.isArmed())) {
                        aboveThresholdMs = 0L;
                        preRoll.clear();
                        continue;
                    }
                    preRoll.addLast(chunk);
                    while (preRoll.size() > PRE_ROLL_CHUNKS) preRoll.removeFirst();
                    aboveThresholdMs = loud ? aboveThresholdMs + CHUNK_MS : 0L;
                    if (aboveThresholdMs >= SPEECH_CONFIRM_MS) {
                        capture = new ByteArrayOutputStream();
                        for (byte[] pre : preRoll) capture.write(pre, 0, pre.length);
                        capturedMs = preRoll.size() * CHUNK_MS;
                        preRoll.clear();
                        belowThresholdMs = 0L;
                        notifySpeechStarted();
                    }
                } else {
                    capture.write(chunk, 0, read);
                    capturedMs += CHUNK_MS;
                    belowThresholdMs = loud ? 0L : belowThresholdMs + CHUNK_MS;
                    boolean silenceEnded = belowThresholdMs >= TRAILING_SILENCE_MS;
                    boolean tooLong = capturedMs >= MAX_DURATION_MS;
                    if (silenceEnded || tooLong) {
                        finish(capture.toByteArray(), capturedMs);
                        capture = null;
                        aboveThresholdMs = 0L;
                    }
                }
            }
        } catch (RuntimeException e) {
            notifyFailed("Recording failed.");
        } finally {
            running.set(false);
            try {
                record.stop();
            } catch (RuntimeException ignored) {
                // Already stopped or never fully started; nothing further to release cleanly.
            }
            record.release();
        }
    }

    private void finish(byte[] pcm, long durationMs) {
        if (durationMs < MIN_CAPTURE_MS || pcm.length == 0) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) listener.onSpeechRejected();
                }
            });
            return;
        }
        final byte[] wav = LeonWavEncoder.wrapAsWav(pcm, SAMPLE_RATE_HZ);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onSpeechCaptured(wav);
            }
        });
    }

    private void notifySpeechStarted() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onSpeechStarted();
            }
        });
    }

    private void notifyFailed(final String message) {
        running.set(false);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onFailed(message);
            }
        });
    }

    /** Root-mean-square amplitude of the 16-bit little-endian samples in {@code bytes[0, len)}. */
    static int rmsAmplitude(byte[] bytes, int len) {
        int sampleCount = len / 2;
        if (sampleCount == 0) return 0;
        long sumSquares = 0L;
        for (int i = 0; i + 1 < len; i += 2) {
            short sample = (short) ((bytes[i] & 0xff) | (bytes[i + 1] << 8));
            sumSquares += (long) sample * (long) sample;
        }
        return (int) Math.sqrt((double) sumSquares / sampleCount);
    }
}
