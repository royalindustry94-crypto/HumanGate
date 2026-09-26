package ai.leon.companion.voice;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import ai.leon.companion.anim.LeonVisemeSink;

import java.util.List;

/**
 * Plays one speech-reply clip through {@link MediaPlayer} and drives {@link LeonVisemeSink} in
 * sync with it via a precomputed loudness timeline ({@link LeonVoiceVisemeTimeline}) scheduled
 * against playback start, rather than polling {@link MediaPlayer#getCurrentPosition()}.
 */
final class LeonVoicePlayer {
    interface Listener {
        void onPlaybackFinished();

        /** {@code message} is safe to show to the user. */
        void onPlaybackFailed(String message);
    }

    private final LeonVisemeSink visemeSink;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer player;

    LeonVoicePlayer(LeonVisemeSink visemeSink) {
        this.visemeSink = visemeSink;
    }

    void play(final byte[] mp3Bytes, final Listener listener) {
        stop();
        final List<LeonVoiceVisemeTimeline.Event> timeline = LeonVoiceVisemeTimeline.build(mp3Bytes);

        try {
            player = new MediaPlayer();
            player.setDataSource(new LeonVoiceAudioSource(mp3Bytes));
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    scheduleTimeline(timeline);
                    mp.start();
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    finish(listener, null);
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    finish(listener, "Leon's voice could not play back.");
                    return true;
                }
            });
            player.prepareAsync();
        } catch (IllegalStateException | IllegalArgumentException e) {
            finish(listener, "Leon's voice could not play back.");
        }
    }

    /** Stops playback (if any) immediately and clears any pending viseme callbacks. */
    void stop() {
        main.removeCallbacksAndMessages(TOKEN);
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // Not started yet, or already stopped; nothing left to do.
            }
            player.release();
            player = null;
        }
        visemeSink.stop();
    }

    private static final Object TOKEN = new Object();

    private void scheduleTimeline(List<LeonVoiceVisemeTimeline.Event> timeline) {
        for (final LeonVoiceVisemeTimeline.Event event : timeline) {
            main.postAtTime(new Runnable() {
                @Override
                public void run() {
                    visemeSink.onViseme(event.viseme, event.intensity, event.durationMs);
                }
            }, TOKEN, android.os.SystemClock.uptimeMillis() + event.offsetMs);
        }
    }

    private void finish(Listener listener, String errorMessage) {
        main.removeCallbacksAndMessages(TOKEN);
        visemeSink.stop();
        if (player != null) {
            try {
                player.release();
            } catch (IllegalStateException ignored) {
                // Already released by a preceding stop(); nothing further to release.
            }
            player = null;
        }
        if (listener == null) return;
        if (errorMessage != null) {
            listener.onPlaybackFailed(errorMessage);
        } else {
            listener.onPlaybackFinished();
        }
    }
}
