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

    private static String resXml(String filename) throws Exception {
        File[] candidates = {
                new File("src/main/res/xml/" + filename),
                new File("app/src/main/res/xml/" + filename),
                new File("apps/leon-android/app/src/main/res/xml/" + filename)
        };
        for (File file : candidates) {
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("res/xml/" + filename + " not found");
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
    public void controlCentreSurfacesSpeechStatusAndErrors() throws Exception {
        String activity = source("ai/leon/companion/MainActivity.java");
        String normalized = activity.replaceAll("\\s+", " ");
        assertTrue(normalized.matches(
                ".*public final class MainActivity extends Activity implements [^\\{]*LeonSpeechController\\.Listener[^\\{]*\\{.*"));
        assertTrue(activity.contains("runtime.speech().setListener(this)"));
        assertTrue(activity.contains("onSpeechStatusChanged"));
    }

    @Test
    public void perUtteranceRejectionDoesNotDisableFutureSpeech() throws Exception {
        String speech = source("ai/leon/companion/voice/LeonSpeechController.java");
        int rejection = speech.indexOf("if (result == TextToSpeech.ERROR)");
        assertTrue(rejection >= 0);
        String block = speech.substring(rejection, Math.min(speech.length(), rejection + 700));
        assertTrue(block.contains("setStatus(Status.ERROR"));
        assertFalse(block.contains("fail("));
        assertFalse(block.contains("ready = false"));
    }

    @Test
    public void backendErrorsSurfaceStageAndRequestIdInsteadOfOnlyStatusCode() throws Exception {
        String client = source("ai/leon/companion/voice/LeonVoiceApiClient.java");
        assertTrue(client.contains("X-Request-ID"));
        assertTrue(client.contains("object.optString(\"stage\""));
        assertTrue(client.contains("response.code() == 502"));
        assertTrue(client.contains("temporarily unavailable"));
    }

    @Test
    public void manifestExposesTtsEngineDiscoveryAndTheRealVoiceBackendPermissions() throws Exception {
        // INTERNET/RECORD_AUDIO used to be deliberately absent, locking in "no AI brain, no network,
        // Leon only speaks text you hand him" for this milestone. A real voice backend now exists
        // (ai.leon.companion.voice.LeonVoiceBackend calling apps/api's /leon/voice-turn), so both are
        // required; the on-device TTS engine discovery this test also covers is unaffected by that.
        String manifest = manifest();
        assertTrue(manifest.contains("android.intent.action.TTS_SERVICE"));
        assertTrue(manifest.contains("android.permission.INTERNET"));
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"));
    }

    @Test
    public void voiceAppTokenIsKeystoreEncryptedNotPlaintextPrefs() throws Exception {
        // LeonSecureTokenStore's own encrypt/decrypt round trip needs a real AndroidKeyStore
        // provider, which this project's plain-JUnit unit tests (no Robolectric shadow runtime,
        // no instrumented androidTest) cannot provide -- see LeonSecureTokenStore's own class
        // Javadoc. These contract checks instead pin the properties a security review actually
        // cares about: the token store uses real Keystore APIs (not a hand-rolled/reversible
        // scheme), and LeonPrefs no longer writes the token in plaintext.
        String store = source("ai/leon/companion/overlay/LeonSecureTokenStore.java");
        assertTrue(store.contains("KEYSTORE_PROVIDER = \"AndroidKeyStore\""));
        assertTrue(store.contains("KeyStore.getInstance(KEYSTORE_PROVIDER)"));
        assertTrue(store.contains("KeyProperties.BLOCK_MODE_GCM"));
        assertTrue(store.contains("setKeySize(256)"));

        String prefs = source("ai/leon/companion/overlay/LeonPrefs.java");
        assertTrue(prefs.contains("LeonSecureTokenStore.read(context)"));
        assertTrue(prefs.contains("LeonSecureTokenStore.store(context"));
        // The legacy plaintext key must only ever be read (for one-time migration) and removed,
        // never written -- putString on it would resurrect plaintext storage.
        assertFalse(prefs.contains("putString(KEY_VOICE_APP_TOKEN_PLAINTEXT_LEGACY"));
    }

    @Test
    public void voiceAppTokenFileIsExcludedFromBackupAndDeviceTransfer() throws Exception {
        String manifest = manifest();
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/leon_backup_rules\""));
        assertTrue(
                manifest.contains("android:dataExtractionRules=\"@xml/leon_data_extraction_rules\""));

        String legacyRules = resXml("leon_backup_rules.xml");
        assertTrue(legacyRules.contains("domain=\"sharedpref\""));
        assertTrue(legacyRules.contains("path=\"leon_secure.xml\""));

        String modernRules = resXml("leon_data_extraction_rules.xml");
        assertTrue(modernRules.contains("<cloud-backup>"));
        assertTrue(modernRules.contains("<device-transfer>"));
        int cloudBackupEnd = modernRules.indexOf("</cloud-backup>");
        int deviceTransferEnd = modernRules.indexOf("</device-transfer>");
        assertTrue(modernRules.substring(0, cloudBackupEnd).contains("leon_secure.xml"));
        assertTrue(
                modernRules.substring(cloudBackupEnd, deviceTransferEnd).contains("leon_secure.xml"));
    }
}
