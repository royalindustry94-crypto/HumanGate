package ai.leon.companion.overlay;

import android.content.Context;
import android.content.SharedPreferences;

import ai.leon.companion.state.LeonState;

/**
 * Everything about Leon that must outlive the process: where he was, whether he was minimised, and
 * which state he was in. Read on service creation so a restart after a reboot, a low-memory kill or
 * an unlock puts him back exactly where the user left him.
 */
public final class LeonPrefs {
    private static final String FILE = "leon_overlay";
    private static final String KEY_FRACTION_X = "position_fraction_x";
    private static final String KEY_FRACTION_Y = "position_fraction_y";
    private static final String KEY_HAS_POSITION = "has_position";
    private static final String KEY_MINIMISED = "minimised";
    private static final String KEY_STATE = "state";
    private static final String KEY_RESTORE_STATE = "restore_state";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HIDDEN_UNTIL = "hidden_until";
    private static final String KEY_RIG_DEBUG = "rig_debug";
    private static final String KEY_VOICE_BASE_URL = "voice_base_url";
    private static final String KEY_VOICE_APP_TOKEN = "voice_app_token";

    /** Default resting place: right-hand side, a little above the middle. */
    private static final float DEFAULT_FRACTION_X = 1f;
    private static final float DEFAULT_FRACTION_Y = 0.42f;

    private final SharedPreferences prefs;

    public LeonPrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean hasSavedPosition() {
        return prefs.getBoolean(KEY_HAS_POSITION, false);
    }

    public float positionFractionX() {
        return prefs.getFloat(KEY_FRACTION_X, DEFAULT_FRACTION_X);
    }

    public float positionFractionY() {
        return prefs.getFloat(KEY_FRACTION_Y, DEFAULT_FRACTION_Y);
    }

    public void savePosition(float fractionX, float fractionY) {
        prefs.edit()
                .putFloat(KEY_FRACTION_X, fractionX)
                .putFloat(KEY_FRACTION_Y, fractionY)
                .putBoolean(KEY_HAS_POSITION, true)
                .apply();
    }

    public boolean isMinimised() {
        return prefs.getBoolean(KEY_MINIMISED, false);
    }

    public void setMinimised(boolean minimised) {
        prefs.edit().putBoolean(KEY_MINIMISED, minimised).apply();
    }

    /** The state to return to on restart. Transient states are never persisted. */
    public LeonState state() {
        return LeonState.fromName(prefs.getString(KEY_STATE, LeonState.IDLE.name()), LeonState.IDLE);
    }

    public LeonState restoreState() {
        return LeonState.fromName(prefs.getString(KEY_RESTORE_STATE, LeonState.IDLE.name()),
                LeonState.IDLE);
    }

    public void saveState(LeonState state, LeonState restoreState) {
        if (state == null || state.isTransient()) return;
        prefs.edit()
                .putString(KEY_STATE, state.name())
                .putString(KEY_RESTORE_STATE,
                        restoreState == null ? LeonState.IDLE.name() : restoreState.name())
                .putBoolean(KEY_MINIMISED, state == LeonState.MINIMISED)
                .apply();
    }

    /** Whether the user wants Leon running at all. Controls boot and unlock restoration. */
    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Wall-clock time until which Leon stays hidden, or 0 when he is not hidden. */
    public long hiddenUntilMillis() {
        return prefs.getLong(KEY_HIDDEN_UNTIL, 0L);
    }

    public void setHiddenUntilMillis(long millis) {
        prefs.edit().putLong(KEY_HIDDEN_UNTIL, millis).apply();
    }

    public boolean isRigDebugEnabled() {
        return prefs.getBoolean(KEY_RIG_DEBUG, false);
    }

    public void setRigDebugEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RIG_DEBUG, enabled).apply();
    }

    /** Base URL of the deployed voice backend (apps/api), e.g. https://your-app.vercel.app. */
    public String leonVoiceBaseUrl() {
        return prefs.getString(KEY_VOICE_BASE_URL, "");
    }

    public void setLeonVoiceBaseUrl(String url) {
        prefs.edit().putString(KEY_VOICE_BASE_URL, url == null ? "" : url.trim()).apply();
    }

    /** The shared app token the backend's LEON_VOICE_APP_TOKEN checks -- see the Settings screen. */
    public String leonVoiceAppToken() {
        return prefs.getString(KEY_VOICE_APP_TOKEN, "");
    }

    public void setLeonVoiceAppToken(String token) {
        prefs.edit().putString(KEY_VOICE_APP_TOKEN, token == null ? "" : token.trim()).apply();
    }
}
