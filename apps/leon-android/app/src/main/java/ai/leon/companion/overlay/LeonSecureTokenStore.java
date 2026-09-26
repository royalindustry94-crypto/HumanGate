package ai.leon.companion.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the Leon voice app token with an Android Keystore-backed AES key before persisting
 * it, in its own SharedPreferences file so it can be excluded from Auto Backup / device-transfer
 * backups (see res/xml/leon_backup_rules.xml, res/xml/leon_data_extraction_rules.xml)
 * independently of the rest of the app's (harmless) backed-up state.
 *
 * The key itself is generated inside the device's secure hardware/keystore and is never
 * exportable -- it is not included in Android backups regardless of the rules above, so the
 * stored ciphertext alone is unusable if it ever did leak into one. The explicit backup
 * exclusion is defense in depth / platform hygiene, not the only thing protecting the token.
 */
public final class LeonSecureTokenStore {
    private static final String TAG = "LeonSecureTokenStore";
    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEY_ALIAS = "leon_voice_app_token_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    static final String FILE = "leon_secure";
    private static final String KEY_IV = "token_iv";
    private static final String KEY_CIPHERTEXT = "token_ciphertext";

    private LeonSecureTokenStore() {
    }

    /** Encrypts and stores the token, or clears it when {@code token} is null/empty. */
    public static void store(Context context, String token) {
        SharedPreferences.Editor editor = securePrefs(context).edit();
        if (token == null || token.isEmpty()) {
            editor.remove(KEY_IV).remove(KEY_CIPHERTEXT).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            editor.putString(KEY_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            // Storage backends this depends on (Keystore, Cipher) can fail for reasons outside
            // this app's control (OEM Keystore bugs, a corrupted keystore) -- losing the save is
            // recoverable (the user re-enters it in Settings); crashing the overlay service is not.
            Log.w(TAG, "failed to encrypt voice app token; not saved", e);
        }
    }

    /** Returns the stored token, or "" if none is saved or it could not be decrypted. */
    public static String read(Context context) {
        SharedPreferences prefs = securePrefs(context);
        String ivB64 = prefs.getString(KEY_IV, null);
        String ciphertextB64 = prefs.getString(KEY_CIPHERTEXT, null);
        if (ivB64 == null || ciphertextB64 == null) return "";
        try {
            byte[] iv = Base64.decode(ivB64, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(Base64.decode(ciphertextB64, Base64.NO_WRAP));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // A corrupt value or a Keystore key invalidated by a device/OS event (e.g. the user
            // clearing device credentials) must not crash Leon -- treat it as "no token saved"
            // and let the user re-enter it in Settings, same as first run.
            Log.w(TAG, "failed to decrypt voice app token; treating as unset", e);
            return "";
        }
    }

    public static boolean hasStoredToken(Context context) {
        SharedPreferences prefs = securePrefs(context);
        return prefs.contains(KEY_IV) && prefs.contains(KEY_CIPHERTEXT);
    }

    private static SharedPreferences securePrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (existing != null) return existing;
        }
        KeyGenerator keyGenerator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return keyGenerator.generateKey();
    }
}
