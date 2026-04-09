package com.ayedata.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;

import java.util.List;
import java.util.function.Consumer;

/**
 * A pre-built {@link TokenStream} that emits a known message word-by-word
 * without making any LLM call. Used to bypass the formatting LLM when the
 * tool result is already human-readable (e.g. successful payment confirmations).
 */
class SyntheticTokenStream implements TokenStream {

    private final String message;
    private Consumer<String> partialHandler;
    private Consumer<ChatResponse> completeHandler;
    private Consumer<Throwable> errorHandler;

    SyntheticTokenStream(String message) {
        this.message = message;
    }

    @Override public TokenStream onPartialResponse(Consumer<String> h)        { this.partialHandler  = h; return this; }
    @Override public TokenStream onCompleteResponse(Consumer<ChatResponse> h) { this.completeHandler = h; return this; }
    @Override public TokenStream onError(Consumer<Throwable> h)               { this.errorHandler    = h; return this; }
    @Override public TokenStream onRetrieved(Consumer<List<Content>> h)       { return this; }
    @Override public TokenStream onToolExecuted(Consumer<ToolExecution> h)    { return this; }
    @Override public TokenStream ignoreErrors()                               { return this; }

    @Override
    public void start() {
        try {
            // Emit word-by-word to give a streaming feel
            String[] words = message.split("(?<=\\s)");
            for (String word : words) {
                if (partialHandler != null) {
                    partialHandler.accept(word);
                }
            }
            if (completeHandler != null) {
                ChatResponse response = ChatResponse.builder()
                        .aiMessage(AiMessage.from(message))
                        .tokenUsage(new TokenUsage(0, message.length() / 4, message.length() / 4))
                        .build();
                completeHandler.accept(response);
            }
        } catch (Exception e) {
            if (errorHandler != null) {
                errorHandler.accept(e);
            }
        }
    }
}
