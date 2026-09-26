package ai.leon.companion.state;

import ai.leon.companion.anim.LeonVisemeSink;

/**
 * The seam between Leon's animation and a future AI brain. It owns the conversational state arc
 * — listening, thinking, speaking, back to idle — and nothing else: no HTTP, no model, no audio.
 *
 * <p>A backend is supplied later by implementing {@link Backend}. Until one is attached the
 * controller reports {@link Status#NO_BACKEND} rather than inventing a reply, so the absence of an
 * AI layer is visible in the UI instead of being papered over.
 */
public final class LeonConversationController {
    public enum Status {
        /** No AI backend has been attached yet. */
        NO_BACKEND,
        IDLE,
        LISTENING,
        THINKING,
        SPEAKING
    }

    /** Implemented later by the AI/voice layer. Called off the render thread. */
    public interface Backend {
        /**
         * Starts a turn. The implementation is expected to call the supplied {@link TurnCallback}
         * as the reply becomes available, and to feed visemes to
         * {@link LeonVisemeSink#onViseme} as speech audio plays.
         */
        void submitTurn(String userText, TurnCallback callback);

        /**
         * Starts a turn from a recorded speech clip instead of known text -- the backend is
         * expected to transcribe it itself before generating a reply. Default implementation fails
         * immediately, so a backend that only understands typed text (or none at all) does not need
         * to implement this to remain a valid {@link Backend}.
         *
         * @param audioWav a WAV-encoded clip, e.g. from {@link ai.leon.companion.voice.LeonVoiceRecorder}
         */
        default void submitAudioTurn(byte[] audioWav, TurnCallback callback) {
            callback.onFailed("This build cannot understand spoken audio yet.");
        }

        /** Cancels the in-flight turn, if any. */
        void cancel();
    }

    public interface TurnCallback {
        /** The backend has accepted the turn and is working on it. */
        void onThinking();

        /** Speech (or text) playback is starting. */
        void onSpeakingStarted(String replyText);

        /** The turn finished normally. */
        void onSpeakingFinished();

        /** The turn failed. {@code message} is safe to show to the user. */
        void onFailed(String message);
    }

    public interface Listener {
        void onConversationStatusChanged(Status status, String detail);
    }

    private final LeonStateController states;
    private final LeonVisemeSink lipSync;

    private Backend backend;
    private Listener listener;
    private Status status = Status.NO_BACKEND;
    private String lastReply;

    public LeonConversationController(LeonStateController states, LeonVisemeSink lipSync) {
        if (states == null) throw new IllegalArgumentException("state controller required");
        if (lipSync == null) throw new IllegalArgumentException("viseme sink required");
        this.states = states;
        this.lipSync = lipSync;
    }

    public void setBackend(Backend backend) {
        this.backend = backend;
        setStatus(backend == null ? Status.NO_BACKEND : Status.IDLE, null);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Status status() {
        return status;
    }

    public boolean hasBackend() {
        return backend != null;
    }

    public String lastReply() {
        return lastReply;
    }

    /** Puts Leon into his listening posture. Independent of whether a backend exists. */
    public void beginListening() {
        states.request(LeonState.LISTENING);
        setStatus(backend == null ? Status.NO_BACKEND : Status.LISTENING, null);
    }

    /**
     * Submits a turn to the attached backend.
     *
     * @return false when no backend is attached; Leon stays where he is and the caller should tell
     *         the user the AI layer is not connected yet.
     */
    public boolean submit(String userText) {
        if (backend == null) {
            setStatus(Status.NO_BACKEND, "No AI backend is attached to this build yet.");
            return false;
        }
        states.request(LeonState.THINKING);
        setStatus(Status.THINKING, null);
        backend.submitTurn(userText, newTurnCallback());
        return true;
    }

    /**
     * Submits a turn from a recorded speech clip -- the same conversation arc as {@link
     * #submit(String)}, but the backend transcribes {@code audioWav} itself rather than being
     * given text up front. Typically called after {@link #beginListening()} once a clip is ready.
     *
     * @return false when no backend is attached, or the attached backend does not implement
     *         {@link Backend#submitAudioTurn} (its default rejects the turn via the callback, so
     *         the state machine still resolves back to idle -- this only reports the earlier,
     *         "nothing is attached at all" case the same way {@link #submit} does).
     */
    public boolean submitAudio(byte[] audioWav) {
        if (backend == null) {
            setStatus(Status.NO_BACKEND, "No AI backend is attached to this build yet.");
            return false;
        }
        states.request(LeonState.THINKING);
        setStatus(Status.THINKING, null);
        backend.submitAudioTurn(audioWav, newTurnCallback());
        return true;
    }

    private TurnCallback newTurnCallback() {
        return new TurnCallback() {
            @Override
            public void onThinking() {
                states.request(LeonState.THINKING);
                setStatus(Status.THINKING, null);
            }

            @Override
            public void onSpeakingStarted(String replyText) {
                lastReply = replyText;
                states.request(LeonState.SPEAKING);
                setStatus(Status.SPEAKING, replyText);
            }

            @Override
            public void onSpeakingFinished() {
                lipSync.stop();
                states.request(LeonState.IDLE);
                setStatus(Status.IDLE, null);
            }

            @Override
            public void onFailed(String message) {
                lipSync.stop();
                states.request(LeonState.IDLE);
                setStatus(Status.IDLE, message);
            }
        };
    }

    /**
     * Returns Leon to idle with a user-facing message, the same way a failed turn's {@link
     * TurnCallback#onFailed} would. For a failure that happens before a turn was ever submitted to
     * the backend (e.g. {@link ai.leon.companion.voice.LeonVoiceBackend}'s mic recording itself
     * failing) and so has no {@link TurnCallback} of its own to call.
     */
    public void reportFailure(String message) {
        lipSync.stop();
        states.request(LeonState.IDLE);
        setStatus(Status.IDLE, message);
    }

    /** Aborts the current turn and returns Leon to idle. */
    public void cancel() {
        if (backend != null) backend.cancel();
        lipSync.stop();
        states.request(LeonState.IDLE);
        setStatus(backend == null ? Status.NO_BACKEND : Status.IDLE, null);
    }

    private void setStatus(Status next, String detail) {
        this.status = next;
        if (listener != null) listener.onConversationStatusChanged(next, detail);
    }
}
