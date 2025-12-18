package com.example.storyprinter.openai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal OpenAI Chat Completions client.
 *
 * Uses the Chat Completions endpoint under /v1/chat/completions.
 * Keeps the API tiny and Java-friendly for this project.
 */
public final class OpenAiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final String apiKey;

    public OpenAiClient(OkHttpClient http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /**
     * Calls Chat Completions and returns the assistant message content.
     */
    public String createChatCompletion(
            String model,
            double temperature,
            List<ChatMessage> messages
    ) throws IOException {
        JSONObject payload = new JSONObject();
        try {
            payload.put("model", model);
            payload.put("temperature", temperature);

            JSONArray msgs = new JSONArray();
            for (ChatMessage m : messages) {
                JSONObject obj = new JSONObject();
                obj.put("role", m.role);
                obj.put("content", m.content);
                msgs.put(obj);
            }
            payload.put("messages", msgs);
        } catch (JSONException e) {
            throw new IOException("Failed to build JSON payload", e);
        }

        Request req = new Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
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
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    throw new IOException("OpenAI response missing choices: " + body);
                }
                JSONObject first = choices.getJSONObject(0);
                JSONObject message = first.optJSONObject("message");
                if (message == null) {
                    throw new IOException("OpenAI response missing message: " + body);
                }
                String content = message.optString("content", "");
                return content.trim();
            } catch (JSONException e) {
                throw new IOException("Failed to parse OpenAI response\n" + body, e);
            }
        }
    }
}
