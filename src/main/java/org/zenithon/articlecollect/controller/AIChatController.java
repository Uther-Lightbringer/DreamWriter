package org.zenithon.articlecollect.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zenithon.articlecollect.dto.DeepSeekRuntimeConfig;
import org.zenithon.articlecollect.entity.ChatMessage;
import org.zenithon.articlecollect.entity.ChatSession;
import org.zenithon.articlecollect.service.AIChatService;
import org.zenithon.articlecollect.service.AIPromptService;
import org.zenithon.articlecollect.service.AIPromptService.ChatStreamResult;
import org.zenithon.articlecollect.service.DeepSeekConfigService;
import org.zenithon.articlecollect.entity.DeepSeekFeatureConfig.FeatureCode;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * AI 对话控制器
 */
@RestController
@RequestMapping("/api/ai")
public class AIChatController {

    private static final Logger logger = LoggerFactory.getLogger(AIChatController.class);

    private final AIPromptService aiPromptService;
    private final DeepSeekConfigService deepSeekConfigService;
    private final AIChatService aiChatService;

    public AIChatController(AIPromptService aiPromptService,
                            DeepSeekConfigService deepSeekConfigService,
                            AIChatService aiChatService) {
        this.aiPromptService = aiPromptService;
        this.deepSeekConfigService = deepSeekConfigService;
        this.aiChatService = aiChatService;
    }

    // ===== 会话 API =====

    @GetMapping("/chat/sessions")
    public ResponseEntity<List<ChatSession>> getSessions() {
        return ResponseEntity.ok(aiChatService.getAllSessions());
    }

    @PostMapping("/chat/sessions")
    public ResponseEntity<ChatSession> createSession(@RequestBody(required = false) Map<String, String> body) {
        String title = body != null ? body.get("title") : null;
        ChatSession session = aiChatService.createSession(title);
        return ResponseEntity.ok(session);
    }

    @PutMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<ChatSession> updateSession(@PathVariable Long sessionId,
                                                      @RequestBody Map<String, String> body) {
        ChatSession session = aiChatService.updateSessionTitle(sessionId, body.get("title"));
        if (session == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(session);
    }

    @DeleteMapping("/chat/sessions/{sessionId}")
    public ResponseEntity<Map<String, String>> deleteSession(@PathVariable Long sessionId) {
        aiChatService.deleteSession(sessionId);
        Map<String, String> resp = new HashMap<>();
        resp.put("message", "会话已删除");
        return ResponseEntity.ok(resp);
    }

    // ===== 历史记录 API =====

    @GetMapping("/chat/sessions/{sessionId}/history")
    public ResponseEntity<List<ChatMessage>> getHistory(@PathVariable Long sessionId) {
        return ResponseEntity.ok(aiChatService.getHistory(sessionId));
    }

    @DeleteMapping("/chat/sessions/{sessionId}/history")
    public ResponseEntity<Map<String, String>> clearHistory(@PathVariable Long sessionId) {
        aiChatService.clearHistory(sessionId);
        Map<String, String> resp = new HashMap<>();
        resp.put("message", "历史记录已清空");
        return ResponseEntity.ok(resp);
    }

    // ===== 聊天 API =====

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamPost(@RequestBody(required = false) Map<String, Object> request) {
        String prompt = request != null ? (String) request.get("prompt") : null;

        Long sessionId = null;
        if (request != null && request.get("sessionId") != null) {
            sessionId = ((Number) request.get("sessionId")).longValue();
        }

        DeepSeekRuntimeConfig config = null;
        if (request != null && request.containsKey("config")) {
            config = extractConfig(request.get("config"));
        }

        logger.info("收到用户请求: sessionId={}, prompt={}, hasConfig={}", sessionId, prompt, config != null);
        return handleChatStream(prompt, sessionId, config);
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, String>> chatPost(@RequestBody(required = false) Map<String, Object> request) {
        String prompt = request != null ? (String) request.get("prompt") : null;
        if (prompt == null || prompt.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "提示词不能为空"));
        }

        DeepSeekRuntimeConfig config = null;
        if (request != null && request.containsKey("config")) {
            config = extractConfig(request.get("config"));
        }

        try {
            String aiResponse = aiPromptService.callDeepSeekAPIWithConfig(prompt, config);
            return ResponseEntity.ok(Map.of("response", aiResponse));
        } catch (Exception e) {
            logger.error("AI 对话失败：" + e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "AI 对话失败：" + e.getMessage()));
        }
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStreamGet(@RequestParam("prompt") String prompt,
                                     @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return handleChatStream(prompt, sessionId, null);
    }

    // ===== 核心处理逻辑 =====

