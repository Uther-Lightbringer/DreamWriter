package org.zenithon.articlecollect.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zenithon.articlecollect.dto.DeepSeekConfigDTO;
import org.zenithon.articlecollect.dto.NovelGeneratorRequest;
import org.zenithon.articlecollect.entity.AiImageHistory;
import org.zenithon.articlecollect.entity.CreativeSession;
import org.zenithon.articlecollect.service.AiImageHistoryService;
import org.zenithon.articlecollect.service.CreativeSessionService;
import org.zenithon.articlecollect.service.EvoLinkImageService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 创作引导会话控制器
 */
@RestController
@RequestMapping("/api/creative-sessions")
public class CreativeSessionController {

    private static final Logger logger = LoggerFactory.getLogger(CreativeSessionController.class);

    private final CreativeSessionService sessionService;
    private final EvoLinkImageService evoLinkImageService;
    private final AiImageHistoryService aiImageHistoryService;

    @Autowired
    public CreativeSessionController(
            CreativeSessionService sessionService,
            EvoLinkImageService evoLinkImageService,
            AiImageHistoryService aiImageHistoryService) {
        this.sessionService = sessionService;
        this.evoLinkImageService = evoLinkImageService;
        this.aiImageHistoryService = aiImageHistoryService;
    }

    // ==================== 会话管理 ====================

