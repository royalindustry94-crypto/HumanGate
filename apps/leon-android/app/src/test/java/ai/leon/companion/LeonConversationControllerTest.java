package ai.leon.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import ai.leon.companion.anim.LeonLipSyncController;
import ai.leon.companion.state.LeonConversationController;
import ai.leon.companion.state.LeonState;
import ai.leon.companion.state.LeonStateController;

import org.junit.Before;
import org.junit.Test;

/**
 * The seam the AI brain will plug into. The important property is that with no backend attached the
 * controller says so rather than fabricating a reply.
 */
public class LeonConversationControllerTest {
    private LeonStateController states;
    private LeonLipSyncController lipSync;
    private LeonConversationController conversation;

    @Before
    public void setUp() {
        states = new LeonStateController();
        lipSync = new LeonLipSyncController(1L);
        conversation = new LeonConversationController(states, lipSync);
    }

    @Test
    public void withNoBackendItReportsNoBackendAndDoesNotInventAReply() {
        assertFalse(conversation.hasBackend());
        assertEquals(LeonConversationController.Status.NO_BACKEND, conversation.status());
        assertFalse("submitting must fail honestly", conversation.submit("hello"));
        assertEquals("Leon must not pretend to be thinking", LeonState.IDLE, states.state());
        org.junit.Assert.assertNull(conversation.lastReply());
    }

    @Test
    public void listeningWorksWithoutABackend() {
        conversation.beginListening();
        assertEquals(LeonState.LISTENING, states.state());
    }

    @Test
    public void aBackendDrivesTheFullConversationArc() {
        final java.util.List<LeonState> seen = new java.util.ArrayList<>();
        states.addListener(new LeonStateController.Listener() {
            @Override
            public void onLeonStateChanged(LeonState previous, LeonState current) {
                seen.add(current);
            }
        });

        conversation.setBackend(new LeonConversationController.Backend() {
            @Override
            public void submitTurn(String userText, LeonConversationController.TurnCallback callback) {
                callback.onThinking();
                callback.onSpeakingStarted("Reply to: " + userText);
                callback.onSpeakingFinished();
            }

            @Override
            public void cancel() {
            }
        });
        assertEquals(LeonConversationController.Status.IDLE, conversation.status());

        assertTrue(conversation.submit("are you there"));
        assertTrue(seen.contains(LeonState.THINKING));
        assertTrue(seen.contains(LeonState.SPEAKING));
        assertEquals(LeonState.IDLE, states.state());
        assertNotNull(conversation.lastReply());
        assertTrue(conversation.lastReply().contains("are you there"));
    }

    @Test
    public void aBackendDrivesTheFullConversationArcFromRecordedAudioToo() {
        conversation.setBackend(new LeonConversationController.Backend() {
            @Override
            public void submitTurn(String userText, LeonConversationController.TurnCallback callback) {
                throw new AssertionError("audio input must not go through the text path");
            }

            @Override
            public void submitAudioTurn(byte[] audioWav,
                    LeonConversationController.TurnCallback callback) {
                callback.onSpeakingStarted("heard: " + audioWav.length + " bytes");
                callback.onSpeakingFinished();
            }

            @Override
            public void cancel() {
            }
        });

        assertTrue(conversation.submitAudio(new byte[]{1, 2, 3}));
        assertEquals(LeonState.IDLE, states.state());
        assertEquals("heard: 3 bytes", conversation.lastReply());
    }

    @Test
    public void submitAudioWithoutABackendFailsHonestlyLikeSubmit() {
        assertFalse(conversation.submitAudio(new byte[]{1}));
        assertEquals(LeonConversationController.Status.NO_BACKEND, conversation.status());
    }

    @Test
    public void aBackendThatDoesNotImplementAudioTurnsFailsThatTurnCleanly() {
        // The default submitAudioTurn on Backend exists so a text-only backend does not have to
        // implement it just to remain valid; a caller that tries voice input against one must still
        // land back in a normal idle state with an honest message, not silently do nothing.
        conversation.setBackend(new LeonConversationController.Backend() {
            @Override
            public void submitTurn(String userText, LeonConversationController.TurnCallback callback) {
            }

            @Override
            public void cancel() {
            }
        });

        assertTrue(conversation.submitAudio(new byte[]{1}));
        assertEquals(LeonState.IDLE, states.state());
    }

    @Test
    public void reportFailureReturnsToIdleWithTheMessage() {
        final String[] reported = new String[1];
        conversation.setListener(new LeonConversationController.Listener() {
            @Override
            public void onConversationStatusChanged(LeonConversationController.Status status,
                                                    String detail) {
                if (detail != null) reported[0] = detail;
            }
        });
        conversation.beginListening();

        conversation.reportFailure("the microphone could not be opened");

        assertEquals(LeonState.IDLE, states.state());
        assertEquals("the microphone could not be opened", reported[0]);
    }

    @Test
    public void aFailedTurnReturnsLeonToIdleWithAMessage() {
        final String[] reported = new String[1];
        conversation.setListener(new LeonConversationController.Listener() {
            @Override
            public void onConversationStatusChanged(LeonConversationController.Status status,
                                                    String detail) {
                if (detail != null) reported[0] = detail;
            }
        });
        conversation.setBackend(new LeonConversationController.Backend() {
            @Override
            public void submitTurn(String userText, LeonConversationController.TurnCallback callback) {
                callback.onFailed("the network is unreachable");
            }

            @Override
            public void cancel() {
            }
        });
        conversation.submit("hi");
        assertEquals(LeonState.IDLE, states.state());
        assertEquals("the network is unreachable", reported[0]);
    }

    @Test
    public void cancellingStopsSpeechAndReturnsToIdle() {
        final boolean[] cancelled = new boolean[1];
        conversation.setBackend(new LeonConversationController.Backend() {
            @Override
            public void submitTurn(String userText, LeonConversationController.TurnCallback callback) {
                callback.onSpeakingStarted("talking");
            }

            @Override
            public void cancel() {
                cancelled[0] = true;
            }
        });
        conversation.submit("stop");
        assertEquals(LeonState.SPEAKING, states.state());
        conversation.cancel();
        assertTrue(cancelled[0]);
        assertEquals(LeonState.IDLE, states.state());
    }

    @Test
    public void removingTheBackendReturnsToNoBackend() {
        conversation.setBackend(new LeonConversationController.Backend() {
            @Override
            public void submitTurn(String t, LeonConversationController.TurnCallback c) {
            }

            @Override
            public void cancel() {
            }
        });
        conversation.setBackend(null);
        assertEquals(LeonConversationController.Status.NO_BACKEND, conversation.status());
    }
}
