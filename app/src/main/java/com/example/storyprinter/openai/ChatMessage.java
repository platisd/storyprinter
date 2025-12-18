package com.example.storyprinter.openai;

/** Simple chat message model for OpenAI Chat Completions. */
public final class ChatMessage {
    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}

