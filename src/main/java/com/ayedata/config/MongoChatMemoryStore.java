package com.ayedata.config;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * MongoDB-backed ChatMemoryStore for LangChain4j.
 *
 * Persists per-session conversation history in the dedicated {@code pass_memory}
 * database, collection {@code agent_chat_memory}. Documents are keyed by
 * session-id and hold a JSON-serialisable list of chat messages.
 *
 * Supported message types: USER, AI (with or without tool calls), SYSTEM,
 * TOOL_EXECUTION_RESULT.
 */
@Component
public class MongoChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MongoChatMemoryStore.class);
    private static final String COLLECTION = "agent_chat_memory";

    private final MongoTemplate memoryMongoTemplate;

    public MongoChatMemoryStore(@Qualifier("memoryMongoTemplate") MongoTemplate memoryMongoTemplate) {
        this.memoryMongoTemplate = memoryMongoTemplate;
    }

    /**
     * Ensure a 7-day TTL index on agent_chat_memory so stale sessions are auto-cleaned.
     * Called from {@link com.ayedata.init.MemoryDatabaseInitializer}.
     */
    public void ensureIndexes() {
        try {
            var collection = memoryMongoTemplate.getCollection(COLLECTION);
            collection.createIndex(
                    new org.bson.Document("updatedAt", 1),
                    new com.mongodb.client.model.IndexOptions()
                            .name("agent_chat_memory_ttl")
                            .expireAfter(7L, TimeUnit.DAYS));
            log.info("✅ agent_chat_memory TTL index ensured (7 days)");
        } catch (com.mongodb.MongoCommandException ex) {
            if (!ex.getMessage().contains("already exists")) {
                log.warn("agent_chat_memory TTL index creation issue: {}", ex.getMessage());
            }
        } catch (Exception e) {
            log.warn("agent_chat_memory TTL index creation skipped: {}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // ChatMemoryStore contract
    // -----------------------------------------------------------------------

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            Query query = Query.query(Criteria.where("_id").is(memoryId.toString()));
            Document doc = memoryMongoTemplate.findOne(query, Document.class, COLLECTION);
            if (doc == null) {
                log.debug("No chat memory found for session {}", memoryId);
                return Collections.emptyList();
            }

            @SuppressWarnings("unchecked")
            List<Document> msgDocs = (List<Document>) doc.get("messages");
            if (msgDocs == null) return Collections.emptyList();

            return msgDocs.stream()
                    .map(this::deserializeMessage)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to load chat memory for session {}", memoryId, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            List<Document> msgDocs = messages.stream()
                    .map(this::serializeMessage)
                    .collect(Collectors.toList());

            Query query = Query.query(Criteria.where("_id").is(memoryId.toString()));
            Update update = Update.update("messages", msgDocs)
                    .set("updatedAt", new Date())
                    .set("messageCount", msgDocs.size());

            memoryMongoTemplate.upsert(query, update, COLLECTION);
            log.debug("Updated chat memory for session {} ({} messages)", memoryId, msgDocs.size());

        } catch (Exception e) {
            log.error("Failed to update chat memory for session {}", memoryId, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            memoryMongoTemplate.remove(
                    Query.query(Criteria.where("_id").is(memoryId.toString())), COLLECTION);
            log.debug("Deleted chat memory for session {}", memoryId);
        } catch (Exception e) {
            log.error("Failed to delete chat memory for session {}", memoryId, e);
        }
    }

    // -----------------------------------------------------------------------
    // Serialization helpers
    // -----------------------------------------------------------------------

    private Document serializeMessage(ChatMessage msg) {
        Document doc = new Document("type", msg.type().name());

        switch (msg.type()) {
            case SYSTEM -> doc.append("text", ((SystemMessage) msg).text());

            case USER -> {
                UserMessage um = (UserMessage) msg;
                // Collect all text parts; non-text content is ignored (images etc.)
                String text = um.contents().stream()
                        .filter(c -> c instanceof TextContent)
                        .map(c -> ((TextContent) c).text())
                        .collect(Collectors.joining("\n"));
                doc.append("text", text);
            }

            case AI -> {
                AiMessage ai = (AiMessage) msg;
                doc.append("text", ai.text() != null ? ai.text() : "");
                if (ai.hasToolExecutionRequests()) {
                    List<Document> toolReqs = ai.toolExecutionRequests().stream()
                            .map(tr -> new Document("id", tr.id())
                                    .append("name", tr.name())
                                    .append("arguments", tr.arguments()))
                            .collect(Collectors.toList());
                    doc.append("toolRequests", toolReqs);
                }
            }

            case TOOL_EXECUTION_RESULT -> {
                ToolExecutionResultMessage tr = (ToolExecutionResultMessage) msg;
                doc.append("toolExecutionId", tr.id())
                   .append("toolName", tr.toolName())
                   .append("text", tr.text() != null ? tr.text() : "");
            }

            case CUSTOM -> {
                // CUSTOM message type: store as generic type
                // CustomMessage doesn't expose text directly, so we just mark the type
                log.debug("Storing CUSTOM message type in chat memory");
            }
        }

        return doc;
    }

    @SuppressWarnings("unchecked")
    private ChatMessage deserializeMessage(Document doc) {
        String type = doc.getString("type");
        String text = doc.getString("text");

        try {
            return switch (type) {
                case "SYSTEM" -> SystemMessage.from(text);

                case "USER" -> UserMessage.from(text != null ? text : "");

                case "AI" -> {
                    List<Document> toolReqDocs = (List<Document>) doc.get("toolRequests");
                    if (toolReqDocs != null && !toolReqDocs.isEmpty()) {
                        List<ToolExecutionRequest> toolReqs = toolReqDocs.stream()
                                .map(d -> ToolExecutionRequest.builder()
                                        .id(d.getString("id"))
                                        .name(d.getString("name"))
                                        .arguments(d.getString("arguments"))
                                        .build())
                                .collect(Collectors.toList());
                        yield AiMessage.from(toolReqs);
                    }
                    yield AiMessage.from(text != null ? text : "");
                }

                case "TOOL_EXECUTION_RESULT" -> ToolExecutionResultMessage.from(
                        doc.getString("toolExecutionId"),
                        doc.getString("toolName"),
                        text != null ? text : "");

                case "CUSTOM" -> {
                    log.debug("Deserializing CUSTOM message type from chat memory");
                    // Treat as generic message - CUSTOM type deserialization not fully supported yet
                    yield null;
                }

                default -> {
                    log.warn("Unknown ChatMessage type '{}' in stored memory — skipping", type);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.warn("Failed to deserialize message of type '{}' — skipping", type, e);
            return null;
        }
    }
}
