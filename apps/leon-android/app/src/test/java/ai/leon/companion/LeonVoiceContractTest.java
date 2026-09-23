package ai.leon.companion;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LeonVoiceContractTest {
    private static String source(String relative) throws Exception {
        File[] candidates = {
                new File("app/src/main/java/" + relative),
                new File("src/main/java/" + relative),
                new File("apps/leon-android/app/src/main/java/" + relative)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Source file not found: " + relative);
    }

    private static String manifest() throws Exception {
        File[] candidates = {
                new File("src/main/AndroidManifest.xml"),
                new File("app/src/main/AndroidManifest.xml"),
                new File("apps/leon-android/app/src/main/AndroidManifest.xml")
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("AndroidManifest.xml not found");
    }

    @Test
    public void androidTtsDrivesRealSpeakingStateAndCompletion() throws Exception {
        String speech = source("ai/leon/companion/voice/LeonSpeechController.java");
        assertTrue(speech.contains("new TextToSpeech"));
        assertTrue(speech.contains("UtteranceProgressListener"));
        assertTrue(speech.contains("states.request(LeonState.SPEAKING)"));
        assertTrue(speech.contains("states.request(LeonState.IDLE)"));
        assertTrue(speech.contains("setPitch(0.90f)"));
        assertTrue(speech.contains("setSpeechRate(0.95f)"));
    }

    @Test
    public void runtimeOwnsOneLazyProcessWideSpeechController() throws Exception {
        String runtime = source("ai/leon/companion/LeonRuntime.java");
        assertTrue(runtime.contains("LeonSpeechController"));
        assertTrue(runtime.contains("public synchronized LeonSpeechController speech()"));
    }

    @Test
    public void controlCentreCanSpeakExactTypedTextWithoutPretendingItIsAi() throws Exception {
        String activity = source("ai/leon/companion/MainActivity.java");
        assertTrue(activity.contains("runtime.speech().speak"));
        assertTrue(activity.contains("Speak"));
        assertTrue(activity.contains("Stop voice"));
    }

    @Test
    public void manifestExposesTtsEngineDiscoveryWithoutNetworkPermission() throws Exception {
        String manifest = manifest();
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"));
        assertFalse(manifest.contains("android.permission.INTERNET"));
    }
}