    /**
     * 创建新会话
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody(required = false) Map<String, String> request) {
        String title = request != null ? request.get("title") : null;
        logger.info("创建新会话, title={}", title);

        try {
            CreativeSession session = sessionService.createSession(title);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sessionId", session.getSessionId());
            response.put("title", session.getTitle());
            response.put("createdAt", session.getCreateTime());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("创建会话失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 获取会话列表
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllSessions() {
        List<CreativeSession> sessions = sessionService.getAllSessions();

        Map<String, Object> response = new HashMap<>();
        response.put("sessions", sessions.stream().map(this::toSessionSummary).toList());

        return ResponseEntity.ok(response);
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        try {
            CreativeSession session = sessionService.getSession(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("sessionId", session.getSessionId());
            response.put("title", session.getTitle());
            response.put("status", session.getStatus());
            response.put("messages", session.getMessages());
            response.put("extractedParams", session.getExtractedParams());
            response.put("contextData", session.getContextData());  // 添加上下文数据
            response.put("createdAt", session.getCreateTime());
            response.put("updatedAt", session.getUpdateTime());

            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.status(404).body(errorResponse);
        }
    }

    /**
     * 更新会话
     */
    @PutMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> updateSession(
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            String title = (String) request.get("title");
            String statusStr = (String) request.get("status");
            CreativeSession.SessionStatus status = statusStr != null
                    ? CreativeSession.SessionStatus.valueOf(statusStr)
                    : null;

            CreativeSession session = sessionService.updateSession(sessionId, title, status);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("sessionId", session.getSessionId());
            response.put("title", session.getTitle());
            response.put("status", session.getStatus());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("更新会话失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        try {
            sessionService.deleteSession(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "会话已删除");

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("删除会话失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 压缩会话历史
     */
    @PostMapping("/{sessionId}/compress")
    public ResponseEntity<Map<String, Object>> compressSession(@PathVariable String sessionId) {
        logger.info("手动压缩会话: sessionId={}", sessionId);
        try {
            Map<String, Object> result = sessionService.compressSession(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("压缩会话失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 修复会话中损坏的消息
     */
    @PostMapping("/{sessionId}/repair")
    public ResponseEntity<Map<String, Object>> repairSession(@PathVariable String sessionId) {
        logger.info("修复会话消息: sessionId={}", sessionId);
        try {
            Map<String, Object> result = sessionService.repairSession(sessionId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("修复会话失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * 获取会话统计信息
     */
    @GetMapping("/{sessionId}/stats")
    public ResponseEntity<Map<String, Object>> getSessionStats(@PathVariable String sessionId) {
        try {
            Map<String, Object> stats = sessionService.getSessionStats(sessionId);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("获取会话统计失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    // ==================== 对话功能 ====================

    /**
     * 发送消息（SSE 流式响应）
     * 支持可选的 config 参数覆盖默认配置
     */
    @PostMapping(value = "/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String sessionId, @RequestBody Map<String, Object> request) {
        String content = (String) request.get("content");

        // 提取可选的运行时配置
        final DeepSeekConfigDTO runtimeConfig;
        if (request.containsKey("config")) {
            Object configObj = request.get("config");
            if (configObj instanceof Map) {
                DeepSeekConfigDTO dto = new DeepSeekConfigDTO();
                @SuppressWarnings("unchecked")
                Map<String, Object> configMap = (Map<String, Object>) configObj;
                if (configMap.containsKey("model")) {
                    dto.setModel((String) configMap.get("model"));
                }
                if (configMap.containsKey("thinkingEnabled")) {
                    dto.setThinkingEnabled((Boolean) configMap.get("thinkingEnabled"));
                }
                if (configMap.containsKey("reasoningEffort")) {
                    dto.setReasoningEffort((String) configMap.get("reasoningEffort"));
                }
                runtimeConfig = dto;
            } else {
                runtimeConfig = null;
            }
        } else {
            runtimeConfig = null;
        }

        logger.info("收到对话请求, sessionId={}, content={}, hasConfig={}", sessionId, content, runtimeConfig != null);

        // 创建 SSE 发射器，设置超时时间为 5 分钟
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        // 异步处理
        CompletableFuture.runAsync(() -> {
            try {
                sessionService.chat(sessionId, content, emitter, runtimeConfig);
            } catch (Exception e) {
                logger.error("对话处理失败: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"message\": \"" + escapeJson(e.getMessage()) + "\"}"));
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    logger.error("发送错误事件失败: {}", ex.getMessage());
                }
            }
        });

        emitter.onCompletion(() -> logger.info("SSE 连接正常关闭, sessionId={}", sessionId));
        emitter.onTimeout(() -> {
            logger.warn("SSE 连接超时, sessionId={}", sessionId);
            emitter.completeWithError(new RuntimeException("请求超时"));
        });
        emitter.onError((throwable) -> logger.error("SSE 连接错误: {}", throwable.getMessage()));

        return emitter;
    }

    /**
     * 生成参数并获取 NovelGeneratorRequest
     */
    @PostMapping("/{sessionId}/fill-params")
    public ResponseEntity<Map<String, Object>> fillParams(@PathVariable String sessionId) {
        logger.info("生成参数请求, sessionId={}", sessionId);

        try {
            NovelGeneratorRequest request = sessionService.fillParams(sessionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("params", toParamsMap(request));
            response.put("request", request);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("生成参数失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 转换为会话摘要
     */
    private Map<String, Object> toSessionSummary(CreativeSession session) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("sessionId", session.getSessionId());
        summary.put("title", session.getTitle());
        summary.put("status", session.getStatus());
        summary.put("createdAt", session.getCreateTime());
        summary.put("updatedAt", session.getUpdateTime());
        // 计算参数完成度
        summary.put("paramsComplete", calculateParamsComplete(session.getExtractedParams()));
        return summary;
    }

    /**
     * 计算参数完成度
     */
    @SuppressWarnings("unchecked")
    private int calculateParamsComplete(String extractedParams) {
        if (extractedParams == null || extractedParams.isEmpty()) {
            return 0;
        }
        try {
            // 简单计算非空参数的比例
            String[] fields = {"theme", "style", "protagonistName", "mainPlot", "endingType"};
            int completed = 0;
            for (String field : fields) {
                if (extractedParams.contains("\"" + field + "\":") &&
                    !extractedParams.contains("\"" + field + "\": null")) {
                    completed++;
                }
            }
            return (completed * 100) / fields.length;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 转换 NovelGeneratorRequest 为 Map
     */
    private Map<String, Object> toParamsMap(NovelGeneratorRequest request) {
        Map<String, Object> map = new HashMap<>();
        map.put("chapterCount", request.getChapterCount());
        map.put("keyword", request.getKeyword());
        map.put("genre", request.getGenre());
        map.put("protagonist", request.getProtagonist());
        map.put("languageStyle", request.getLanguageStyle());
        map.put("wordsPerChapter", request.getWordsPerChapter());
        map.put("pointOfView", request.getPointOfView());
        return map;
    }

    /**
     * 转义 JSON 字符串
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 公共记忆管理 ====================

    /**
     * 获取全局记忆列表
     */
    @GetMapping("/memories/global")
    public ResponseEntity<List<?>> getGlobalMemories() {
        return ResponseEntity.ok(sessionService.getGlobalMemories());
    }

    /**
     * 删除全局记忆
     */
    @DeleteMapping("/memories/global/{key}")
    public ResponseEntity<Map<String, Object>> deleteGlobalMemory(@PathVariable String key) {
        logger.info("删除全局记忆: {}", key);
        try {
            sessionService.deleteGlobalMemory(key);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "已删除全局记忆");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("删除全局记忆失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    // ==================== 参考图片生成 ====================

    /**
     * 生成参考图片
     * POST /api/creative-sessions/{sessionId}/generate-reference-image
     */
    @PostMapping("/{sessionId}/generate-reference-image")
    public ResponseEntity<Map<String, Object>> generateReferenceImage(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> request) {

        String prompt = request.get("prompt");
        String size = request.getOrDefault("size", "1:1");

        if (prompt == null || prompt.trim().isEmpty()) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "提示词不能为空");
            return ResponseEntity.badRequest().body(errorResponse);
        }

        try {
            logger.info("生成参考图片: sessionId={}, size={}", sessionId, size);

            // 调用 EvoLink 生成图片
            String taskId = evoLinkImageService.generateImage(prompt, size);

            // 轮询获取结果（最多等待60秒）
            String imageUrl = null;
            int maxAttempts = 30;
            for (int i = 0; i < maxAttempts; i++) {
                Thread.sleep(2000);
                EvoLinkImageService.TaskStatus status = evoLinkImageService.getTaskStatus(taskId);
                if ("completed".equals(status.getStatus())) {
                    imageUrl = status.getImageUrl();
                    break;
                } else if ("failed".equals(status.getStatus())) {
                    Map<String, Object> errorResponse = new HashMap<>();
                    errorResponse.put("success", false);
                    errorResponse.put("error", "图片生成失败: " + status.getError());
                    return ResponseEntity.badRequest().body(errorResponse);
                }
            }

            if (imageUrl == null) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", "图片生成超时，请重试");
                return ResponseEntity.badRequest().body(errorResponse);
            }

            // 保存到历史记录
            AiImageHistory history = aiImageHistoryService.saveHistory(prompt, imageUrl);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("imageUrl", imageUrl);
            response.put("imageId", history != null ? history.getId() : null);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("生成参考图片失败: {}", e.getMessage(), e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "生成失败：" + e.getMessage());
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
}
