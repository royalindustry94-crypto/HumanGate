package ai.leon.companion.voice;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide text-to-speech output for Leon.
 *
 * <p>This deliberately does not invent AI replies. It speaks only text explicitly supplied by the
 * caller, while driving Leon's real SPEAKING state for the lifetime of the TTS utterance. Because
 * the animation controllers already attach their synthetic viseme driver to SPEAKING, the mouth
 * moves on both the control-centre preview and the floating overlay while audio is playing.
 */
public final class LeonSpeechController implements TextToSpeech.OnInitListener {
    public enum Status {
        INITIALIZING,
        READY,
        SPEAKING,
        ERROR
    }

    public interface Listener {
        void onSpeechStatusChanged(Status status, String detail);
    }

    private final Context appContext;
    private final LeonStateController states;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger utteranceCounter = new AtomicInteger();

    private final TextToSpeech tts;
    private Listener listener;
    private Status status = Status.INITIALIZING;
    private boolean ready;
    private boolean released;
    private String pendingText;
    private String activeUtteranceId;

    public LeonSpeechController(Context context, LeonStateController states) {
        if (context == null) throw new IllegalArgumentException("context required");
        if (states == null) throw new IllegalArgumentException("state controller required");
        this.appContext = context.getApplicationContext();
        this.states = states;
        this.tts = new TextToSpeech(appContext, this);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) listener.onSpeechStatusChanged(status, null);
    }

    public Status status() {
        return status;
    }

    public boolean isReady() {
        return ready;
    }

    @Override
    public void onInit(final int result) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (released) return;
                if (result != TextToSpeech.SUCCESS) {
                    fail("Text-to-speech could not start on this device.");
                    return;
                }

                int languageResult = tts.setLanguage(preferredEnglishLocale());
                if (languageResult == TextToSpeech.LANG_MISSING_DATA
                        || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    languageResult = tts.setLanguage(Locale.US);
                }
                if (languageResult == TextToSpeech.LANG_MISSING_DATA
                        || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    fail("No supported English text-to-speech voice is installed.");
                    return;
                }

                // Slightly lower and slower than the Android default so Leon reads as a calm adult
                // voice without requiring a paid/cloud voice provider for this first milestone.
                tts.setPitch(0.90f);
                tts.setSpeechRate(0.95f);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(final String utteranceId) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (!isActive(utteranceId)) return;
                                states.request(LeonState.SPEAKING);
                                setStatus(Status.SPEAKING, "Leon is speaking.");
                            }
                        });
                    }

                    @Override
                    public void onDone(final String utteranceId) {
                        finishUtterance(utteranceId, null);
                    }

                    @Override
                    public void onError(final String utteranceId) {
                        finishUtterance(utteranceId, "Speech playback failed.");
                    }

                    @Override
                    public void onError(final String utteranceId, int errorCode) {
                        finishUtterance(utteranceId, "Speech playback failed (code " + errorCode + ").");
                    }

                    @Override
                    public void onStop(final String utteranceId, boolean interrupted) {
                        finishUtterance(utteranceId, null);
                    }
                });

                ready = true;
                setStatus(Status.READY, "Voice ready.");
                if (pendingText != null) {
                    String queued = pendingText;
                    pendingText = null;
                    speakReady(queued);
                }
            }
        });
    }

    /**
     * Speaks exactly the supplied text. While the TTS engine is still initialising, the most recent
     * request is queued and begins as soon as the engine becomes ready.
     *
     * @return false only for blank text or after this controller has been released.
     */
    public boolean speak(String text) {
        if (released || text == null || text.trim().isEmpty()) return false;
        final String spoken = text.trim();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (released) return;
                if (!ready) {
                    pendingText = spoken;
                    setStatus(Status.INITIALIZING, "Voice is getting ready...");
                    return;
                }
                speakReady(spoken);
            }
        });
        return true;
    }

    public void stop() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (released) return;
                pendingText = null;
                activeUtteranceId = null;
                tts.stop();
                states.request(LeonState.IDLE);
                setStatus(ready ? Status.READY : Status.INITIALIZING,
                        ready ? "Voice stopped." : "Voice is getting ready...");
            }
        });
    }

    public void shutdown() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (released) return;
                released = true;
                pendingText = null;
                activeUtteranceId = null;
                tts.stop();
                tts.shutdown();
                states.request(LeonState.IDLE);
            }
        });
    }

    private void speakReady(String text) {
        final String utteranceId = "leon-voice-" + utteranceCounter.incrementAndGet();
        activeUtteranceId = utteranceId;

        Bundle params = new Bundle();
        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        if (result == TextToSpeech.ERROR) {
            activeUtteranceId = null;
            states.request(LeonState.IDLE);
            // A single utterance rejection does not mean the engine itself is unusable. Keep the
            // initialized controller ready so the next valid Speak request can be attempted.
            setStatus(Status.ERROR, "The installed text-to-speech engine rejected that utterance.");
        }
    }

    private void finishUtterance(final String utteranceId, final String error) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!isActive(utteranceId)) return;
                activeUtteranceId = null;
                states.request(LeonState.IDLE);
                if (error != null) {
                    setStatus(Status.ERROR, error);
                } else {
                    setStatus(Status.READY, "Voice ready.");
                }
            }
        });
    }

    private boolean isActive(String utteranceId) {
        return !released && utteranceId != null && utteranceId.equals(activeUtteranceId);
    }

    private void fail(String message) {
        ready = false;
        activeUtteranceId = null;
        states.request(LeonState.IDLE);
        setStatus(Status.ERROR, message);
    }

    private void setStatus(Status next, String detail) {
        status = next;
        if (listener != null) listener.onSpeechStatusChanged(next, detail);
    }

    private static Locale preferredEnglishLocale() {
        Locale device = Locale.getDefault();
        return "en".equalsIgnoreCase(device.getLanguage()) ? device : Locale.US;
    }
}
