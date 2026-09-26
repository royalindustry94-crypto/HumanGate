package ai.leon.companion.voice;

import android.media.MediaDataSource;

/** Lets MediaExtractor/MediaPlayer read an in-memory audio clip with no temp file. */
final class LeonVoiceAudioSource extends MediaDataSource {
    private final byte[] data;

    LeonVoiceAudioSource(byte[] data) {
        this.data = data;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) {
        if (position >= data.length) return -1;
        int available = (int) Math.min(size, data.length - position);
        System.arraycopy(data, (int) position, buffer, offset, available);
        return available;
    }

    @Override
    public long getSize() {
        return data.length;
    }

    @Override
    public void close() {
        // Nothing to release; the backing array is just held in memory.
    }
}
