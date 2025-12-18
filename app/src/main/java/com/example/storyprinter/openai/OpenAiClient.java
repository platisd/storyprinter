package com.example.storyprinter.openai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal OpenAI Responses API client.
 *
 * Uses the Responses endpoint under /v1/responses.
 */
public final class OpenAiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String apiKey;

    public OpenAiClient(OkHttpClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /** Result of a Responses API call: output text plus response id for chaining. */
    public static final class ResponseResult {
        public final String responseId;
        public final String outputText;

        public ResponseResult(String responseId, String outputText) {
            this.responseId = responseId;
            this.outputText = outputText;
        }
    }

    /**
     * Calls the Responses API and returns both `response_id` and assistant output text.
     *
     * For multi-turn conversations, pass the previous response id (or null for the first turn).
     */
    public ResponseResult createResponse(
            String model,
            double temperature,
            String input,
            String previousResponseId
    ) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("model", model);
            payload.put("temperature", temperature);
            payload.put("input", input);
            if (previousResponseId != null && !previousResponseId.trim().isEmpty()) {
                payload.put("previous_response_id", previousResponseId);
            }
        } catch (JSONException e) {
            throw new IOException("Failed to build JSON payload", e);
        }

        Request req = new Request.Builder()
                .url("https://api.openai.com/v1/responses")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("OpenAI error: HTTP " + resp.code() + "\n" + body);
            }

            try {
                JSONObject json = new JSONObject(body);

                String responseId = json.optString("id", "");

                // Preferred: response.output_text() equivalent: collect output[].content[].type == output_text.
                JSONArray output = json.optJSONArray("output");
                String text = extractOutputText(output);
                if (text.isEmpty()) {
                    // Fallback: some SDKs expose `output_text` at top-level (not guaranteed)
                    text = json.optString("output_text", "").trim();
                }

                if (text.isEmpty()) {
                    throw new IOException("OpenAI response contained no output_text: " + body);
                }

                return new ResponseResult(responseId, text);
            } catch (JSONException e) {
                throw new IOException("Failed to parse OpenAI response\n" + body, e);
            }
        }
    }

    private static String extractOutputText(JSONArray output) {
        if (output == null || output.length() == 0) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            if (!"message".equals(item.optString("type"))) continue;

            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;

            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if (!"output_text".equals(part.optString("type"))) continue;

                String t = part.optString("text", "");
                if (t.isEmpty()) continue;

                if (sb.length() > 0) sb.append("\n");
                sb.append(t);
            }
        }

        return sb.toString().trim();
    }

    /**
     * Backwards-compatible shim used earlier in this project.
     *
     * NOTE: Chat-message style input should now be handled by the caller (either by
     * `previous_response_id` chaining or by concatenating a transcript into `input`).
     */
    @Deprecated
    public String createChatCompletion(
            String model,
            double temperature,
            java.util.List<ChatMessage> messages
    ) throws IOException {
        StringBuilder transcript = new StringBuilder();
        for (ChatMessage m : messages) {
            transcript.append(m.role).append(": ").append(m.content).append("\n");
        }
        return createResponse(model, temperature, transcript.toString(), null).outputText;
    }
}