    private SseEmitter handleChatStream(String prompt, Long sessionId, DeepSeekRuntimeConfig config) {
        if (prompt == null || prompt.trim().isEmpty()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"error\": \"提示词不能为空\"}"));
            } catch (IOException e) {
                logger.error("发送错误消息失败", e);
            }
            return emitter;
        }

        // 如果没有 sessionId，自动创建新会话
        if (sessionId == null) {
            String title = prompt.length() > 30 ? prompt.substring(0, 30) + "..." : prompt;
            ChatSession session = aiChatService.createSession(title);
            sessionId = session.getId();
        }

        if (config == null) {
            config = deepSeekConfigService.getDefaultRuntimeConfig(FeatureCode.AI_CHAT);
        }

        final Long finalSessionId = sessionId;
        final DeepSeekRuntimeConfig finalConfig = config;

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        // 发送 sessionId 给前端（如果是新创建的会话）
        try {
            emitter.send(SseEmitter.event().name("session")
                    .data("{\"sessionId\":" + finalSessionId + "}"));
        } catch (IOException e) {
            logger.error("发送 sessionId 失败", e);
        }

        CompletableFuture.runAsync(() -> {
            try {
                // 保存用户消息
                aiChatService.saveMessage(finalSessionId, "user", prompt);
                aiChatService.touchSession(finalSessionId);

                // 构建消息列表（从历史记录加载上下文）
                List<Map<String, Object>> messages = buildMessagesFromHistory(finalSessionId);

                // 调用AI流式对话
                ChatStreamResult result = aiPromptService.chatStreamWithMessages(messages, emitter, finalConfig);

                // 保存AI回复
                String aiContent = result.getContent();
                if (aiContent != null && !aiContent.isEmpty()) {
                    aiChatService.saveMessage(finalSessionId, "assistant", aiContent);
                }
                aiChatService.touchSession(finalSessionId);

                // 更新会话标题（首条消息时）
                if (aiChatService.getHistory(finalSessionId).size() <= 2) {
                    String userMsg = messages.stream()
                            .filter(m -> "user".equals(m.get("role")))
                            .map(m -> (String) m.get("content"))
                            .findFirst()
                            .orElse("新对话");
                    String title = userMsg.length() > 30 ? userMsg.substring(0, 30) + "..." : userMsg;
                    aiChatService.updateSessionTitle(finalSessionId, title);
                }

                // 发送 usage 信息
                Map<String, Object> usage = new HashMap<>();
                usage.put("promptTokens", result.getPromptTokens());
                usage.put("completionTokens", result.getCompletionTokens());
                usage.put("totalTokens", result.getTotalTokens());
                try {
                    String usageJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(usage);
                    emitter.send(SseEmitter.event().name("usage").data(usageJson));
                } catch (Exception e) {
                    logger.warn("发送 usage 事件失败: {}", e.getMessage());
                }

                emitter.send(SseEmitter.event().name("done").data(""));
                emitter.complete();

            } catch (Exception e) {
                logger.error("AI 对话失败：" + e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.completeWithError(e);
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
            }
        });

        emitter.onCompletion(() -> logger.info("SSE 连接正常关闭"));
        emitter.onTimeout(() -> {
            logger.warn("SSE 连接超时");
            emitter.completeWithError(new RuntimeException("请求超时"));
        });
        emitter.onError((throwable) -> logger.error("SSE 连接错误：" + throwable.getMessage(), throwable));

        return emitter;
    }

    private List<Map<String, Object>> buildMessagesFromHistory(Long sessionId) {
        List<ChatMessage> history = aiChatService.getHistory(sessionId);
        List<Map<String, Object>> messages = new ArrayList<>();

        for (ChatMessage msg : history) {
            Map<String, Object> message = new HashMap<>();
            message.put("role", msg.getRole());
            message.put("content", msg.getContent());
            messages.add(message);
        }

        return messages;
    }

    // ===== 辅助方法 =====

    @SuppressWarnings("unchecked")
    private DeepSeekRuntimeConfig extractConfig(Object configObj) {
        if (configObj instanceof Map) {
            Map<String, Object> configMap = (Map<String, Object>) configObj;
            DeepSeekRuntimeConfig config = new DeepSeekRuntimeConfig();
            if (configMap.containsKey("model")) config.setModel((String) configMap.get("model"));
            if (configMap.containsKey("thinkingEnabled")) config.setThinkingEnabled((Boolean) configMap.get("thinkingEnabled"));
            if (configMap.containsKey("reasoningEffort")) config.setReasoningEffort((String) configMap.get("reasoningEffort"));
            return config;
        }
        return null;
    }

    private String escapeJson(String text) {
        if (text == null) return "\"\"";
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
