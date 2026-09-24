package ai.leon.companion.voice;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Talks to the backend's {@code POST /leon/voice-turn} (apps/api/app/api/routes/leon_voice.py). */
final class LeonVoiceApiClient {
    interface Callback2 {
        void onSuccess(String userText, String replyText, byte[] replyAudio);

        /** {@code message} is safe to show to the user. */
        void onFailure(String message);
    }

    /** One prior turn, matching the backend's {role: "user"|"assistant", content: "..."} shape. */
    static final class HistoryTurn {
        final String role;
        final String content;

        HistoryTurn(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private static final MediaType AUDIO_WAV = MediaType.parse("audio/wav");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // The backend's own three provider calls (STT, LLM, TTS) run sequentially and each has
            // its own ~30s upstream timeout, so the client's read timeout has to comfortably exceed
            // the sum of all three rather than a single call's budget.
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    void submitAudio(String baseUrl, String appToken, byte[] wavBytes,
            List<HistoryTurn> history, Callback2 callback) {
        MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "speech.wav",
                        RequestBody.create(wavBytes, AUDIO_WAV));
        addHistory(body, history);
        send(baseUrl, appToken, body.build(), callback);
    }

    void submitText(String baseUrl, String appToken, String text,
            List<HistoryTurn> history, Callback2 callback) {
        MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("text", text);
        addHistory(body, history);
        send(baseUrl, appToken, body.build(), callback);
    }

    private void addHistory(MultipartBody.Builder body, List<HistoryTurn> history) {
        if (history == null || history.isEmpty()) return;
        JSONArray array = new JSONArray();
        for (HistoryTurn turn : history) {
            try {
                JSONObject entry = new JSONObject();
                entry.put("role", turn.role);
                entry.put("content", turn.content);
                array.put(entry);
            } catch (JSONException ignored) {
                // A single malformed turn is dropped rather than failing the whole request.
            }
        }
        body.addFormDataPart("history", array.toString());
    }

    private void send(String baseUrl, String appToken, RequestBody body, final Callback2 callback) {
        String url = (baseUrl == null ? "" : baseUrl.replaceAll("/+$", "")) + "/leon/voice-turn";
        Request request;
        try {
            request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + (appToken == null ? "" : appToken))
                    .post(body)
                    .build();
        } catch (IllegalArgumentException e) {
            callback.onFailure("Voice backend URL is not set up correctly.");
            return;
        }

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onFailure("Could not reach Leon's voice backend.");
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        callback.onFailure(friendlyError(r));
                        return;
                    }
                    String bodyText = r.body() != null ? r.body().string() : "";
                    JSONObject json = new JSONObject(bodyText);
                    String userText = json.optString("user_text", "");
                    String replyText = json.optString("reply_text", "");
                    String audioBase64 = json.optString("reply_audio_base64", "");
                    if (replyText.isEmpty() || audioBase64.isEmpty()) {
                        callback.onFailure("Leon's voice backend returned an incomplete reply.");
                        return;
                    }
                    byte[] audio = android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT);
                    callback.onSuccess(userText, replyText, audio);
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    callback.onFailure("Leon's voice backend returned something unexpected.");
                }
            }
        });
    }

    private static String friendlyError(Response response) {
        if (response.code() == 401) {
            return "Leon's voice backend token is not accepted.";
        }
        if (response.code() == 413) {
            return "That recording was too long.";
        }

        String requestId = response.header("X-Request-ID", "");
        String message = "";
        String stage = "";
        try {
            String bodyText = response.body() != null ? response.body().string() : "";
            if (!bodyText.isEmpty()) {
                JSONObject json = new JSONObject(bodyText);
                Object detail = json.opt("detail");
                if (detail instanceof JSONObject) {
                    JSONObject object = (JSONObject) detail;
                    message = object.optString("message", "");
                    stage = object.optString("stage", "");
                } else if (detail instanceof String) {
                    message = (String) detail;
                }
            }
        } catch (IOException | JSONException ignored) {
            // Fall through to the status-code based message below.
        }

        String suffix = requestId.isEmpty() ? "" : " Request " + requestId + ".";
        if (response.code() == 502 && !message.isEmpty()) {
            String where = stage.isEmpty() ? "" : " [" + stage + "]";
            return message + where + suffix;
        }
        if (response.code() == 503) {
            return "Leon's voice service is temporarily unavailable." + suffix;
        }
        return "Leon's voice backend had a problem (code " + response.code() + ")." + suffix;
    }
}
