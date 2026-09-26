package ai.leon.companion.voice;

import ai.leon.companion.anim.LeonVisemeSink;
import ai.leon.companion.overlay.LeonPrefs;
import ai.leon.companion.state.LeonConversationController;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The real {@link LeonConversationController.Backend}: speech or text in, the backend's own
 * Whisper -> Claude -> OpenAI TTS turn out (apps/api/app/api/routes/leon_voice.py), played back
 * with amplitude-driven lip sync. Configuration (backend URL, app token) is read from
 * {@link LeonPrefs} on every turn, so a user editing it in Settings takes effect immediately.
 */
public final class LeonVoiceBackend implements LeonConversationController.Backend {
    // Keeps the reply-generation prompt bounded and the request small; matches the backend's own
    // MAX_HISTORY_TURNS cap (apps/api/app/services/leon_voice.py) so nothing here silently sends
    // more than the server would keep anyway.
    private static final int MAX_HISTORY_TURNS = 8;

    private final LeonConversationController conversation;
    private final LeonPrefs prefs;
    private final LeonVoiceApiClient api = new LeonVoiceApiClient();
    private final LeonVoicePlayer player;
    private final LeonVoiceRecorder recorder = new LeonVoiceRecorder();
    private final Deque<LeonVoiceApiClient.HistoryTurn> history = new ArrayDeque<>();

    private int turnId;

    /**
     * @param conversation the controller this backend is (or is about to be) attached to. Held so
     *                      a recorded mic clip can be handed back to {@link
     *                      LeonConversationController#submitAudio} -- routing it through the
     *                      controller rather than calling {@link #submitAudioTurn} directly is what
     *                      makes the THINKING/SPEAKING state transitions fire for voice input the
     *                      same way they already do for typed text.
     */
    public LeonVoiceBackend(LeonConversationController conversation, LeonPrefs prefs,
            LeonVisemeSink visemeSink) {
        this.conversation = conversation;
        this.prefs = prefs;
        this.player = new LeonVoicePlayer(visemeSink);
    }

    @Override
    public void submitTurn(String userText, LeonConversationController.TurnCallback callback) {
        final int id = ++turnId;
        api.submitText(prefs.leonVoiceBaseUrl(), prefs.leonVoiceAppToken(), userText,
                historySnapshot(), new ResultHandler(id, callback));
    }

    @Override
    public void submitAudioTurn(byte[] audioWav, LeonConversationController.TurnCallback callback) {
        final int id = ++turnId;
        api.submitAudio(prefs.leonVoiceBaseUrl(), prefs.leonVoiceAppToken(), audioWav,
                historySnapshot(), new ResultHandler(id, callback));
    }

    /**
     * Starts recording a push-to-talk clip. The caller is expected to have already called {@link
     * LeonConversationController#beginListening()} for the LISTENING posture; once the clip is
     * ready this hands it to {@link LeonConversationController#submitAudio} itself, so no further
     * action is needed here beyond eventually calling {@link #finishListening()}.
     *
     * @return false if the mic could not be opened (see {@link LeonVoiceRecorder#start}); the
     *         caller should tell the user and not expect any further callback.
     */
    public boolean startListening() {
        return recorder.start(new LeonVoiceRecorder.Listener() {
            @Override
            public void onRecorded(byte[] wavBytes) {
                conversation.submitAudio(wavBytes);
            }

            @Override
            public void onFailed(String message) {
                conversation.reportFailure(message);
            }
        });
    }

    /** Stops the in-progress recording, if any, triggering {@link #startListening}'s callback. */
    public void finishListening() {
        recorder.stop();
    }

    @Override
    public void cancel() {
        ++turnId; // Invalidates any in-flight ResultHandler for the old turn.
        recorder.stop();
        player.stop();
    }

    private List<LeonVoiceApiClient.HistoryTurn> historySnapshot() {
        return new ArrayList<>(history);
    }

    private void remember(String userText, String replyText) {
        history.addLast(new LeonVoiceApiClient.HistoryTurn("user", userText));
        history.addLast(new LeonVoiceApiClient.HistoryTurn("assistant", replyText));
        while (history.size() > MAX_HISTORY_TURNS) history.pollFirst();
    }

    /** Shared success/failure handling for both the text and audio submission paths. */
    private final class ResultHandler implements LeonVoiceApiClient.Callback2 {
        private final int id;
        private final LeonConversationController.TurnCallback callback;

        ResultHandler(int id, LeonConversationController.TurnCallback callback) {
            this.id = id;
            this.callback = callback;
        }

        @Override
        public void onSuccess(final String userText, final String replyText, byte[] replyAudio) {
            if (id != turnId) return; // Superseded by a newer turn or a cancel(); drop it silently.
            remember(userText, replyText);
            callback.onSpeakingStarted(replyText);
            player.play(replyAudio, new LeonVoicePlayer.Listener() {
                @Override
                public void onPlaybackFinished() {
                    if (id == turnId) callback.onSpeakingFinished();
                }

                @Override
                public void onPlaybackFailed(String message) {
                    if (id == turnId) callback.onFailed(message);
                }
            });
        }

        @Override
        public void onFailure(String message) {
            if (id == turnId) callback.onFailed(message);
        }
    }
}
