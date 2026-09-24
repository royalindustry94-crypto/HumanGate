package ai.leon.companion.voice;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import ai.leon.companion.anim.Viseme;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns speech audio into a mouth-movement timeline by decoding it once (off the playback path)
 * and measuring loudness in short windows, rather than true phoneme timing.
 *
 * <p>No TTS provider used here exposes phoneme/viseme timestamps in its API response, so this is
 * the same approach most real-time talking-avatar implementations fall back to: the mouth opens
 * and closes in sync with the audio's actual loudness, cycling through a small set of open-mouth
 * shapes for visual variety rather than picking the "correct" shape for each sound. It reads as
 * natural, lively speech; it is not lip-reading-accurate, and does not try to be.
 */
final class LeonVoiceVisemeTimeline {
    static final class Event {
        final Viseme viseme;
        final float intensity;
        final long offsetMs;
        final long durationMs;

        Event(Viseme viseme, float intensity, long offsetMs, long durationMs) {
            this.viseme = viseme;
            this.intensity = intensity;
            this.offsetMs = offsetMs;
            this.durationMs = durationMs;
        }
    }

    private static final long WINDOW_MS = 60L;
    private static final float SILENCE_RMS_THRESHOLD = 0.02f;
    // Cycled through for open windows so a sustained loud passage does not hold one static shape.
    private static final Viseme[] OPEN_SHAPES = {
            Viseme.A, Viseme.NEUTRAL_OPEN, Viseme.E, Viseme.O, Viseme.NEUTRAL_OPEN, Viseme.U
    };

    private LeonVoiceVisemeTimeline() {
    }

    /** @return the timeline, or an empty list if the clip could not be decoded for any reason. */
    static List<Event> build(byte[] audioBytes) {
        List<Event> events = new ArrayList<>();
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(new LeonVoiceAudioSource(audioBytes));
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) return events;
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            extractor.selectTrack(trackIndex);

            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            decodeAndMeasure(extractor, codec, events);
        } catch (Exception e) {
            // A malformed or unsupported clip should not break playback -- an empty timeline just
            // means the mouth stays at rest while the (still perfectly audible) speech plays.
            return new ArrayList<>();
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
                // Best-effort teardown; nothing left to salvage if this fails.
            }
            extractor.release();
        }
        return events;
    }

    private static int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    private static void decodeAndMeasure(MediaExtractor extractor, MediaCodec codec,
            List<Event> events) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false;
        boolean outputDone = false;
        int sampleRate = 0;
        int channelCount = 1;

        long windowSum = 0L;
        long windowCount = 0L;
        long samplesSoFar = 0L;

        final long timeoutUs = 10_000L;
        while (!outputDone) {
            if (!inputDone) {
                int inIndex = codec.dequeueInputBuffer(timeoutUs);
                if (inIndex >= 0) {
                    ByteBuffer inBuffer = codec.getInputBuffer(inIndex);
                    int sampleSize = inBuffer == null ? -1 : extractor.readSampleData(inBuffer, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIndex = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFormat = codec.getOutputFormat();
                sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                channelCount = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } else if (outIndex >= 0) {
                ByteBuffer outBuffer = codec.getOutputBuffer(outIndex);
                if (outBuffer != null && info.size > 0 && sampleRate > 0) {
                    outBuffer.position(info.offset);
                    outBuffer.limit(info.offset + info.size);
                    ShortBufferView pcm = ShortBufferView.of(outBuffer);
                    long windowSamples = Math.max(1L, sampleRate * channelCount * WINDOW_MS / 1000L);

                    for (int i = 0; i < pcm.length; i++) {
                        windowSum += Math.abs(pcm.get(i));
                        windowCount++;
                        samplesSoFar++;
                        if (windowCount >= windowSamples) {
                            emitWindow(events, windowSum, windowCount, samplesSoFar, sampleRate,
                                    channelCount);
                            windowSum = 0L;
                            windowCount = 0L;
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }
        if (windowCount > 0 && sampleRate > 0) {
            emitWindow(events, windowSum, windowCount, samplesSoFar, sampleRate, channelCount);
        }
    }

    private static void emitWindow(List<Event> events, long windowSum, long windowCount,
            long samplesSoFar, int sampleRate, int channelCount) {
        float meanAbs = windowSum / (float) windowCount;
        // 16-bit PCM full scale is 32768; a normal speech RMS sits well under that, so this is a
        // deliberately generous normalisation -- it is tuned for "reads as alive," not calibrated
        // against a reference loudness standard.
        float normalized = ai.leon.companion.util.Mathx.clamp01(meanAbs / 9000f);
        long offsetMs = (samplesSoFar - windowCount) * 1000L / ((long) sampleRate * channelCount);

        Viseme viseme;
        float intensity;
        if (normalized < SILENCE_RMS_THRESHOLD) {
            viseme = Viseme.CLOSED;
            intensity = 0f;
        } else {
            viseme = OPEN_SHAPES[events.size() % OPEN_SHAPES.length];
            intensity = 0.35f + normalized * 0.65f;
        }
        events.add(new Event(viseme, intensity, offsetMs, WINDOW_MS));
    }

    /** Minimal little-endian PCM16 view over a ByteBuffer's remaining bytes, mono-summed if stereo. */
    private static final class ShortBufferView {
        final ByteBuffer buffer;
        final int start;
        final int length;

        private ShortBufferView(ByteBuffer buffer, int start, int length) {
            this.buffer = buffer;
            this.start = start;
            this.length = length;
        }

        static ShortBufferView of(ByteBuffer buffer) {
            int remaining = buffer.remaining() / 2;
            return new ShortBufferView(buffer, buffer.position(), remaining);
        }

        short get(int i) {
            int base = start + i * 2;
            int lo = buffer.get(base) & 0xff;
            int hi = buffer.get(base + 1);
            return (short) ((hi << 8) | lo);
        }
    }
}
