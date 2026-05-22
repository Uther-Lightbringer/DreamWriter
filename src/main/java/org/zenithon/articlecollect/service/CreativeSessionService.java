package org.zenithon.articlecollect.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zenithon.articlecollect.config.DeepSeekConfig;
import org.zenithon.articlecollect.dto.DeepSeekConfigDTO;
import org.zenithon.articlecollect.dto.DeepSeekRuntimeConfig;
import org.zenithon.articlecollect.dto.NovelGeneratorRequest;
import org.zenithon.articlecollect.entity.CreativeMemory;
import org.zenithon.articlecollect.entity.CreativeSession;
import org.zenithon.articlecollect.entity.DeepSeekFeatureConfig.FeatureCode;
import org.zenithon.articlecollect.repository.CreativeMemoryRepository;
import org.zenithon.articlecollect.repository.CreativeSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.zenithon.articlecollect.dto.CharacterCard;
import org.zenithon.articlecollect.dto.CharacterCardAppearance;
import org.zenithon.articlecollect.dto.CharacterCardRelationship;
import org.zenithon.articlecollect.entity.Chapter;
import org.zenithon.articlecollect.entity.Novel;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 创作引导会话服务
 *
 * 遵循 DeepSeek API 规范（docs/deepseek-api-reference.md）：
 * 1. 每次请求携带完整 messages 历史
 * 2. 使用 strict 模式定义工具
 * 3. 处理 reasoning_content 作为备选响应
 * 4. 使用 SSE 流式输出
 */
@Service
public class CreativeSessionService {

    private static final Logger logger = LoggerFactory.getLogger(CreativeSessionService.class);

    // 对话轮数阈值，超过此值触发压缩
    private static final int SUMMARY_THRESHOLD = 15;
    // 保留最近对话轮数（不压缩）
    private static final int KEEP_RECENT_TURNS = 5;
    // 工具调用最大递归深度，防止无限递归
    private static final int MAX_TOOL_RECURSION_DEPTH = 5;
    // 清理AI思考标签的正则
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    // 工具名称中文映射
    private static final Map<String, String> TOOL_NAME_MAP = Map.ofEntries(
        Map.entry("create_novel", "创建小说"),
        Map.entry("add_chapter", "添加章节"),
        Map.entry("update_chapter", "修改章节"),
        Map.entry("update_novel", "修改小说"),
        Map.entry("list_character_cards", "查询角色卡"),
        Map.entry("create_character_card", "创建角色卡"),
        Map.entry("update_character_card", "更新角色卡"),
        Map.entry("generate_character_image", "生成角色图片"),
        Map.entry("generate_chapter_images", "生成章节配图"),
        Map.entry("generate_reference_image", "生成参考图片"),
        Map.entry("get_chapter_summaries", "获取章节概括"),
        Map.entry("fill_params", "更新参数"),
        Map.entry("add_memory", "保存偏好"),
        Map.entry("preview_params", "预览参数"),
        Map.entry("remove_memory", "删除记忆"),
        Map.entry("show_examples", "展示示例"),
        Map.entry("generate_outline", "生成大纲"),
        Map.entry("update_outline", "更新大纲"),
        Map.entry("get_material", "获取素材"),
        Map.entry("auto_generate_all", "一键生成完整方案"),
        Map.entry("baidu_search", "百度搜索")
    );

    // 系统提示词（固定部分）
    private static final String SYSTEM_PROMPT_BASE = buildSystemPromptBase();

    // 默认写作质量提示词
    private static final String DEFAULT_WRITING_QUALITY_PROMPT = """
            ## 写作质量要求

            你必须用感官语言和动作细节来呈现故事，而非抽象概括。遵循以下原则：

            1. **五感先行**：每个场景至少调动2种感官。写"冷"不如写"指尖碰到铁栏杆，像被针扎了一下"；写"香"不如写"空气里飘着桂花酿过头的甜腻味"
            2. **动作替心理**：永远不写"她很难过"，而是写她做了什么——"她把筷子放下，盯着碗里那片浮着的肥肉看了很久，然后推开椅子站起来"
            3. **细节替形容词**：删掉"美丽的""精致的""温暖地"，换成具体的视觉画面——"裙摆底下露出一截绷得发白的棉袜"
            4. **对话带身体**：人物说话时必须伴随动作——"她把窗关上，背靠着窗台，'你走吧。'"
            5. **绝对禁止"不是……而是……"口癖**：无论叙述还是对话，不允许出现"不是A而是B""并非A而是B"等对比转折句式，直接写事实

            **核心标准**：读完你的文字，读者脑中应该浮现出画面，而不是得到一段总结。""";

    // 自定义写作质量提示词（可通过配置覆盖）
    private final String writingQualityPrompt;

    private final CreativeSessionRepository sessionRepository;
    private final CreativeMemoryRepository memoryRepository;
    private final DeepSeekConfig deepSeekConfig;
    private final DeepSeekConfigService deepSeekConfigService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    private NovelService novelService;

    @Autowired
    private CharacterCardService characterCardService;

    @Autowired
    private EvoLinkImageService evoLinkImageService;

    @Autowired
    private ChapterImageService chapterImageService;

    @Autowired
    private AiImageHistoryService aiImageHistoryService;

    @Autowired
    private MaterialService materialService;

    @Autowired
    private BaiduSearchService baiduSearchService;

    @Autowired
    private ChapterGenerationService chapterGenerationService;

    public CreativeSessionService(
            CreativeSessionRepository sessionRepository,
            CreativeMemoryRepository memoryRepository,
            DeepSeekConfig deepSeekConfig,
            DeepSeekConfigService deepSeekConfigService,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${creative.writing-quality-prompt:}") String writingQualityPrompt) {
        this.sessionRepository = sessionRepository;
        this.memoryRepository = memoryRepository;
        this.deepSeekConfig = deepSeekConfig;
        this.deepSeekConfigService = deepSeekConfigService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.writingQualityPrompt = writingQualityPrompt;
    }

    // ==================== 会话上下文管理 ====================

    /**
     * 会话上下文数据结构
     */
    public static class SessionContext {
        private Long currentNovelId;
        private Long currentChapterId;
        private List<Long> createdCharacterIds = new ArrayList<>();
        private List<Map<String, Object>> pendingCharacterCards = new ArrayList<>();
        private Map<String, Object> pendingOutline = new LinkedHashMap<>();
        private boolean schemeGenerated = false;

        public Long getCurrentNovelId() { return currentNovelId; }
        public void setCurrentNovelId(Long novelId) { this.currentNovelId = novelId; }

        public Long getCurrentChapterId() { return currentChapterId; }
        public void setCurrentChapterId(Long chapterId) { this.currentChapterId = chapterId; }

        public List<Long> getCreatedCharacterIds() { return createdCharacterIds; }
        public void addCharacterId(Long id) { this.createdCharacterIds.add(id); }

        public List<Map<String, Object>> getPendingCharacterCards() { return pendingCharacterCards; }
        public void setPendingCharacterCards(List<Map<String, Object>> cards) { this.pendingCharacterCards = cards; }

        public Map<String, Object> getPendingOutline() { return pendingOutline; }
        public void setPendingOutline(Map<String, Object> outline) { this.pendingOutline = outline; }

        public boolean isSchemeGenerated() { return schemeGenerated; }
        public void setSchemeGenerated(boolean generated) { this.schemeGenerated = generated; }
    }

    /**
     * 获取会话上下文
     */
    private SessionContext getContext(CreativeSession session) {
        if (session.getContextData() == null || session.getContextData().isEmpty()) {
            return new SessionContext();
        }
        try {
            return objectMapper.readValue(session.getContextData(), SessionContext.class);
        } catch (Exception e) {
            logger.error("解析上下文失败: {}", e.getMessage());
            return new SessionContext();
        }
    }

    /**
     * 更新会话上下文
     */
    private void updateContext(CreativeSession session, SessionContext context) {
        try {
            session.setContextData(objectMapper.writeValueAsString(context));
            sessionRepository.save(session);
        } catch (Exception e) {
            logger.error("保存上下文失败: {}", e.getMessage());
        }
    }

    // ==================== 会话管理 ====================

    /**
     * 创建新会话
     */
    @Transactional
    public CreativeSession createSession(String title) {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        CreativeSession session = new CreativeSession(sessionId);
        if (title != null && !title.trim().isEmpty()) {
            session.setTitle(title);
        } else {
            session.setTitle("新创作会话 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        }

        // 初始化消息历史（包含系统提示词 + 用户记忆）
        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = buildSystemPromptWithMemories(sessionId);
        messages.add(Map.of("role", "system", "content", systemPrompt));
        session.setMessages(toJson(messages));

        // 初始化空的参数
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("theme", null);
        params.put("style", null);
        params.put("protagonistName", null);
        params.put("protagonistGender", null);
        params.put("protagonistIdentity", null);
        params.put("mainPlot", null);
        params.put("conflictType", null);
        params.put("endingType", null);
        params.put("chapterCount", 8);
        params.put("wordsPerChapter", 3000);
        params.put("pointOfView", null);
        params.put("otherCharacters", new ArrayList<>());
        params.put("languageStyle", null);
        session.setExtractedParams(toJson(params));

        return sessionRepository.save(session);
    }

    /**
     * 获取所有会话列表
     */
    public List<CreativeSession> getAllSessions() {
        return sessionRepository.findAllByOrderByUpdateTimeDesc();
    }

    /**
     * 获取会话详情
     */
    public CreativeSession getSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));
    }

    /**
     * 更新会话
     */
    @Transactional
    public CreativeSession updateSession(String sessionId, String title, CreativeSession.SessionStatus status) {
        CreativeSession session = getSession(sessionId);

        // 如果会话已绑定小说，拒绝修改标题
        if (title != null && !title.trim().isEmpty()) {
            SessionContext context = getContext(session);
            if (context.getCurrentNovelId() != null) {
                logger.warn("会话已绑定小说，拒绝修改标题: sessionId={}", sessionId);
                // 忽略标题修改，继续处理其他字段
            } else {
                session.setTitle(title);
            }
        }

        if (status != null) {
            session.setStatus(status);
        }
        return sessionRepository.save(session);
    }

    /**
     * 删除会话
     */
    @Transactional
    public void deleteSession(String sessionId) {
        sessionRepository.deleteBySessionId(sessionId);
    }

    /**
     * 手动压缩会话历史
     * 用户主动触发或异常时自动调用
     *
     * @param sessionId 会话ID
     * @return 压缩结果信息
     */
    @Transactional
    public Map<String, Object> compressSession(String sessionId) {
        CreativeSession session = getSession(sessionId);
        List<Map<String, Object>> messages = parseMessages(session.getMessages());

        int messagesBefore = messages.size();
        int userTurnsBefore = countUserTurns(messages);

        // 检查是否有足够的消息需要压缩
        if (messages.size() <= (KEEP_RECENT_TURNS * 2 + 2)) {
            return Map.of(
                "success", false,
                "message", "对话较短，无需压缩",
                "messageCount", messages.size(),
                "userTurns", userTurnsBefore
            );
        }

        // 保留第一条（系统提示词）
        Map<String, Object> systemMessage = messages.get(0);

        // 计算需要保留的最近消息数量
        int keepMessageCount = KEEP_RECENT_TURNS * 2;

        // 获取最近的消息（不压缩），确保 tool_calls/tool 消息配对完整
        List<Map<String, Object>> recentMessages = extractRecentMessages(messages, keepMessageCount);

        // 提取即将被丢弃的旧消息（systemMessage和recentMessages之间的部分）
        int recentStartIndex = messages.size() - recentMessages.size();
        List<Map<String, Object>> oldMessages = new ArrayList<>(messages.subList(1, recentStartIndex));

        // 调用AI生成讨论历程摘要（降级：失败时返回null，不影响压缩）
        String discussionSummary = generateDiscussionSummary(oldMessages);
        if (discussionSummary != null) {
            logger.info("讨论历程摘要生成成功, 长度={}", discussionSummary.length());
        } else {
            logger.info("讨论历程摘要生成失败或跳过，仅使用结构化状态摘要");
        }

        // 构建状态摘要（含AI讨论摘要）
        Map<String, Object> stateSummary = buildStateSummaryMessage(session, messages, recentMessages, discussionSummary);

        // 替换消息历史
        messages.clear();
        messages.add(systemMessage);
        messages.add(stateSummary);
        messages.addAll(recentMessages);

        // 保存更新后的会话
        session.setMessages(toJson(messages));
        sessionRepository.save(session);

        int messagesAfter = messages.size();
        int userTurnsAfter = countUserTurns(messages);
        int turnsCompressed = userTurnsBefore - userTurnsAfter;

        logger.info("手动压缩完成: sessionId={}, 压缩前={}, 压缩后={}, 压缩轮数={}, AI摘要={}",
            sessionId, messagesBefore, messagesAfter, turnsCompressed, discussionSummary != null ? "已生成" : "未生成");

        return Map.of(
            "success", true,
            "message", "压缩完成",
            "messagesBefore", messagesBefore,
            "messagesAfter", messagesAfter,
            "userTurnsBefore", userTurnsBefore,
            "userTurnsAfter", userTurnsAfter,
            "turnsCompressed", turnsCompressed
        );
    }

    /**
     * 修复会话中损坏的消息（孤立 tool 消息、不完整的 tool_calls 等）
     */
    @Transactional
    public Map<String, Object> repairSession(String sessionId) {
        CreativeSession session = getSession(sessionId);
        List<Map<String, Object>> messages = parseMessages(session.getMessages());

        int messagesBefore = messages.size();
        logger.info("修复会话: sessionId={}, 修复前消息数={}", sessionId, messagesBefore);

        // 记录修复前的消息角色分布
        Map<String, Integer> roleCountBefore = new LinkedHashMap<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            roleCountBefore.merge(role, 1, Integer::sum);
        }

        // 执行修复
        repairMessages(messages);

        int messagesAfter = messages.size();
        Map<String, Integer> roleCountAfter = new LinkedHashMap<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            roleCountAfter.merge(role, 1, Integer::sum);
        }

        // 保存修复后的消息
        session.setMessages(toJson(messages));
        sessionRepository.save(session);

        logger.info("修复会话完成: sessionId={}, 修复前={}, 修复后={}", sessionId, messagesBefore, messagesAfter);

        return Map.of(
            "success", true,
            "sessionId", sessionId,
            "messagesBefore", messagesBefore,
            "messagesAfter", messagesAfter,
            "messagesRemoved", messagesBefore - messagesAfter,
            "roleCountBefore", roleCountBefore,
            "roleCountAfter", roleCountAfter
        );
    }

    /**
     * 获取会话统计信息
     */
    public Map<String, Object> getSessionStats(String sessionId) {
        CreativeSession session = getSession(sessionId);
        List<Map<String, Object>> messages = parseMessages(session.getMessages());

        int messageCount = messages.size();
        int userTurns = countUserTurns(messages);

        // 估算 token 数（粗略：每 4 个字符约 1 token）
        int estimatedTokens = 0;
        for (Map<String, Object> msg : messages) {
            String content = (String) msg.get("content");
            if (content != null) {
                estimatedTokens += content.length() / 4;
            }
        }

        // 计算压缩阈值百分比
        int thresholdPercentage = (userTurns * 100) / SUMMARY_THRESHOLD;

        return Map.of(
            "messageCount", messageCount,
            "userTurns", userTurns,
            "estimatedTokens", estimatedTokens,
            "thresholdPercentage", Math.min(thresholdPercentage, 100),
            "needsCompression", userTurns > SUMMARY_THRESHOLD
        );
    }

    // ==================== 对话功能 ====================

    /**
     * 发送消息并获取流式响应（使用默认配置）
     */
    @Transactional
    public void chat(String sessionId, String content, SseEmitter emitter) {
        chat(sessionId, content, emitter, null);
    }

    /**
     * 发送消息并获取流式响应（支持运行时配置覆盖）
     */
    @Transactional
    public void chat(String sessionId, String content, SseEmitter emitter, DeepSeekConfigDTO runtimeConfig) {
        // 检查 API Key
        if (deepSeekConfig.getApiKey() == null || deepSeekConfig.getApiKey().trim().isEmpty()) {
            sendError(emitter, "DeepSeek API Key 未配置");
            return;
        }

        logger.info("[创作会话] 开始处理聊天请求: sessionId={}, contentLength={}, hasRuntimeConfig={}",
            sessionId, content != null ? content.length() : 0, runtimeConfig != null);

        CreativeSession session = getSession(sessionId);

        try {
            // 解析现有消息历史
            List<Map<String, Object>> messages = parseMessages(session.getMessages());

            // 刷新系统消息中的记忆部分（每次请求都更新）
            refreshMemoriesInSystemMessage(messages, sessionId);

            // 添加用户消息
            messages.add(Map.of("role", "user", "content", content));

            // 如果是第一条用户消息，使用前7个字作为临时标题
            if (countUserTurns(messages) == 1 && content != null && !content.isEmpty()) {
                String tempTitle = content.length() > 7
                    ? content.substring(0, 7) + "..."
                    : content;
                session.setTitle(tempTitle);
                logger.info("设置临时会话标题: {}", tempTitle);
            }

            // 移除自动压缩 - 改为由异常处理或用户手动触发
            // 原因：避免破坏 DeepSeek 的 context caching

            // 获取运行时配置
            DeepSeekRuntimeConfig config = deepSeekConfigService.getRuntimeConfig(FeatureCode.CREATIVE_GUIDANCE, runtimeConfig);

            // 调用 DeepSeek API（流式）
            chatStream(messages, emitter, session, config);

        } catch (Exception e) {
            logger.error("对话失败: {}", e.getMessage(), e);
            sendError(emitter, "对话失败: " + e.getMessage());
        }
    }

    /**
     * 构建聊天请求体（公共逻辑提取）
     *
     * @param messages 消息历史
     * @param config 运行时配置
     * @return 请求体 Map
     */
    private Map<String, Object> buildChatRequestBody(List<Map<String, Object>> messages, DeepSeekRuntimeConfig config) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("messages", messages);
        requestBody.put("stream", true);
        requestBody.put("stream_options", Map.of("include_usage", true));
        requestBody.put("max_tokens", deepSeekConfig.getMaxTokens());
        requestBody.put("tools", getGuidanceTools());

        // 思考模式配置
        if (config.getThinkingEnabled()) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "enabled");
            requestBody.put("thinking", thinking);
            requestBody.put("reasoning_effort", config.getReasoningEffort());
        }

        return requestBody;
    }

    /**
     * 流式调用 DeepSeek API（真正的流式处理）
     */
    private void chatStream(List<Map<String, Object>> messages, SseEmitter emitter, CreativeSession session, DeepSeekRuntimeConfig config) {
        try {
            // 发送前验证并修复消息，确保 tool/tool_calls 配对完整
            repairMessages(messages);

            // 构建请求体
            Map<String, Object> requestBody = buildChatRequestBody(messages, config);
            String requestBodyStr = objectMapper.writeValueAsString(requestBody);

            logger.info("调用 DeepSeek API (流式), model={}, thinking={}, messages={}",
                    config.getModel(), config.getThinkingEnabled(), messages.size());

            // 调试：打印完整的消息内容
            logger.info("请求消息内容:\n{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(messages));

            // 检查重复的 tool_call_id
            Set<String> allToolCallIds = new HashSet<>();
            for (Map<String, Object> msg : messages) {
                if ("assistant".equals(msg.get("role"))) {
                    List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                    if (tcs != null) {
                        for (Map<String, Object> tc : tcs) {
                            String id = (String) tc.get("id");
                            if (id != null && !id.isEmpty()) {
                                if (allToolCallIds.contains(id)) {
                                    logger.error("发现重复的 tool_call_id: {} 在 assistant 消息中!", id);
                                }
                                allToolCallIds.add(id);
                            }
                        }
                    }
                }
            }
            if (!allToolCallIds.isEmpty()) {
                logger.info("所有 tool_call_id: {}", allToolCallIds);
            }

            // 使用 RestTemplate.execute 实现真正的流式请求
            restTemplate.execute(
                    deepSeekConfig.getApiUrl(),
                    org.springframework.http.HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().set("Authorization", "Bearer " + deepSeekConfig.getApiKey());
                        request.getBody().write(requestBodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    },
                    response -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {

                            StringBuilder fullContent = new StringBuilder();
                            StringBuilder reasoningContent = new StringBuilder();  // 存储推理内容
                            // 使用 Map 按 toolCallId 累积工具调用
                            Map<String, Map<String, Object>> toolCallsMap = new LinkedHashMap<>();
                            Map<String, Object> usageInfo = new LinkedHashMap<>();  // 存储 usage 信息
                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (line.trim().isEmpty() || line.startsWith(":")) {
                                    continue;
                                }

                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    try {
                                        JsonNode jsonNode = objectMapper.readTree(data);
                                        JsonNode choices = jsonNode.path("choices");

                                        // 提取 usage 信息（通常在最后一个 chunk 中）
                                        JsonNode usageNode = jsonNode.get("usage");
                                        if (usageNode != null && !usageNode.isNull()) {
                                            usageInfo.clear();
                                            usageNode.fields().forEachRemaining(entry -> {
                                                usageInfo.put(entry.getKey(), entry.getValue().asInt());
                                            });
                                        }

                                        if (choices.isArray() && choices.size() > 0) {
                                            JsonNode delta = choices.get(0).path("delta");

                                            // 处理推理内容（reasoning_content）
                                            JsonNode reasoningNode = delta.get("reasoning_content");
                                            if (reasoningNode != null && !reasoningNode.isNull() && reasoningNode.isTextual()) {
                                                String reasoningChunk = reasoningNode.asText();
                                                if (reasoningChunk != null && !reasoningChunk.isEmpty()) {
                                                    reasoningContent.append(reasoningChunk);
                                                    // 实时发送推理内容事件
                                                    emitter.send(SseEmitter.event()
                                                            .name("reasoning_content")
                                                            .data(objectMapper.writeValueAsString(Map.of("text", reasoningChunk))));
                                                }
                                            }

                                            // 处理内容
                                            JsonNode contentNode = delta.get("content");
                                            if (contentNode != null && !contentNode.isNull() && contentNode.isTextual()) {
                                                String content = contentNode.asText();
                                                if (content != null && !content.isEmpty()) {
                                                    fullContent.append(content);
                                                    // 实时发送内容事件
                                                    emitter.send(SseEmitter.event()
                                                            .name("content")
                                                            .data(objectMapper.writeValueAsString(Map.of("text", content))));
                                                }
                                            }

                                            // 处理工具调用（累积模式）
                                            // 重要：DeepSeek 流式响应使用 index 标识同一个工具调用的不同 chunk
                                            JsonNode toolCallsDelta = delta.path("tool_calls");
                                            if (toolCallsDelta.isArray() && toolCallsDelta.size() > 0) {
                                                for (JsonNode tc : toolCallsDelta) {
                                                    // 使用 index 作为累积 key（DeepSeek 流式规范）
                                                    int index = tc.path("index").asInt(-1);
                                                    String toolCallId = tc.path("id").asText(null);
                                                    JsonNode function = tc.path("function");
                                                    String functionName = function.path("name").asText(null);
                                                    String argumentsChunk = function.path("arguments").asText(null);

                                                    // 使用 index 作为 mapKey，确保同一工具调用的 chunk 正确累积
                                                    String mapKey = (index >= 0) ? String.valueOf(index) : "_default_";

                                                    Map<String, Object> toolCall = toolCallsMap.computeIfAbsent(
                                                            mapKey,
                                                            k -> {
                                                                Map<String, Object> tc2 = new LinkedHashMap<>();
                                                                // 先用 index 作为临时 id，后续会被真实 id 覆盖
                                                                tc2.put("id", "pending_id_" + index);
                                                                tc2.put("type", "function");
                                                                tc2.put("index", index);
                                                                Map<String, Object> func = new LinkedHashMap<>();
                                                                func.put("name", "");
                                                                func.put("arguments", new StringBuilder());
                                                                tc2.put("function", func);
                                                                logger.info("创建工具调用条目: index={}, mapKey={}", index, mapKey);
                                                                return tc2;
                                                            }
                                                    );

                                                    // 更新 id（如果提供了）
                                                    if (toolCallId != null && !toolCallId.isEmpty()) {
                                                        toolCall.put("id", toolCallId);
                                                        logger.debug("工具调用 id 更新: index={} -> id={}", index, toolCallId);
                                                    }

                                                    Map<String, Object> func = (Map<String, Object>) toolCall.get("function");
                                                    // 更新 function name（如果有）
                                                    if (functionName != null && !functionName.isEmpty()) {
                                                        func.put("name", functionName);
                                                        logger.info("工具调用名称更新: index={}, name={}", index, functionName);
                                                    }
                                                    // 累积 arguments
                                                    if (argumentsChunk != null && !argumentsChunk.isEmpty()) {
                                                        ((StringBuilder) func.get("arguments")).append(argumentsChunk);
                                                        logger.debug("工具调用参数累积: index={}, +{} 字符", index, argumentsChunk.length());
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.warn("解析 SSE 数据块失败: {}", e.getMessage());
                                    }
                                }
                            }

                            // 将累积的 toolCallsMap 转换为最终格式
                            List<Map<String, Object>> toolCalls = new ArrayList<>();
                            for (Map<String, Object> tc : toolCallsMap.values()) {
                                Map<String, Object> finalTc = new LinkedHashMap<>();
                                String finalId = (String) tc.get("id");
                                // 如果 id 仍是 pending_ 开头，生成最终 id
                                if (finalId == null || finalId.startsWith("pending_id_")) {
                                    finalId = "auto_id_" + UUID.randomUUID().toString().substring(0, 8);
                                }
                                finalTc.put("id", finalId);
                                finalTc.put("type", tc.get("type"));
                                Map<String, Object> func = (Map<String, Object>) tc.get("function");
                                Map<String, Object> finalFunc = new LinkedHashMap<>();
                                String funcName = (String) func.get("name");
                                finalFunc.put("name", funcName);
                                finalFunc.put("arguments", func.get("arguments").toString());  // StringBuilder 转 String
                                finalTc.put("function", finalFunc);
                                toolCalls.add(finalTc);

                                // 调试日志
                                logger.info("工具调用累积完成: id={}, name={}, arguments={}", finalId, funcName, finalFunc.get("arguments"));
                            }

                            // 构建助手消息
                            Map<String, Object> assistantMessage = new LinkedHashMap<>();
                            assistantMessage.put("role", "assistant");
                            assistantMessage.put("content", fullContent.toString());

                            // 如果有推理内容，必须包含在消息中（DeepSeek thinking 模式要求）
                            if (reasoningContent.length() > 0) {
                                assistantMessage.put("reasoning_content", reasoningContent.toString());
                            }

                            if (!toolCalls.isEmpty()) {
                                assistantMessage.put("tool_calls", toolCalls);
                            }
                            messages.add(assistantMessage);

                            // 处理工具调用
                            boolean hasToolCalls = !toolCalls.isEmpty();
                            if (hasToolCalls) {
                                processToolCalls(toolCalls, emitter, session, messages);

                                // 调试：打印 messages 验证 tool 消息
                                logger.info("=== 工具处理后消息验证 ===");
                                for (int i = 0; i < messages.size(); i++) {
                                    Map<String, Object> msg = messages.get(i);
                                    String role = (String) msg.get("role");
                                    if ("assistant".equals(role)) {
                                        List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                                        if (tcs != null) {
                                            List<String> ids = new ArrayList<>();
                                            for (Map<String, Object> tc : tcs) {
                                                ids.add((String) tc.get("id"));
                                            }
                                            logger.info("  [{}] assistant - tool_calls ids: {}", i, ids);
                                        } else {
                                            logger.info("  [{}] assistant (no tool_calls)", i);
                                        }
                                    } else if ("tool".equals(role)) {
                                        logger.info("  [{}] tool - tool_call_id: {}", i, msg.get("tool_call_id"));
                                    } else {
                                        String content = (String) msg.get("content");
                                        String preview = content != null && content.length() > 50
                                            ? content.substring(0, 50) + "..."
                                            : content;
                                        logger.info("  [{}] {} - {}", i, role, preview);
                                    }
                                }
                                logger.info("=== 消息验证结束 ===");

                                // 更新会话消息历史（包含工具结果）
                                session.setMessages(toJson(messages));
                                sessionRepository.save(session);

                                // 发送分隔事件，表示工具调用完成，即将继续生成
                                emitter.send(SseEmitter.event()
                                        .name("tool_calls_done")
                                        .data("{}"));

                                // 再次调用 API，让 AI 基于工具结果继续生成响应
                                logger.info("工具调用完成，再次调用 API 让 AI 继续生成响应");
                                continueAfterToolCalls(messages, emitter, session, config, 0);
                            } else {
                                // 没有工具调用，检查是否 content 为空但有 reasoning_content
                                boolean hasReasoningButNoContent = fullContent.length() == 0 && reasoningContent.length() > 0;

                                if (hasReasoningButNoContent) {
                                    // AI 只输出了思考内容但没有正文，追加提示让 AI 继续回复
                                    logger.info("AI只输出了思考内容（长度={}）但没有正文，将自动追加提示让AI继续", reasoningContent.length());
                                    messages.add(Map.of(
                                        "role", "user",
                                        "content", "请基于你的思考，继续给出你的回复。"
                                    ));
                                    // 再次调用 API，让 AI 生成有内容的回复
                                    continueAfterEmptyContent(messages, emitter, session, config);
                                } else {
                                    // 正常结束
                                    session.setMessages(toJson(messages));
                                    sessionRepository.save(session);

                                    // 发送 usage 事件
                                    if (!usageInfo.isEmpty()) {
                                        emitter.send(SseEmitter.event()
                                                .name("usage")
                                                .data(objectMapper.writeValueAsString(usageInfo)));
                                    }

                                    // 发送完成事件
                                    emitter.send(SseEmitter.event().name("done").data("{}"));
                                    emitter.complete();
                                }
                            }

                        } catch (Exception e) {
                            logger.error("处理流式响应失败: {}", e.getMessage(), e);
                            sendError(emitter, "处理响应失败: " + e.getMessage());
                        }
                        return null;
                    }
            );

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            // 打印完整的 HTTP 错误响应
            String errorBody = e.getResponseBodyAsString();
            logger.error("流式调用失败: HTTP {} - {}", e.getStatusCode(), errorBody, e);
            sendError(emitter, "API 调用失败: " + errorBody);
        } catch (Exception e) {
            logger.error("流式调用失败: {}", e.getMessage(), e);
            sendError(emitter, "流式调用失败: " + e.getMessage());
        }
    }

    /**
     * content为空但有reasoning_content时，追加提示让AI继续回复
     */
    private void continueAfterEmptyContent(List<Map<String, Object>> messages, SseEmitter emitter, CreativeSession session, DeepSeekRuntimeConfig config) {
        repairMessages(messages);

        try {
            // 发送提示事件，告知前端正在继续生成
            emitter.send(SseEmitter.event()
                    .name("content")
                    .data(objectMapper.writeValueAsString(Map.of("text", "\n\n"))));

            Map<String, Object> requestBody = buildChatRequestBody(messages, config);
            String requestBodyStr = objectMapper.writeValueAsString(requestBody);

            restTemplate.execute(
                    deepSeekConfig.getApiUrl(),
                    org.springframework.http.HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().set("Authorization", "Bearer " + deepSeekConfig.getApiKey());
                        request.getBody().write(requestBodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    },
                    response -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {

                            StringBuilder fullContent = new StringBuilder();
                            StringBuilder reasoningContent = new StringBuilder();
                            Map<String, Map<String, Object>> toolCallsMap = new LinkedHashMap<>();
                            Map<String, Object> usageInfo = new LinkedHashMap<>();
                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (line.trim().isEmpty() || line.startsWith(":")) {
                                    continue;
                                }

                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    try {
                                        JsonNode jsonNode = objectMapper.readTree(data);
                                        JsonNode choices = jsonNode.path("choices");

                                        JsonNode usageNode = jsonNode.get("usage");
                                        if (usageNode != null && !usageNode.isNull()) {
                                            usageInfo.clear();
                                            usageNode.fields().forEachRemaining(entry -> {
                                                usageInfo.put(entry.getKey(), entry.getValue().asInt());
                                            });
                                        }

                                        if (choices.isArray() && choices.size() > 0) {
                                            JsonNode delta = choices.get(0).path("delta");

                                            // 转发 reasoning_content
                                            JsonNode reasoningNode = delta.get("reasoning_content");
                                            if (reasoningNode != null && !reasoningNode.isNull() && reasoningNode.isTextual()) {
                                                String reasoningChunk = reasoningNode.asText();
                                                if (reasoningChunk != null && !reasoningChunk.isEmpty()) {
                                                    reasoningContent.append(reasoningChunk);
                                                    emitter.send(SseEmitter.event()
                                                            .name("reasoning_content")
                                                            .data(objectMapper.writeValueAsString(Map.of("text", reasoningChunk))));
                                                }
                                            }

                                            // 转发 content
                                            JsonNode contentNode = delta.get("content");
                                            if (contentNode != null && !contentNode.isNull() && contentNode.isTextual()) {
                                                String content = contentNode.asText();
                                                if (content != null && !content.isEmpty()) {
                                                    fullContent.append(content);
                                                    emitter.send(SseEmitter.event()
                                                            .name("content")
                                                            .data(objectMapper.writeValueAsString(Map.of("text", content))));
                                                }
                                            }

                                            // 累积 tool_calls
                                            JsonNode toolCallsDelta = delta.path("tool_calls");
                                            if (toolCallsDelta.isArray() && toolCallsDelta.size() > 0) {
                                                for (JsonNode tc : toolCallsDelta) {
                                                    int index = tc.path("index").asInt(-1);
                                                    String toolCallId = tc.path("id").asText(null);
                                                    JsonNode function = tc.path("function");
                                                    String functionName = function.path("name").asText(null);
                                                    String argumentsChunk = function.path("arguments").asText(null);

                                                    String mapKey = (index >= 0) ? String.valueOf(index) : "_default_";

                                                    Map<String, Object> toolCall = toolCallsMap.computeIfAbsent(
                                                            mapKey,
                                                            k -> {
                                                                Map<String, Object> tc2 = new LinkedHashMap<>();
                                                                tc2.put("id", "pending_id_" + index);
                                                                tc2.put("type", "function");
                                                                tc2.put("index", index);
                                                                Map<String, Object> func = new LinkedHashMap<>();
                                                                func.put("name", "");
                                                                func.put("arguments", new StringBuilder());
                                                                tc2.put("function", func);
                                                                return tc2;
                                                            }
                                                    );

                                                    if (toolCallId != null && !toolCallId.isEmpty()) {
                                                        toolCall.put("id", toolCallId);
                                                    }

                                                    Map<String, Object> func = (Map<String, Object>) toolCall.get("function");
                                                    if (functionName != null && !functionName.isEmpty()) {
                                                        func.put("name", functionName);
                                                    }
                                                    if (argumentsChunk != null && !argumentsChunk.isEmpty()) {
                                                        ((StringBuilder) func.get("arguments")).append(argumentsChunk);
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.warn("解析 SSE 数据块失败: {}", e.getMessage());
                                    }
                                }
                            }

                            // 构建助手消息
                            Map<String, Object> assistantMessage = new LinkedHashMap<>();
                            assistantMessage.put("role", "assistant");
                            assistantMessage.put("content", fullContent.toString());
                            if (reasoningContent.length() > 0) {
                                assistantMessage.put("reasoning_content", reasoningContent.toString());
                            }
                            messages.add(assistantMessage);

                            // 保存会话
                            session.setMessages(toJson(messages));
                            sessionRepository.save(session);

                            // 发送 usage 和 done
                            if (!usageInfo.isEmpty()) {
                                emitter.send(SseEmitter.event()
                                        .name("usage")
                                        .data(objectMapper.writeValueAsString(usageInfo)));
                            }

                            emitter.send(SseEmitter.event().name("done").data("{}"));
                            emitter.complete();

                        } catch (Exception e) {
                            logger.error("继续生成响应失败: {}", e.getMessage(), e);
                            try {
                                sendError(emitter, "继续生成失败: " + e.getMessage());
                            } catch (Exception ignored) {}
                        }
                        return null;
                    }
            );

        } catch (Exception e) {
            logger.error("调用继续生成失败: {}", e.getMessage(), e);
            sendError(emitter, "继续生成失败: " + e.getMessage());
        }
    }

    /**
     * 工具调用完成后继续生成响应（支持递归工具调用）
     *
     * @param messages 消息历史
     * @param emitter SSE 发射器
     * @param session 会话对象
     * @param config 运行时配置
     * @param recursionDepth 当前递归深度，用于防止无限递归
     */
    private void continueAfterToolCalls(List<Map<String, Object>> messages, SseEmitter emitter, CreativeSession session, DeepSeekRuntimeConfig config, int recursionDepth) {
        // 发送前验证并修复消息
        repairMessages(messages);
        // 检查递归深度，防止栈溢出
        if (recursionDepth > MAX_TOOL_RECURSION_DEPTH) {
            logger.warn("工具调用递归深度超过限制: {}, 终止递归", recursionDepth);
            sendError(emitter, "工具调用次数过多，请简化操作");
            return;
        }

        try {
            // 构建请求体
            Map<String, Object> requestBody = buildChatRequestBody(messages, config);
            String requestBodyStr = objectMapper.writeValueAsString(requestBody);

            restTemplate.execute(
                    deepSeekConfig.getApiUrl(),
                    org.springframework.http.HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().set("Authorization", "Bearer " + deepSeekConfig.getApiKey());
                        request.getBody().write(requestBodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    },
                    response -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {

                            StringBuilder fullContent = new StringBuilder();
                            StringBuilder reasoningContent = new StringBuilder();
                            // 使用 Map 按 toolCallId 累积工具调用（与 chatStream 相同逻辑）
                            Map<String, Map<String, Object>> toolCallsMap = new LinkedHashMap<>();
                            Map<String, Object> usageInfo = new LinkedHashMap<>();
                            String line;

                            while ((line = reader.readLine()) != null) {
                                if (line.trim().isEmpty() || line.startsWith(":")) {
                                    continue;
                                }

                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6);

                                    if ("[DONE]".equals(data.trim())) {
                                        break;
                                    }

                                    try {
                                        JsonNode jsonNode = objectMapper.readTree(data);
                                        JsonNode choices = jsonNode.path("choices");

                                        JsonNode usageNode = jsonNode.get("usage");
                                        if (usageNode != null && !usageNode.isNull()) {
                                            usageInfo.clear();
                                            usageNode.fields().forEachRemaining(entry -> {
                                                usageInfo.put(entry.getKey(), entry.getValue().asInt());
                                            });
                                        }

                                        if (choices.isArray() && choices.size() > 0) {
                                            JsonNode delta = choices.get(0).path("delta");

                                            JsonNode reasoningNode = delta.get("reasoning_content");
                                            if (reasoningNode != null && !reasoningNode.isNull() && reasoningNode.isTextual()) {
                                                String reasoningChunk = reasoningNode.asText();
                                                if (reasoningChunk != null && !reasoningChunk.isEmpty()) {
                                                    reasoningContent.append(reasoningChunk);
                                                    // 实时发送推理内容事件
                                                    emitter.send(SseEmitter.event()
                                                            .name("reasoning_content")
                                                            .data(objectMapper.writeValueAsString(Map.of("text", reasoningChunk))));
                                                }
                                            }

                                            JsonNode contentNode = delta.get("content");
                                            if (contentNode != null && !contentNode.isNull() && contentNode.isTextual()) {
                                                String content = contentNode.asText();
                                                if (content != null && !content.isEmpty()) {
                                                    fullContent.append(content);
                                                    emitter.send(SseEmitter.event()
                                                            .name("content")
                                                            .data(objectMapper.writeValueAsString(Map.of("text", content))));
                                                }
                                            }

                                            // 处理工具调用（累积模式，与 chatStream 相同）
                                            JsonNode toolCallsDelta = delta.path("tool_calls");
                                            if (toolCallsDelta.isArray() && toolCallsDelta.size() > 0) {
                                                for (JsonNode tc : toolCallsDelta) {
                                                    int index = tc.path("index").asInt(-1);
                                                    String toolCallId = tc.path("id").asText(null);
                                                    JsonNode function = tc.path("function");
                                                    String functionName = function.path("name").asText(null);
                                                    String argumentsChunk = function.path("arguments").asText(null);

                                                    String mapKey = (index >= 0) ? String.valueOf(index) : "_default_";

                                                    Map<String, Object> toolCall = toolCallsMap.computeIfAbsent(
                                                            mapKey,
                                                            k -> {
                                                                Map<String, Object> tc2 = new LinkedHashMap<>();
                                                                tc2.put("id", "pending_id_" + index);
                                                                tc2.put("type", "function");
                                                                tc2.put("index", index);
                                                                Map<String, Object> func = new LinkedHashMap<>();
                                                                func.put("name", "");
                                                                func.put("arguments", new StringBuilder());
                                                                tc2.put("function", func);
                                                                logger.info("递归工具调用累积: index={}, mapKey={}", index, mapKey);
                                                                return tc2;
                                                            }
                                                    );

                                                    if (toolCallId != null && !toolCallId.isEmpty()) {
                                                        toolCall.put("id", toolCallId);
                                                    }

                                                    Map<String, Object> func = (Map<String, Object>) toolCall.get("function");
                                                    if (functionName != null && !functionName.isEmpty()) {
                                                        func.put("name", functionName);
                                                        logger.info("递归工具调用名称: index={}, name={}", index, functionName);
                                                    }
                                                    if (argumentsChunk != null && !argumentsChunk.isEmpty()) {
                                                        ((StringBuilder) func.get("arguments")).append(argumentsChunk);
                                                    }
                                                }
                                            }
                                        }
                                    } catch (Exception e) {
                                        logger.warn("解析 SSE 数据块失败: {}", e.getMessage());
                                    }
                                }
                            }

                            // 将累积的 toolCallsMap 转换为最终格式
                            List<Map<String, Object>> toolCalls = new ArrayList<>();
                            for (Map<String, Object> tc : toolCallsMap.values()) {
                                Map<String, Object> finalTc = new LinkedHashMap<>();
                                String finalId = (String) tc.get("id");
                                if (finalId == null || finalId.startsWith("pending_id_")) {
                                    finalId = "auto_id_" + UUID.randomUUID().toString().substring(0, 8);
                                }
                                finalTc.put("id", finalId);
                                finalTc.put("type", tc.get("type"));
                                Map<String, Object> func = (Map<String, Object>) tc.get("function");
                                Map<String, Object> finalFunc = new LinkedHashMap<>();
                                String funcName = (String) func.get("name");
                                finalFunc.put("name", funcName);
                                finalFunc.put("arguments", func.get("arguments").toString());
                                finalTc.put("function", finalFunc);
                                toolCalls.add(finalTc);

                                logger.info("递归工具调用完成: id={}, name={}, arguments={}", finalId, funcName, finalFunc.get("arguments"));
                            }

                            // 构建助手消息
                            Map<String, Object> assistantMessage = new LinkedHashMap<>();
                            assistantMessage.put("role", "assistant");
                            assistantMessage.put("content", fullContent.toString());
                            if (reasoningContent.length() > 0) {
                                assistantMessage.put("reasoning_content", reasoningContent.toString());
                            }

                            if (!toolCalls.isEmpty()) {
                                assistantMessage.put("tool_calls", toolCalls);
                            }
                            messages.add(assistantMessage);

                            // 处理递归工具调用
                            boolean hasToolCalls = !toolCalls.isEmpty();
                            if (hasToolCalls) {
                                processToolCalls(toolCalls, emitter, session, messages);

                                // 更新会话消息历史
                                session.setMessages(toJson(messages));
                                sessionRepository.save(session);

                                // 发送分隔事件
                                emitter.send(SseEmitter.event()
                                        .name("tool_calls_done")
                                        .data("{}"));

                                // 递归调用，支持连续工具调用
                                logger.info("递归工具调用检测到 {} 个工具，继续生成响应，当前深度: {}", toolCalls.size(), recursionDepth + 1);
                                continueAfterToolCalls(messages, emitter, session, config, recursionDepth + 1);
                            } else {
                                // 没有工具调用，正常结束
                                session.setMessages(toJson(messages));
                                sessionRepository.save(session);

                                // 发送 usage 和 done
                                if (!usageInfo.isEmpty()) {
                                    emitter.send(SseEmitter.event()
                                            .name("usage")
                                            .data(objectMapper.writeValueAsString(usageInfo)));
                                }

                                emitter.send(SseEmitter.event().name("done").data("{}"));
                                emitter.complete();
                            }

                        } catch (Exception e) {
                            logger.error("继续生成响应失败: {}", e.getMessage(), e);
                            sendError(emitter, "继续生成失败: " + e.getMessage());
                        }
                        return null;
                    }
            );

        } catch (Exception e) {
            logger.error("调用继续生成 API 失败: {}", e.getMessage(), e);
            sendError(emitter, "继续生成失败: " + e.getMessage());
        }
    }

    /**
     * 处理工具调用
     */
    @SuppressWarnings("unchecked")
    private void processToolCalls(List<Map<String, Object>> toolCalls, SseEmitter emitter,
                                  CreativeSession session, List<Map<String, Object>> messages) {
        // 第一步：为所有工具调用分配/确认唯一的 toolCallId，并发送 tool_call_start 事件
        Set<String> assignedIds = new HashSet<>();
        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            if (toolCallId == null || toolCallId.isEmpty()) {
                toolCallId = "auto_id_" + UUID.randomUUID().toString().substring(0, 8);
                toolCall.put("id", toolCallId); // 写回，确保后续循环使用相同 ID
            }

            // 处理重复的 id
            if (assignedIds.contains(toolCallId)) {
                toolCallId = "auto_id_" + UUID.randomUUID().toString().substring(0, 8);
                toolCall.put("id", toolCallId);
            }
            assignedIds.add(toolCallId);

            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String functionName = function != null ? (String) function.get("name") : "";
            String argumentsJson = function != null ? (String) function.get("arguments") : "{}";
            String displayName = TOOL_NAME_MAP.getOrDefault(functionName, functionName);

            // 解析参数并提取关键信息用于显示
            String paramsDisplay = extractParamsDisplay(functionName, argumentsJson);

            try {
                emitter.send(SseEmitter.event()
                        .name("tool_call_start")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "callId", toolCallId,
                                "tool", functionName,
                                "displayName", displayName,
                                "params", paramsDisplay
                        ))));
            } catch (Exception e) {
                logger.warn("发送工具调用开始事件失败: {}", e.getMessage());
            }
        }

        // 第二步：执行工具调用并发送 tool_result 事件
        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            Map<String, Object> function = (Map<String, Object>) toolCall.get("function");
            String functionName = function != null ? (String) function.get("name") : "";
            String argumentsJson = function != null ? (String) function.get("arguments") : "{}";

            logger.info("[工具调用] 开始执行: {}({}), sessionId={}", functionName, argumentsJson,
                session != null ? session.getSessionId() : "null");

            String result;
            boolean success = true;

            try {
                switch (functionName) {
                    case "preview_params":
                        result = previewParams(session);
                        break;
                    case "fill_params":
                        result = fillParamsInternal(session, argumentsJson, messages);
                        break;
                    case "show_examples":
                        result = showExamples(argumentsJson);
                        break;
                    case "add_memory":
                        result = addMemory(session.getSessionId(), argumentsJson);
                        break;
                    case "remove_memory":
                        result = removeMemory(session.getSessionId(), argumentsJson);
                        break;
                    case "summarize":
                        result = summarizeConversation(messages);
                        break;
                    case "create_novel":
                        result = createNovel(argumentsJson, session, messages);
                        break;
                    case "add_chapter":
                        result = addChapter(argumentsJson, session, messages, emitter);
                        break;
                    case "create_character_card":
                        result = createCharacterCard(argumentsJson, session, messages);
                        break;
                    case "update_character_card":
                        result = updateCharacterCard(argumentsJson, session, messages);
                        break;
                    case "generate_character_image":
                        result = generateCharacterImage(argumentsJson, session, emitter);
                        break;
                    case "generate_chapter_images":
                        result = generateChapterImages(argumentsJson, session, emitter);
                        break;
                    case "generate_reference_image":
                        result = generateReferenceImage(argumentsJson, session, emitter);
                        break;
                    case "get_chapter_summaries":
                        result = getChapterSummaries(argumentsJson, session);
                        break;
                    case "update_chapter":
                        result = updateChapter(argumentsJson, session);
                        break;
                    case "update_novel":
                        result = updateNovel(argumentsJson, session);
                        break;
                    case "generate_outline":
                        result = generateOutline(argumentsJson, session, messages);
                        break;
                    case "update_outline":
                        result = updateOutline(argumentsJson, session, messages);
                        break;
                    case "list_character_cards":
                        result = listCharacterCards(argumentsJson, session);
                        break;
                    case "get_material":
                        result = getMaterial(argumentsJson);
                        break;
                    case "auto_generate_all":
                        result = autoGenerateAll(argumentsJson, session, messages);
                        break;
                    case "baidu_search":
                        result = baiduSearch(argumentsJson);
                        break;
                    default:
                        result = "{\"error\": \"未知工具: " + functionName + "\"}";
                        success = false;
                }
            } catch (Exception e) {
                logger.error("工具调用 {} 执行失败: {}", functionName, e.getMessage(), e);
                result = "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}";
                success = false;
            }

            // 添加工具结果到消息历史
            messages.add(Map.of(
                    "role", "tool",
                    "tool_call_id", toolCallId,
                    "content", result
            ));

            // 发送工具结果事件（确保即使失败也会发送，让前端知道工具调用已结束）
            try {
                emitter.send(SseEmitter.event()
                        .name("tool_result")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "callId", toolCallId,
                                "tool", functionName,
                                "result", result,
                                "success", success
                        ))));
            } catch (Exception e) {
                logger.warn("发送工具结果事件失败: {}", e.getMessage());
            }
        }
    }

    // ==================== 工具实现 ====================

    /**
     * 预览参数
     */
    private String previewParams(CreativeSession session) {
        try {
            Map<String, Object> params = parseParams(session.getExtractedParams());
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 填充参数（公开方法，用于前端调用）
     */
    @Transactional
    public NovelGeneratorRequest fillParams(String sessionId) {
        CreativeSession session = getSession(sessionId);
        return getGeneratorRequest(session);
    }

    /**
     * 填充参数（内部方法），并添加系统消息
     * 支持多选参数（数组格式）
     */
    private String fillParamsInternal(CreativeSession session, String argumentsJson, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> params = parseParams(session.getExtractedParams());
            Map<String, Object> updatedFields = new LinkedHashMap<>();

            // 如果有传入的参数，合并
            if (argumentsJson != null && !argumentsJson.isEmpty()) {
                Map<String, Object> newParams = objectMapper.readValue(argumentsJson,
                        new TypeReference<Map<String, Object>>() {});

                for (Map.Entry<String, Object> entry : newParams.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (value != null) {
                        // 支持多选：值可能是 String 或 List
                        // Jackson 会自动解析 JSON 数组为 List
                        params.put(key, value);
                        updatedFields.put(key, value);
                    }
                }
            }

            // 更新会话参数
            session.setExtractedParams(toJson(params));
            sessionRepository.save(session);

            // 注意：不要在这里添加 system 消息到 messages
            // 因为 API 要求 tool 消息必须紧跟 assistant 消息

            return objectMapper.writeValueAsString(Map.of("success", true, "params", params, "updated", updatedFields));
        } catch (Exception e) {
            logger.error("填充参数失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 展示示例
     */
    private String showExamples(String argumentsJson) {
        return "{\"message\": \"示例功能暂未实现\"}";
    }

    /**
     * 添加记忆
     * 支持 scope 参数：global（全局记忆，默认）或 session（会话记忆）
     */
    @Transactional
    private String addMemory(String sessionId, String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String key = (String) args.get("key");
            String value = (String) args.get("value");
            String scope = (String) args.getOrDefault("scope", "global"); // global 或 session

            if (key == null || value == null) {
                return "{\"error\": \"key 和 value 参数必填\"}";
            }

            boolean isSessionMemory = "session".equalsIgnoreCase(scope);
            String targetSessionId = isSessionMemory ? sessionId : null;

            // 检查是否已存在同 key 同 scope 的记忆
            Optional<CreativeMemory> existing;
            if (isSessionMemory) {
                existing = memoryRepository.findByKeyAndSessionId(key, sessionId);
            } else {
                existing = memoryRepository.findByKey(key)
                    .filter(m -> m.getSessionId() == null);
            }

            if (existing.isPresent()) {
                CreativeMemory memory = existing.get();
                memory.setValue(value);
                memory.setSourceSessionId(sessionId);
                memoryRepository.save(memory);
                logger.info("更新{}记忆: {} = {}", isSessionMemory ? "会话" : "全局", key, value);
            } else {
                CreativeMemory memory = new CreativeMemory(key, value, sessionId, isSessionMemory);
                memoryRepository.save(memory);
                logger.info("添加{}记忆: {} = {}", isSessionMemory ? "会话" : "全局", key, value);
            }

            String message = isSessionMemory ? "已添加到会话偏好" : "已添加到全局偏好";
            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "message", message,
                "key", key,
                "value", value,
                "scope", scope
            ));
        } catch (Exception e) {
            logger.error("添加记忆失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 删除记忆
     * 支持 scope 参数：global（全局记忆，默认）或 session（会话记忆）
     */
    @Transactional
    private String removeMemory(String sessionId, String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String key = (String) args.get("key");
            String scope = (String) args.getOrDefault("scope", "global");

            if (key == null) {
                return "{\"error\": \"key 参数必填\"}";
            }

            boolean isSessionMemory = "session".equalsIgnoreCase(scope);

            if (isSessionMemory) {
                memoryRepository.deleteByKeyAndSessionId(key, sessionId);
                logger.info("删除会话记忆: {}, sessionId: {}", key, sessionId);
            } else {
                memoryRepository.deleteByKey(key);
                logger.info("删除全局记忆: {}", key);
            }

            String message = isSessionMemory ? "已删除会话记忆" : "已删除全局记忆";
            return objectMapper.writeValueAsString(Map.of("success", true, "message", message, "key", key, "scope", scope));
        } catch (Exception e) {
            logger.error("删除记忆失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 生成对话摘要
     */
    private String summarizeConversation(List<Map<String, Object>> messages) {
        // 简单实现：标记需要摘要，实际摘要由 AI 生成
        return "{\"message\": \"摘要功能将由 AI 在下次请求时生成\"}";
    }

    /**
     * 获取素材工具实现
     * 根据场景关键词按需加载素材库中的相关素材
     */
    private String getMaterial(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String scene = (String) args.get("scene");
            String detail = (String) args.get("detail");

            if (scene == null || scene.trim().isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", "scene 参数必填",
                    "availableScenes", materialService.getAvailableScenes()
                ));
            }

            logger.info("获取素材: scene={}, detail={}", scene, detail);

            String content;
            if (detail != null && !detail.trim().isEmpty()) {
                content = materialService.getMaterialsByScene(scene, detail);
            } else {
                content = materialService.getMaterialsByScene(scene);
            }

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "scene", scene,
                "content", content
            ));
        } catch (Exception e) {
            logger.error("获取素材失败: {}", e.getMessage(), e);
            return "{\"success\": false, \"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 一键生成完整创作方案
     * 基于对话中已收集的信息，自动生成小说参数、角色卡、章节大纲等完整设定
     */
    @Transactional
    private String autoGenerateAll(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 提取 novelParams
            Map<String, Object> novelParams = new LinkedHashMap<>();
            Object npObj = args.get("novelParams");
            if (npObj instanceof Map) {
                novelParams.putAll((Map<String, Object>) npObj);
            }

            // 提取 characterCards
            List<Map<String, Object>> characterCards = new ArrayList<>();
            Object ccObj = args.get("characterCards");
            if (ccObj instanceof List) {
                characterCards.addAll((List<Map<String, Object>>) ccObj);
            }

            // 提取 outline
            Map<String, Object> outline = new LinkedHashMap<>();
            Object olObj = args.get("outline");
            if (olObj instanceof Map) {
                outline.putAll((Map<String, Object>) olObj);
            }

            // 提取 summary
            String summary = args.get("summary") != null ? (String) args.get("summary") : "";

            // 合并到现有参数
            Map<String, Object> existingParams = parseParams(session.getExtractedParams());
            for (Map.Entry<String, Object> entry : novelParams.entrySet()) {
                if (entry.getValue() != null) {
                    existingParams.put(entry.getKey(), entry.getValue());
                }
            }
            session.setExtractedParams(toJson(existingParams));

            // 保存角色卡信息到会话上下文（供后续 create_character_card 使用）
            SessionContext context = getContext(session);
            if (!characterCards.isEmpty()) {
                context.setPendingCharacterCards(characterCards);
            }

            // 保存大纲信息到会话上下文（供后续 generate_outline 使用）
            if (!outline.isEmpty()) {
                context.setPendingOutline(outline);
            }

            sessionRepository.save(session);

            // 构建返回结果
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("summary", summary);

            // 参数摘要
            Map<String, String> paramSummary = new LinkedHashMap<>();
            paramSummary.put("题材", novelParams.get("theme") != null ? (String) novelParams.get("theme") : "未设定");
            paramSummary.put("风格", novelParams.get("style") != null ? (String) novelParams.get("style") : "未设定");
            paramSummary.put("主角", novelParams.get("protagonistName") != null ? (String) novelParams.get("protagonistName") : "未设定");
            paramSummary.put("主线", novelParams.get("mainPlot") != null ? (String) novelParams.get("mainPlot") : "未设定");
            paramSummary.put("章节数", novelParams.get("chapterCount") != null ? String.valueOf(novelParams.get("chapterCount")) : "未设定");
            result.put("paramSummary", paramSummary);

            // 角色卡数量
            result.put("characterCount", characterCards.size());

            // 大纲章节数
            Object chaptersObj = outline.get("chapters");
            int chapterCount = chaptersObj instanceof List ? ((List<?>) chaptersObj).size() : 0;
            result.put("outlineChapterCount", chapterCount);

            // 标记方案已生成，等待用户确认
            context.setSchemeGenerated(true);
            sessionRepository.save(session);

            logger.info("一键生成完整方案: 题材={}, 主角={}, 角色数={}, 大纲章节数={}",
                novelParams.get("theme"), novelParams.get("protagonistName"),
                characterCards.size(), chapterCount);

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("一键生成方案失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 百度搜索工具实现
     * 调用百度搜索API获取实时素材和创作灵感
     */
    private String baiduSearch(String argumentsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String query = (String) args.get("query");
            Integer count = args.get("count") != null ? (Integer) args.get("count") : 10;
            String freshness = (String) args.get("freshness");

            if (query == null || query.trim().isEmpty()) {
                return "{\"error\": \"搜索关键词不能为空\"}";
            }

            if (!baiduSearchService.isAvailable()) {
                return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", "百度搜索API未配置（需设置BAIDU_API_KEY环境变量）",
                    "errorCode", "SEARCH_UNAVAILABLE"
                ));
            }

            logger.info("百度搜索: query={}, count={}, freshness={}", query, count, freshness);

            List<BaiduSearchService.SearchResult> results = baiduSearchService.search(query, count, freshness);

            if (results.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "query", query,
                    "count", 0,
                    "message", "未找到相关结果"
                ));
            }

            // 构建返回结果
            List<Map<String, String>> simplifiedResults = results.stream()
                .limit(count)
                .map(r -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", r.getTitle());
                    item.put("url", r.getUrl());
                    item.put("abstract", r.getAbstractText());
                    if (r.getSiteName() != null) item.put("site", r.getSiteName());
                    if (r.getPublishTime() != null) item.put("time", r.getPublishTime());
                    return item;
                })
                .toList();

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "query", query,
                "count", simplifiedResults.size(),
                "results", simplifiedResults
            ));
        } catch (Exception e) {
            logger.error("百度搜索失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    // ==================== 新增创作工具实现 ====================

    /**
     * 创建小说工具实现
     */
    private String createNovel(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            String title = (String) args.get("title");

            if (title == null || title.trim().isEmpty()) {
                return "{\"error\": \"小说标题不能为空\"}";
            }

            // 检查是否已绑定小说
            SessionContext context = getContext(session);
            if (context.getCurrentNovelId() != null) {
                Novel existingNovel = novelService.getNovelById(context.getCurrentNovelId());
                String existingTitle = existingNovel != null ? existingNovel.getTitle() : "未知";
                logger.warn("会话已绑定小说，拒绝重复创建: sessionId={}, existingNovelId={}", session.getSessionId(), context.getCurrentNovelId());
                return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", "当前会话已绑定小说《" + existingTitle + "》，如需创建新小说请新开会话",
                    "errorCode", "NOVEL_ALREADY_BOUND"
                ));
            }

            // 调用 NovelService 创建小说
            Novel novel = novelService.createNovel(title.trim());

            // 更新会话上下文
            context.setCurrentNovelId(novel.getId());
            updateContext(session, context);

            // 更新会话标题为小说标题
            session.setTitle(title.trim());
            sessionRepository.save(session);

            logger.info("创建小说成功: id={}, title={}", novel.getId(), title);

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "novelId", novel.getId(),
                "title", title
            ));
        } catch (Exception e) {
            logger.error("创建小说失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 添加章节工具实现（子Agent模式）
     * 只传入标题+概括，由 ChapterGenerationService 异步生成完整内容
     */
    private String addChapter(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages, SseEmitter emitter) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            if (novelId == null) {
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            // ===== 检查主角角色卡 =====
            if (!characterCardService.hasProtagonistCard(novelId)) {
                logger.warn("尝试添加章节但缺少主角角色卡: novelId={}", novelId);
                return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", "请先创建主角角色卡后再开始写章节",
                    "errorCode", "NO_PROTAGONIST_CARD",
                    "hint", "在左侧信息面板中创建主角角色卡"
                ));
            }
            // ===== 检查结束 =====

            String title = (String) args.get("title");
            String summary = (String) args.get("summary");

            if (title == null || title.trim().isEmpty()) {
                return "{\"error\": \"章节标题不能为空\"}";
            }
            if (summary == null || summary.trim().isEmpty()) {
                return "{\"error\": \"章节概括不能为空，系统需要概括来生成章节内容\"}";
            }

            // 启动异步生成
            String taskId = chapterGenerationService.startGeneration(
                novelId, title.trim(), summary.trim(),
                emitter, session.getSessionId(), session.getExtractedParams()
            );

            logger.info("启动章节异步生成: novelId={}, title={}, taskId={}", novelId, title, taskId);

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "taskId", taskId,
                "status", "generating",
                "message", "章节已加入生成队列，将按顺序自动生成并保存"
            ));
        } catch (Exception e) {
            logger.error("添加章节失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 查询角色卡列表工具实现
     */
    private String listCharacterCards(String argumentsJson, CreativeSession session) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            if (novelId == null) {
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            // 获取所有角色卡
            List<CharacterCard> cards = characterCardService.getCharacterCardsByNovelId(novelId);

            // 构建简化的返回列表
            List<Map<String, Object>> characterList = cards.stream()
                .map(c -> {
                    Map<String, Object> cardInfo = new LinkedHashMap<>();
                    cardInfo.put("id", c.getId());
                    cardInfo.put("name", c.getName());
                    cardInfo.put("role", c.getRole() != null ? c.getRole() : "");
                    cardInfo.put("gender", c.getGender());
                    cardInfo.put("age", c.getAge());
                    cardInfo.put("occupation", c.getOccupation());
                    cardInfo.put("personality", c.getPersonality());
                    cardInfo.put("hasImage", c.getGeneratedImageUrl() != null && !c.getGeneratedImageUrl().isEmpty());
                    return cardInfo;
                })
                .toList();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("novelId", novelId);
            result.put("count", characterList.size());
            result.put("characters", characterList);

            // 统计主角和配角数量
            long protagonistCount = cards.stream().filter(c -> "protagonist".equals(c.getRole())).count();
            long supportingCount = cards.stream().filter(c -> "supporting".equals(c.getRole())).count();
            result.put("protagonistCount", protagonistCount);
            result.put("supportingCount", supportingCount);
            result.put("hasProtagonist", protagonistCount > 0);

            logger.info("查询角色卡列表: novelId={}, count={}", novelId, characterList.size());

            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("查询角色卡列表失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 创建角色卡工具实现
     */
    private String createCharacterCard(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            if (novelId == null) {
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            // 校验小说是否存在
            if (!novelService.existsById(novelId)) {
                return "{\"error\": \"小说 ID " + novelId + " 不存在，请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            String name = (String) args.get("name");
            if (name == null || name.trim().isEmpty()) {
                return "{\"error\": \"角色姓名不能为空\"}";
            }

            String role = (String) args.get("role");

            // ===== 获取现有角色卡 =====
            List<CharacterCard> existingCards = characterCardService.getCharacterCardsByNovelId(novelId);

            // 构建现有角色摘要列表（用于返回给AI）
            List<Map<String, Object>> existingCharactersSummary = existingCards.stream()
                .map(c -> Map.<String, Object>of(
                    "id", c.getId(),
                    "name", c.getName() != null ? c.getName() : "",
                    "role", c.getRole() != null ? c.getRole() : ""
                ))
                .toList();

            // ===== 检查同名角色 =====
            for (CharacterCard existing : existingCards) {
                if (name.trim().equals(existing.getName())) {
                    logger.warn("创建角色卡失败：同名角色已存在, novelId={}, name={}", novelId, name);
                    Map<String, Object> errorResult = new LinkedHashMap<>();
                    errorResult.put("success", false);
                    errorResult.put("error", "角色'" + name + "'已存在");
                    errorResult.put("errorCode", "DUPLICATE_NAME");
                    errorResult.put("existingCharacterId", existing.getId());
                    errorResult.put("suggestion", "如需修改现有角色，请使用 update_character_card 工具");
                    errorResult.put("existingCharacters", existingCharactersSummary);
                    return objectMapper.writeValueAsString(errorResult);
                }
            }

            // ===== 检查主角唯一性 =====
            if ("protagonist".equals(role)) {
                boolean hasProtagonist = existingCards.stream()
                    .anyMatch(c -> "protagonist".equals(c.getRole()));
                if (hasProtagonist) {
                    // 找到现有主角
                    CharacterCard existingProtagonist = existingCards.stream()
                        .filter(c -> "protagonist".equals(c.getRole()))
                        .findFirst()
                        .orElse(null);

                    logger.warn("创建角色卡失败：主角已存在, novelId={}", novelId);
                    Map<String, Object> errorResult = new LinkedHashMap<>();
                    errorResult.put("success", false);
                    errorResult.put("error", "每部小说只能有一个主角，当前小说已有主角");
                    errorResult.put("errorCode", "PROTAGONIST_EXISTS");
                    if (existingProtagonist != null) {
                        errorResult.put("existingProtagonistId", existingProtagonist.getId());
                        errorResult.put("existingProtagonistName", existingProtagonist.getName());
                        errorResult.put("suggestion", "如需更换主角，请将现有主角改为配角(supporting)后再创建新主角");
                    }
                    errorResult.put("existingCharacters", existingCharactersSummary);
                    return objectMapper.writeValueAsString(errorResult);
                }
            }
            // ===== 检查结束 =====

            // 生成随机 seed（1-2147483647）
            int seed = new Random().nextInt(2147483646) + 1;

            // 构建角色卡 DTO
            CharacterCard card = new CharacterCard();
            card.setName(name.trim());
            card.setRole(role);  // 角色类型：protagonist/supporting
            card.setPersonality((String) args.get("personality"));
            card.setBackground((String) args.get("description"));

            // 新增字段
            if (args.get("age") != null) {
                card.setAge(((Number) args.get("age")).intValue());
            }
            card.setGender((String) args.get("gender"));
            card.setOccupation((String) args.get("occupation"));

            // 解析结构化外貌字段
            Object appearanceArg = args.get("appearance");
            if (appearanceArg instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> appearanceMap = (Map<String, Object>) appearanceArg;
                CharacterCardAppearance appearance = new CharacterCardAppearance();
                appearance.setHeight((String) appearanceMap.get("height"));
                appearance.setHair((String) appearanceMap.get("hair"));
                appearance.setEyes((String) appearanceMap.get("eyes"));
                appearance.setFace((String) appearanceMap.get("face"));
                appearance.setBuild((String) appearanceMap.get("build"));
                appearance.setClothing((String) appearanceMap.get("clothing"));
                appearance.setLegwear((String) appearanceMap.get("legwear"));
                appearance.setShoes((String) appearanceMap.get("shoes"));
                appearance.setAccessories((String) appearanceMap.get("accessories"));
                appearance.setDistinguishingFeatures((String) appearanceMap.get("distinguishingFeatures"));
                card.setAppearance(appearance);

                // 生成外貌描述文本（用于AI生成图片）
                StringBuilder appearanceDesc = new StringBuilder();
                if (appearance.getHeight() != null && !appearance.getHeight().isEmpty()) {
                    appearanceDesc.append("身高").append(appearance.getHeight()).append("，");
                }
                if (appearance.getHair() != null && !appearance.getHair().isEmpty()) {
                    appearanceDesc.append(appearance.getHair()).append("，");
                }
                if (appearance.getEyes() != null && !appearance.getEyes().isEmpty()) {
                    appearanceDesc.append(appearance.getEyes()).append("，");
                }
                if (appearance.getFace() != null && !appearance.getFace().isEmpty()) {
                    appearanceDesc.append(appearance.getFace()).append("，");
                }
                if (appearance.getBuild() != null && !appearance.getBuild().isEmpty()) {
                    appearanceDesc.append(appearance.getBuild()).append("，");
                }
                if (appearance.getClothing() != null && !appearance.getClothing().isEmpty()) {
                    appearanceDesc.append("穿着").append(appearance.getClothing()).append("，");
                }
                if (appearance.getLegwear() != null && !appearance.getLegwear().isEmpty()) {
                    appearanceDesc.append(appearance.getLegwear()).append("，");
                }
                if (appearance.getShoes() != null && !appearance.getShoes().isEmpty()) {
                    appearanceDesc.append(appearance.getShoes()).append("，");
                }
                if (appearance.getAccessories() != null && !appearance.getAccessories().isEmpty()) {
                    appearanceDesc.append("配饰：").append(appearance.getAccessories()).append("，");
                }
                if (appearance.getDistinguishingFeatures() != null && !appearance.getDistinguishingFeatures().isEmpty()) {
                    appearanceDesc.append("显著特征：").append(appearance.getDistinguishingFeatures()).append("。");
                }
                // 移除末尾的逗号
                String desc = appearanceDesc.toString().replaceAll("，+$", "。");
                card.setAppearanceDescription(desc);
            }

            // 解析关系字段
            Object relationshipsArg = args.get("relationships");
            if (relationshipsArg instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> relationshipsList = (List<Map<String, Object>>) relationshipsArg;
                List<CharacterCardRelationship> relationships = new ArrayList<>();
                for (Map<String, Object> relMap : relationshipsList) {
                    CharacterCardRelationship relationship = new CharacterCardRelationship();
                    relationship.setTargetName((String) relMap.get("targetName"));
                    relationship.setRelationship((String) relMap.get("relationship"));
                    relationships.add(relationship);
                }
                card.setRelationships(relationships);
            }

            // 存储 seed 在 notes 字段（临时方案）
            card.setNotes("seed:" + seed);

            // 调用 CharacterCardService 保存
            List<CharacterCard> savedCards = characterCardService.saveCharacterCards(novelId, Arrays.asList(card));
            CharacterCard savedCard = savedCards.get(0);

            // 更新上下文
            context.addCharacterId(savedCard.getId());
            updateContext(session, context);

            logger.info("创建角色卡成功: novelId={}, characterId={}, name={}, seed={}", novelId, savedCard.getId(), name, seed);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("characterId", savedCard.getId());
            result.put("name", name);
            result.put("appearance", card.getAppearanceDescription());
            result.put("personality", card.getPersonality());
            result.put("description", card.getBackground());
            result.put("role", role);
            result.put("seed", seed);
            // 成功时也返回现有角色列表（方便AI了解全局）
            existingCharactersSummary.add(Map.of("id", savedCard.getId(), "name", name.trim(), "role", role != null ? role : ""));
            result.put("allCharacters", existingCharactersSummary);
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("创建角色卡失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 更新角色卡工具实现
     */
    private String updateCharacterCard(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            Long characterId = args.get("characterId") != null
                ? ((Number) args.get("characterId")).longValue()
                : null;

            if (characterId == null) {
                // 尝试从上下文获取最近创建的角色卡
                SessionContext context = getContext(session);
                List<Long> characterIds = context.getCreatedCharacterIds();
                if (characterIds != null && !characterIds.isEmpty()) {
                    characterId = characterIds.get(characterIds.size() - 1);
                } else {
                    return "{\"error\": \"请提供角色卡ID，或先创建角色卡\", \"errorCode\": \"CHARACTER_ID_REQUIRED\"}";
                }
            }

            // 获取现有角色卡
            CharacterCard card = characterCardService.getCharacterCardById(characterId);
            if (card == null) {
                return "{\"error\": \"角色卡不存在\", \"errorCode\": \"CHARACTER_NOT_FOUND\"}";
            }

            // 更新字段（只更新提供的字段）
            if (args.get("name") != null) {
                card.setName((String) args.get("name"));
            }
            if (args.get("role") != null) {
                card.setRole((String) args.get("role"));
            }
            if (args.get("age") != null) {
                card.setAge(((Number) args.get("age")).intValue());
            }
            if (args.get("gender") != null) {
                card.setGender((String) args.get("gender"));
            }
            if (args.get("occupation") != null) {
                card.setOccupation((String) args.get("occupation"));
            }
            // 解析结构化外貌字段
            Object appearanceArg = args.get("appearance");
            if (appearanceArg instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> appearanceMap = (Map<String, Object>) appearanceArg;
                CharacterCardAppearance appearance = card.getAppearance();
                if (appearance == null) {
                    appearance = new CharacterCardAppearance();
                }
                if (appearanceMap.get("height") != null) {
                    appearance.setHeight((String) appearanceMap.get("height"));
                }
                if (appearanceMap.get("hair") != null) {
                    appearance.setHair((String) appearanceMap.get("hair"));
                }
                if (appearanceMap.get("eyes") != null) {
                    appearance.setEyes((String) appearanceMap.get("eyes"));
                }
                if (appearanceMap.get("face") != null) {
                    appearance.setFace((String) appearanceMap.get("face"));
                }
                if (appearanceMap.get("build") != null) {
                    appearance.setBuild((String) appearanceMap.get("build"));
                }
                if (appearanceMap.get("clothing") != null) {
                    appearance.setClothing((String) appearanceMap.get("clothing"));
                }
                if (appearanceMap.get("legwear") != null) {
                    appearance.setLegwear((String) appearanceMap.get("legwear"));
                }
                if (appearanceMap.get("shoes") != null) {
                    appearance.setShoes((String) appearanceMap.get("shoes"));
                }
                if (appearanceMap.get("accessories") != null) {
                    appearance.setAccessories((String) appearanceMap.get("accessories"));
                }
                if (appearanceMap.get("distinguishingFeatures") != null) {
                    appearance.setDistinguishingFeatures((String) appearanceMap.get("distinguishingFeatures"));
                }
                card.setAppearance(appearance);

                // 更新外貌描述文本
                StringBuilder appearanceDesc = new StringBuilder();
                if (appearance.getHeight() != null && !appearance.getHeight().isEmpty()) {
                    appearanceDesc.append("身高").append(appearance.getHeight()).append("，");
                }
                if (appearance.getHair() != null && !appearance.getHair().isEmpty()) {
                    appearanceDesc.append(appearance.getHair()).append("，");
                }
                if (appearance.getEyes() != null && !appearance.getEyes().isEmpty()) {
                    appearanceDesc.append(appearance.getEyes()).append("，");
                }
                if (appearance.getFace() != null && !appearance.getFace().isEmpty()) {
                    appearanceDesc.append(appearance.getFace()).append("，");
                }
                if (appearance.getBuild() != null && !appearance.getBuild().isEmpty()) {
                    appearanceDesc.append(appearance.getBuild()).append("，");
                }
                if (appearance.getClothing() != null && !appearance.getClothing().isEmpty()) {
                    appearanceDesc.append("穿着").append(appearance.getClothing()).append("，");
                }
                if (appearance.getLegwear() != null && !appearance.getLegwear().isEmpty()) {
                    appearanceDesc.append(appearance.getLegwear()).append("，");
                }
                if (appearance.getShoes() != null && !appearance.getShoes().isEmpty()) {
                    appearanceDesc.append(appearance.getShoes()).append("，");
                }
                if (appearance.getAccessories() != null && !appearance.getAccessories().isEmpty()) {
                    appearanceDesc.append("配饰：").append(appearance.getAccessories()).append("，");
                }
                if (appearance.getDistinguishingFeatures() != null && !appearance.getDistinguishingFeatures().isEmpty()) {
                    appearanceDesc.append("显著特征：").append(appearance.getDistinguishingFeatures()).append("。");
                }
                String desc = appearanceDesc.toString().replaceAll("，+$", "。");
                card.setAppearanceDescription(desc);
            }
            if (args.get("personality") != null) {
                card.setPersonality((String) args.get("personality"));
            }
            if (args.get("description") != null) {
                card.setBackground((String) args.get("description"));
            }

            // 解析关系字段
            Object relationshipsArg = args.get("relationships");
            if (relationshipsArg instanceof List) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> relationshipsList = (List<Map<String, Object>>) relationshipsArg;
                List<CharacterCardRelationship> relationships = new ArrayList<>();
                for (Map<String, Object> relMap : relationshipsList) {
                    CharacterCardRelationship relationship = new CharacterCardRelationship();
                    relationship.setTargetName((String) relMap.get("targetName"));
                    relationship.setRelationship((String) relMap.get("relationship"));
                    relationships.add(relationship);
                }
                card.setRelationships(relationships);
            }

            // 保存更新
            characterCardService.updateCharacterCard(characterId, card);

            logger.info("更新角色卡成功: characterId={}, name={}", characterId, card.getName());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("characterId", characterId);
            result.put("name", card.getName());
            result.put("appearance", card.getAppearanceDescription());
            result.put("personality", card.getPersonality());
            result.put("description", card.getBackground());
            result.put("role", args.get("role"));
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            logger.error("更新角色卡失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 生成角色图片工具实现
     */
    private String generateCharacterImage(String argumentsJson, CreativeSession session, SseEmitter emitter) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            Long characterId = ((Number) args.get("characterId")).longValue();
            String size = (String) args.getOrDefault("size", "1:1");

            // 获取角色卡
            CharacterCard card = characterCardService.getCharacterCardById(characterId);
            if (card == null) {
                return "{\"error\": \"角色卡不存在，请先创建\", \"errorCode\": \"CHARACTER_NOT_FOUND\"}";
            }

            // 构建提示词
            StringBuilder prompt = new StringBuilder();
            if (card.getAppearanceDescription() != null && !card.getAppearanceDescription().isEmpty()) {
                prompt.append(card.getAppearanceDescription());
            } else {
                prompt.append("beautiful character portrait");
            }
            prompt.append(", character art, ").append(card.getName());

            // 从 notes 中提取 seed（如果存在）
            Integer seed = null;
            if (card.getNotes() != null && card.getNotes().startsWith("seed:")) {
                try {
                    seed = Integer.parseInt(card.getNotes().substring(5).trim());
                } catch (NumberFormatException e) {
                    // 忽略解析错误
                }
            }

            // 调用 EvoLink 生成图片
            String taskId = evoLinkImageService.generateImage(prompt.toString(), size, seed);
            logger.info("开始轮询图片生成任务: taskId={}", taskId);

            // 发送开始生成事件
            emitter.send(SseEmitter.event()
                .name("image_progress")
                .data(objectMapper.writeValueAsString(Map.of(
                    "taskId", taskId,
                    "status", "started",
                    "progress", 0,
                    "message", "开始生成角色图片...",
                    "characterId", characterId,
                    "characterName", card.getName()
                ))));

            // 轮询获取结果（最多等待5分钟，gpt-image-2需要更长时间）
            String imageUrl = null;
            int maxAttempts = 150;  // 150次 * 2秒 = 300秒 = 5分钟
            int lastReportedProgress = 0;
            for (int i = 0; i < maxAttempts; i++) {
                Thread.sleep(2000);
                EvoLinkImageService.TaskStatus status = evoLinkImageService.getTaskStatus(taskId);

                // 每10次轮询输出一次日志，避免日志过多
                if (i % 10 == 0 || "completed".equals(status.getStatus()) || "failed".equals(status.getStatus())) {
                    logger.info("轮询任务状态: attempt={}/{}, taskId={}, status={}, progress={}, imageUrl={}",
                        i + 1, maxAttempts, taskId, status.getStatus(), status.getProgress(), status.getImageUrl());
                }

                // 发送进度更新（每增加5%或状态变化时发送）
                int currentProgress = status.getProgress();
                if (currentProgress >= lastReportedProgress + 5 || "completed".equals(status.getStatus()) || "failed".equals(status.getStatus())) {
                    lastReportedProgress = currentProgress;

                    String progressMessage = "生成中...";
                    if (currentProgress < 20) {
                        progressMessage = "正在构思画面...";
                    } else if (currentProgress < 50) {
                        progressMessage = "正在绘制草图...";
                    } else if (currentProgress < 80) {
                        progressMessage = "正在细化细节...";
                    } else {
                        progressMessage = "即将完成...";
                    }

                    emitter.send(SseEmitter.event()
                        .name("image_progress")
                        .data(objectMapper.writeValueAsString(Map.of(
                            "taskId", taskId,
                            "status", status.getStatus(),
                            "progress", currentProgress,
                            "message", progressMessage,
                            "characterId", characterId,
                            "characterName", card.getName()
                        ))));
                }

                if ("completed".equals(status.getStatus())) {
                    imageUrl = status.getImageUrl();
                    logger.info("图片生成完成: taskId={}, imageUrl={}", taskId, imageUrl);
                    break;
                } else if ("failed".equals(status.getStatus())) {
                    logger.error("图片生成失败: taskId={}, error={}", taskId, status.getError());

                    // 发送失败事件
                    emitter.send(SseEmitter.event()
                        .name("image_progress")
                        .data(objectMapper.writeValueAsString(Map.of(
                            "taskId", taskId,
                            "status", "failed",
                            "progress", currentProgress,
                            "message", "生成失败: " + status.getError(),
                            "characterId", characterId,
                            "characterName", card.getName()
                        ))));

                    return "{\"error\": \"图片生成失败: " + status.getError() + "\", \"errorCode\": \"IMAGE_GENERATION_FAILED\"}";
                }
            }

            if (imageUrl == null) {
                return "{\"error\": \"图片生成超时\", \"errorCode\": \"IMAGE_GENERATION_FAILED\"}";
            }

            // 更新角色卡图片
            characterCardService.updateCharacterCardAIGeneratedFields(characterId, card.getAppearanceDescription(), imageUrl);

            // 发送图片结果事件
            emitter.send(SseEmitter.event()
                .name("image_result")
                .data(objectMapper.writeValueAsString(Map.of(
                    "imageUrl", imageUrl,
                    "type", "character",
                    "id", characterId,
                    "name", card.getName()
                ))));

            logger.info("生成角色图片成功: characterId={}, imageUrl={}", characterId, imageUrl);

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "imageUrl", imageUrl,
                "characterId", characterId,
                "characterName", card.getName()
            ));
        } catch (Exception e) {
            logger.error("生成角色图片失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 生成章节配图工具实现
     */
    @SuppressWarnings("unchecked")
    private String generateChapterImages(String argumentsJson, CreativeSession session, SseEmitter emitter) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            Long chapterId = ((Number) args.get("chapterId")).longValue();
            String mode = (String) args.getOrDefault("mode", "confirm");

            // 调用 ChapterImageService 分析章节
            List<Map<String, Object>> recommendations = chapterImageService.analyzeChapterForImages(chapterId);

            if (recommendations == null || recommendations.isEmpty()) {
                return "{\"success\": true, \"message\": \"该章节无需配图\"}";
            }

            // confirm 模式：返回推荐列表
            if ("confirm".equals(mode)) {
                // 为每个推荐构建 prompt
                List<Map<String, Object>> formattedRecs = new ArrayList<>();
                for (Map<String, Object> rec : recommendations) {
                    String description = (String) rec.get("description");
                    String prompt = chapterImageService.generateImagePrompt(description);
                    formattedRecs.add(Map.of(
                        "position", rec.get("position"),
                        "reason", rec.get("reason"),
                        "description", description,
                        "prompt", prompt
                    ));
                }

                return objectMapper.writeValueAsString(Map.of(
                    "success", true,
                    "mode", "confirm",
                    "recommendations", formattedRecs
                ));
            }

            // auto 模式：直接生成
            List<Map<String, Object>> images = new ArrayList<>();
            for (Map<String, Object> rec : recommendations) {
                String description = (String) rec.get("description");
                String prompt = chapterImageService.generateImagePrompt(description);
                String taskId = evoLinkImageService.generateImage(prompt, "16:9", null);

                // 轮询获取结果
                String imageUrl = null;
                int maxAttempts = 30;
                for (int i = 0; i < maxAttempts; i++) {
                    Thread.sleep(2000);
                    EvoLinkImageService.TaskStatus status = evoLinkImageService.getTaskStatus(taskId);
                    if ("completed".equals(status.getStatus())) {
                        imageUrl = status.getImageUrl();
                        break;
                    } else if ("failed".equals(status.getStatus())) {
                        break;
                    }
                }

                if (imageUrl != null) {
                    images.add(Map.of(
                        "imageUrl", imageUrl,
                        "position", rec.get("position")
                    ));

                    // 发送图片结果事件
                    emitter.send(SseEmitter.event()
                        .name("image_result")
                        .data(objectMapper.writeValueAsString(Map.of(
                            "imageUrl", imageUrl,
                            "type", "chapter",
                            "chapterId", chapterId,
                            "position", rec.get("position")
                        ))));
                }
            }

            logger.info("生成章节配图成功: chapterId={}, count={}", chapterId, images.size());

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "images", images
            ));
        } catch (Exception e) {
            logger.error("生成章节配图失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 生成参考图片工具实现
     * 不直接生成图片，返回确认卡片数据供前端渲染
     */
    @SuppressWarnings("unchecked")
    private String generateReferenceImage(String argumentsJson, CreativeSession session, SseEmitter emitter) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            String prompt = (String) args.get("prompt");
            String size = (String) args.getOrDefault("suggestedSize", "1:1");
            String type = (String) args.getOrDefault("type", "other");

            if (prompt == null || prompt.trim().isEmpty()) {
                return "{\"error\": \"提示词不能为空\", \"errorCode\": \"EMPTY_PROMPT\"}";
            }

            logger.info("开始生成参考图片: type={}, size={}, prompt={}", type, size, prompt);

            // 发送开始生成事件
            emitter.send(SseEmitter.event()
                .name("image_progress")
                .data(objectMapper.writeValueAsString(Map.of(
                    "taskId", "ref-" + System.currentTimeMillis(),
                    "status", "started",
                    "progress", 0,
                    "message", "开始生成参考图片...",
                    "type", type
                ))));

            // 调用 EvoLink 生成图片
            String taskId = evoLinkImageService.generateImage(prompt, size);
            logger.info("参考图片生成任务已创建: taskId={}", taskId);

            // 轮询获取结果（最多等待5分钟）
            String imageUrl = null;
            int maxAttempts = 150;
            int lastReportedProgress = 0;
            for (int i = 0; i < maxAttempts; i++) {
                Thread.sleep(2000);
                EvoLinkImageService.TaskStatus status = evoLinkImageService.getTaskStatus(taskId);

                // 每10次轮询输出一次日志
                if (i % 10 == 0 || "completed".equals(status.getStatus()) || "failed".equals(status.getStatus())) {
                    logger.info("参考图片轮询: attempt={}/{}, status={}, progress={}",
                        i + 1, maxAttempts, status.getStatus(), status.getProgress());
                }

                // 发送进度更新（每增加5%或状态变化时发送）
                int currentProgress = status.getProgress();
                if (currentProgress >= lastReportedProgress + 5 || "completed".equals(status.getStatus()) || "failed".equals(status.getStatus())) {
                    lastReportedProgress = currentProgress;

                    String progressMessage = "生成中...";
                    if (currentProgress < 20) {
                        progressMessage = "正在构思画面...";
                    } else if (currentProgress < 50) {
                        progressMessage = "正在绘制草图...";
                    } else if (currentProgress < 80) {
                        progressMessage = "正在细化细节...";
                    } else {
                        progressMessage = "即将完成...";
                    }

                    emitter.send(SseEmitter.event()
                        .name("image_progress")
                        .data(objectMapper.writeValueAsString(Map.of(
                            "taskId", taskId,
                            "status", status.getStatus(),
                            "progress", currentProgress,
                            "message", progressMessage,
                            "type", type
                        ))));
                }

                if ("completed".equals(status.getStatus())) {
                    imageUrl = status.getImageUrl();
                    logger.info("参考图片生成完成: taskId={}, imageUrl={}", taskId, imageUrl);
                    break;
                } else if ("failed".equals(status.getStatus())) {
                    logger.error("参考图片生成失败: taskId={}, error={}", taskId, status.getError());

                    // 发送失败事件
                    emitter.send(SseEmitter.event()
                        .name("image_progress")
                        .data(objectMapper.writeValueAsString(Map.of(
                            "taskId", taskId,
                            "status", "failed",
                            "progress", currentProgress,
                            "message", "生成失败: " + status.getError(),
                            "type", type
                        ))));

                    return "{\"error\": \"图片生成失败: " + status.getError() + "\", \"errorCode\": \"IMAGE_GENERATION_FAILED\"}";
                }
            }

            if (imageUrl == null) {
                return "{\"error\": \"图片生成超时\", \"errorCode\": \"IMAGE_GENERATION_FAILED\"}";
            }

            // 保存到历史记录
            aiImageHistoryService.saveHistory(prompt, imageUrl);

            // 发送完成事件
            emitter.send(SseEmitter.event()
                .name("image_result")
                .data(objectMapper.writeValueAsString(Map.of(
                    "imageUrl", imageUrl,
                    "type", type,
                    "name", "参考图"
                ))));

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "imageUrl", imageUrl,
                "type", type
            ));
        } catch (Exception e) {
            logger.error("生成参考图片失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    /**
     * 获取章节概括列表
     */
    private String getChapterSummaries(String argumentsJson, CreativeSession session) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            if (novelId == null) {
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            // 获取章节概括
            List<Map<String, Object>> summaries = novelService.getChapterSummaries(novelId);

            logger.info("获取章节概括: novelId={}, count={}", novelId, summaries.size());

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "chapters", summaries
            ));
        } catch (Exception e) {
            logger.error("获取章节概括失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 修改章节工具实现
     */
    private String updateChapter(String argumentsJson, CreativeSession session) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            Long chapterId = args.get("chapterId") != null
                ? ((Number) args.get("chapterId")).longValue()
                : null;

            if (chapterId == null) {
                return "{\"error\": \"请提供章节ID\", \"errorCode\": \"CHAPTER_ID_REQUIRED\"}";
            }

            // 获取修改参数
            String title = (String) args.get("title");
            String content = (String) args.get("content");
            String summary = (String) args.get("summary");

            // 至少需要一个修改项
            if (title == null && content == null && summary == null) {
                return "{\"error\": \"请提供至少一个修改项（标题、内容或概括）\", \"errorCode\": \"NO_UPDATE_FIELD\"}";
            }

            // 调用 NovelService 更新章节
            Chapter chapter = novelService.updateChapter(chapterId, title, content, summary);

            if (chapter == null) {
                return "{\"error\": \"章节不存在\", \"errorCode\": \"CHAPTER_NOT_FOUND\"}";
            }

            logger.info("修改章节成功: chapterId={}, title={}", chapterId, title != null ? title : "未修改");

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "chapterId", chapterId,
                "title", chapter.getTitle(),
                "updatedFields", Map.of(
                    "title", title != null,
                    "content", content != null,
                    "summary", summary != null
                )
            ));
        } catch (Exception e) {
            logger.error("修改章节失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 修改小说工具实现
     */
    private String updateNovel(String argumentsJson, CreativeSession session) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            if (novelId == null) {
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            String worldView = (String) args.get("worldView");

            if (worldView == null || worldView.trim().isEmpty()) {
                return "{\"error\": \"世界观不能为空\"}";
            }

            // 调用 NovelService 更新世界观
            Novel novel = novelService.updateWorldView(novelId, worldView.trim());

            logger.info("修改小说世界观成功: novelId={}", novelId);

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "novelId", novelId,
                "worldView", worldView.trim()
            ));
        } catch (Exception e) {
            logger.error("修改小说失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 生成大纲工具实现
     */
    private String generateOutline(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            logger.info("[大纲生成] 开始生成大纲, sessionId={}, novelId={}", session.getSessionId(), novelId);

            if (novelId == null) {
                logger.warn("[大纲生成] 未指定小说ID, sessionId={}", session.getSessionId());
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            // 获取小说和已收集的参数
            Novel novel = novelService.getNovelById(novelId);
            Map<String, Object> params = parseParams(session.getExtractedParams());

            logger.info("[大纲生成] 小说参数: novelId={}, title={}, params={}", novelId,
                novel.getTitle(), params.keySet());

            // 构建大纲生成提示词
            String outlinePrompt = buildOutlinePrompt(params, novel);

            // 调用 AI 生成大纲
            logger.info("[大纲生成] 调用 DeepSeek API 生成大纲, novelId={}", novelId);
            String outline = callDeepSeekForOutline(outlinePrompt);

            // 检查是否生成成功
            if (outline == null || outline.isEmpty() || outline.equals("大纲生成失败")) {
                logger.error("[大纲生成] AI生成大纲失败: novelId={}, 返回内容为空", novelId);
                return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", "AI生成大纲失败，请重试",
                    "errorCode", "AI_GENERATION_FAILED"
                ));
            }

            // 保存大纲到小说
            novel.setOutline(outline);
            novelService.updateNovel(novel);

            logger.info("[大纲生成] 大纲生成并保存成功: novelId={}, outlineLength={}", novelId, outline.length());

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "outline", outline
            ));
        } catch (Exception e) {
            logger.error("[大纲生成] 生成大纲异常: sessionId={}, error={}", session.getSessionId(), e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 构建大纲生成提示词
     */
    private String buildOutlinePrompt(Map<String, Object> params, Novel novel) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下设定生成小说大纲：\n\n");

        if (params.get("theme") != null) {
            sb.append("- 题材：").append(params.get("theme")).append("\n");
        }
        if (params.get("style") != null) {
            sb.append("- 风格：").append(params.get("style")).append("\n");
        }
        if (params.get("protagonistName") != null) {
            sb.append("- 主角：").append(params.get("protagonistName"));
            if (params.get("protagonistIdentity") != null) {
                sb.append("，").append(params.get("protagonistIdentity"));
            }
            sb.append("\n");
        }
        if (params.get("mainPlot") != null) {
            sb.append("- 故事主线：").append(params.get("mainPlot")).append("\n");
        }
        if (params.get("conflictType") != null) {
            sb.append("- 核心冲突：").append(params.get("conflictType")).append("\n");
        }
        if (params.get("endingType") != null) {
            sb.append("- 结局类型：").append(params.get("endingType")).append("\n");
        }
        if (params.get("chapterCount") != null) {
            sb.append("- 章节数量：").append(params.get("chapterCount")).append("\n");
        }

        if (novel.getWorldView() != null && !novel.getWorldView().isEmpty()) {
            sb.append("\n世界观设定：\n").append(novel.getWorldView()).append("\n");
        }

        sb.append("\n请生成一个结构化大纲，包含：\n");
        sb.append("1. 故事概述（100-200字）\n");
        sb.append("2. 主要剧情线\n");
        sb.append("3. 每个章节的简要说明\n");

        return sb.toString();
    }

    /**
     * 更新大纲工具实现
     */
    private String updateOutline(String argumentsJson, CreativeSession session, List<Map<String, Object>> messages) {
        try {
            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});

            // 获取小说ID
            SessionContext context = getContext(session);
            Long novelId = args.get("novelId") != null
                ? ((Number) args.get("novelId")).longValue()
                : context.getCurrentNovelId();

            if (novelId == null) {
                logger.warn("[大纲更新] 未指定小说ID, sessionId={}", session.getSessionId());
                return "{\"error\": \"请先创建小说\", \"errorCode\": \"NO_NOVEL\"}";
            }

            String outline = (String) args.get("outline");
            if (outline == null || outline.trim().isEmpty()) {
                logger.warn("[大纲更新] 大纲内容为空, novelId={}", novelId);
                return "{\"error\": \"大纲内容不能为空\"}";
            }

            logger.info("[大纲更新] 开始更新大纲, novelId={}, outlineLength={}", novelId, outline.length());

            // 更新大纲
            Novel novel = novelService.getNovelById(novelId);
            novel.setOutline(outline.trim());
            novelService.updateNovel(novel);

            logger.info("[大纲更新] 大纲更新成功: novelId={}, title={}", novelId, novel.getTitle());

            return objectMapper.writeValueAsString(Map.of(
                "success", true,
                "message", "大纲更新成功"
            ));
        } catch (Exception e) {
            logger.error("[大纲更新] 更新大纲异常: sessionId={}, error={}", session.getSessionId(), e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 从消息历史中提取工具调用记录
     */
    private List<Map<String, Object>> extractToolCallHistory(List<Map<String, Object>> messages) {
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (Map<String, Object> msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> calls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (calls != null) {
                    for (Map<String, Object> call : calls) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> function = (Map<String, Object>) call.get("function");
                        if (function != null) {
                            String toolName = (String) function.get("name");
                            if (toolName != null) {
                                Map<String, Object> toolInfo = new LinkedHashMap<>();
                                toolInfo.put("name", toolName);
                                toolInfo.put("displayName", TOOL_NAME_MAP.getOrDefault(toolName, toolName));
                                toolCalls.add(toolInfo);
                            }
                        }
                    }
                }
            }
        }

        return toolCalls;
    }

    /**
     * 从最近消息中推断当前讨论焦点
     */
    private String extractCurrentFocus(List<Map<String, Object>> recentMessages) {
        // 关键词映射
        Map<String, String> keywordFocusMap = new LinkedHashMap<>();
        keywordFocusMap.put("大纲", "大纲讨论");
        keywordFocusMap.put("章节", "章节创作");
        keywordFocusMap.put("角色", "角色设定");
        keywordFocusMap.put("世界观", "世界观设定");
        keywordFocusMap.put("主角", "主角设定");
        keywordFocusMap.put("配角", "配角设定");
        keywordFocusMap.put("风格", "风格确定");
        keywordFocusMap.put("题材", "题材确定");

        // 工具名到焦点的映射
        Map<String, String> toolFocusMap = new LinkedHashMap<>();
        toolFocusMap.put("create_novel", "小说创建");
        toolFocusMap.put("create_character_card", "角色设定");
        toolFocusMap.put("update_character_card", "角色设定");
        toolFocusMap.put("add_chapter", "章节创作");
        toolFocusMap.put("update_chapter", "章节创作");
        toolFocusMap.put("update_novel", "世界观设定");
        toolFocusMap.put("generate_outline", "大纲讨论");
        toolFocusMap.put("update_outline", "大纲讨论");

        // 从最近消息向前遍历
        for (int i = recentMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = recentMessages.get(i);
            String role = (String) msg.get("role");

            // 检查工具调用
            if ("assistant".equals(role)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> function = (Map<String, Object>) toolCalls.get(0).get("function");
                    if (function != null) {
                        String toolName = (String) function.get("name");
                        String focus = toolFocusMap.get(toolName);
                        if (focus != null) {
                            return focus;
                        }
                    }
                }
            }

            // 检查用户消息中的关键词
            if ("user".equals(role)) {
                String content = (String) msg.get("content");
                if (content != null) {
                    for (Map.Entry<String, String> entry : keywordFocusMap.entrySet()) {
                        if (content.contains(entry.getKey())) {
                            return entry.getValue();
                        }
                    }
                }
            }
        }

        return "创作引导";
    }

    /**
     * 构建状态摘要消息（从数据库获取真实数据）
     */
    /**
     * 从消息列表中提取最近的指定数量的消息，确保 tool_calls/tool 消息配对完整。
     * 如果切割点落在 tool 消息上（对应的 assistant+tool_calls 在切割点之前），
     * 则向前扩展切割点以包含该 assistant 消息；
     * 如果切割点落在 assistant+tool_calls 消息和对应的 tool 消息之间，
     * 也向前扩展以保持配对完整。
     * 最后还会清理掉任何孤立的 tool 消息。
     */
    /**
     * 在发送 API 请求前验证并修复消息列表，确保 tool/tool_calls 配对完整。
     * 1. 收集所有 assistant+tool_calls 中的 tool_call_id
     * 2. 移除没有对应 tool_calls 的孤立 tool 消息
     * 3. 清理没有对应 tool 响应的 assistant+tool_calls
     * 4. 确保消息序列角色合法（不能连续两个 assistant 等）
     */
    private void repairMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 第一步：收集所有 assistant+tool_calls 中的 tool_call_id
        Set<String> definedToolCallIds = new LinkedHashSet<>();
        for (Map<String, Object> msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null) {
                    for (Map<String, Object> tc : toolCalls) {
                        String id = (String) tc.get("id");
                        if (id != null && !id.isEmpty()) {
                            definedToolCallIds.add(id);
                        }
                    }
                }
            }
        }

        // 第二步：移除没有对应 tool_calls 的孤立 tool 消息
        int removedTools = 0;
        Iterator<Map<String, Object>> it = messages.iterator();
        while (it.hasNext()) {
            Map<String, Object> msg = it.next();
            if ("tool".equals(msg.get("role"))) {
                String toolCallId = (String) msg.get("tool_call_id");
                if (toolCallId == null || !definedToolCallIds.contains(toolCallId)) {
                    logger.warn("修复消息：移除孤立 tool 消息, tool_call_id={}", toolCallId);
                    it.remove();
                    removedTools++;
                }
            }
        }

        // 第三步：收集所有有响应的 tool_call_id
        Set<String> respondedToolCallIds = new HashSet<>();
        for (Map<String, Object> msg : messages) {
            if ("tool".equals(msg.get("role"))) {
                String toolCallId = (String) msg.get("tool_call_id");
                if (toolCallId != null) {
                    respondedToolCallIds.add(toolCallId);
                }
            }
        }

        // 第四步：清理没有对应 tool 响应的 assistant+tool_calls
        for (Map<String, Object> msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    boolean hasAnyResponse = toolCalls.stream()
                        .anyMatch(tc -> respondedToolCallIds.contains(tc.get("id")));
                    if (!hasAnyResponse) {
                        logger.warn("修复消息：移除无响应的 tool_calls 字段");
                        msg.remove("tool_calls");
                    } else {
                        toolCalls.removeIf(tc -> !respondedToolCallIds.contains(tc.get("id")));
                    }
                }
            }
        }

        // 第五步：确保消息序列合法 - tool 消息必须紧跟在带 tool_calls 的 assistant 消息之后
        // 如果 tool 消息和其对应的 assistant 消息之间被其他消息隔开，需要重排
        // 收集 assistant+tool_calls 的索引及其对应 tool 消息的索引
        List<Integer> toRemove = new ArrayList<>();
        Set<String> seenToolCallIds = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    for (Map<String, Object> tc : toolCalls) {
                        seenToolCallIds.add((String) tc.get("id"));
                    }
                    // 检查紧跟的下一条消息是否是 tool 消息
                    if (i + 1 < messages.size()) {
                        Map<String, Object> nextMsg = messages.get(i + 1);
                        if (!"tool".equals(nextMsg.get("role"))) {
                            logger.warn("修复消息：assistant+tool_calls 后未紧跟 tool 消息，移除 tool_calls");
                            msg.remove("tool_calls");
                        }
                    } else {
                        // assistant+tool_calls 是最后一条消息，没有 tool 响应
                        logger.warn("修复消息：assistant+tool_calls 是最后一条消息，移除 tool_calls");
                        msg.remove("tool_calls");
                    }
                }
            }
        }

        // 第六步：重新验证 - 再次检查是否还有孤立 tool 消息
        definedToolCallIds.clear();
        for (Map<String, Object> msg : messages) {
            if ("assistant".equals(msg.get("role"))) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null) {
                    for (Map<String, Object> tc : toolCalls) {
                        String id = (String) tc.get("id");
                        if (id != null) definedToolCallIds.add(id);
                    }
                }
            }
        }
        Iterator<Map<String, Object>> it2 = messages.iterator();
        while (it2.hasNext()) {
            Map<String, Object> msg = it2.next();
            if ("tool".equals(msg.get("role"))) {
                String toolCallId = (String) msg.get("tool_call_id");
                if (toolCallId == null || !definedToolCallIds.contains(toolCallId)) {
                    logger.warn("修复消息（二次检查）：移除孤立 tool 消息, tool_call_id={}", toolCallId);
                    it2.remove();
                    removedTools++;
                }
            }
        }

        if (removedTools > 0) {
            logger.info("消息修复完成：共移除 {} 条孤立 tool 消息", removedTools);
        }
    }

    private List<Map<String, Object>> extractRecentMessages(List<Map<String, Object>> messages, int keepMessageCount) {
        int startIndex = messages.size() - keepMessageCount;

        // 向前调整切割点，确保不在 tool_calls/tool 配对中间切割
        // 如果切割点落在 tool 消息上，需要向前找到对应的 assistant+tool_calls 消息
        if (startIndex > 0) {
            String startRole = (String) messages.get(startIndex).get("role");
            if ("tool".equals(startRole)) {
                // 向前查找对应的 assistant 消息（带 tool_calls）
                String toolCallId = (String) messages.get(startIndex).get("tool_call_id");
                for (int i = startIndex - 1; i >= 1; i--) {
                    Map<String, Object> msg = messages.get(i);
                    if ("assistant".equals(msg.get("role"))) {
                        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                        if (toolCalls != null) {
                            // 检查这个 assistant 消息是否包含对应的 tool_call_id
                            for (Map<String, Object> tc : toolCalls) {
                                if (toolCallId != null && toolCallId.equals(tc.get("id"))) {
                                    startIndex = i;
                                    break;
                                }
                            }
                        }
                        break; // assistant 消息只找最近的一个
                    } else if ("tool".equals(msg.get("role"))) {
                        continue; // 跳过其他 tool 消息
                    } else {
                        break; // 遇到 user 消息，停止
                    }
                }
            }
        }

        List<Map<String, Object>> recentMessages = new ArrayList<>(messages.subList(startIndex, messages.size()));

        // 清理孤立的 tool 消息：检查每个 tool 消息前面是否有对应的 assistant+tool_calls
        Set<String> availableToolCallIds = new HashSet<>();
        for (int i = 0; i < recentMessages.size(); i++) {
            Map<String, Object> msg = recentMessages.get(i);
            if ("assistant".equals(msg.get("role"))) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null) {
                    for (Map<String, Object> tc : toolCalls) {
                        String id = (String) tc.get("id");
                        if (id != null) {
                            availableToolCallIds.add(id);
                        }
                    }
                }
            }
        }

        // 移除没有对应 tool_calls 的 tool 消息
        recentMessages.removeIf(msg -> {
            if ("tool".equals(msg.get("role"))) {
                String toolCallId = (String) msg.get("tool_call_id");
                return toolCallId == null || !availableToolCallIds.contains(toolCallId);
            }
            return false;
        });

        // 清理没有对应 tool 响应的 assistant+tool_calls 消息中的 tool_calls
        Set<String> respondedToolCallIds = new HashSet<>();
        for (Map<String, Object> msg : recentMessages) {
            if ("tool".equals(msg.get("role"))) {
                String toolCallId = (String) msg.get("tool_call_id");
                if (toolCallId != null) {
                    respondedToolCallIds.add(toolCallId);
                }
            }
        }
        for (Map<String, Object> msg : recentMessages) {
            if ("assistant".equals(msg.get("role"))) {
                List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    // 如果没有任何 tool_call 得到响应，移除整个 tool_calls 字段
                    boolean hasAnyResponse = toolCalls.stream()
                        .anyMatch(tc -> respondedToolCallIds.contains(tc.get("id")));
                    if (!hasAnyResponse) {
                        msg.remove("tool_calls");
                    } else {
                        // 只保留有响应的 tool_calls
                        toolCalls.removeIf(tc -> !respondedToolCallIds.contains(tc.get("id")));
                    }
                }
            }
        }

        logger.info("提取最近消息: startIndex={}, 实际保留消息数={}", startIndex, recentMessages.size());
        return recentMessages;
    }

    /**
     * 调用DeepSeek对旧对话生成讨论历程摘要
     * @param oldMessages 即将被丢弃的旧消息列表
     * @return AI生成的讨论摘要，调用失败时返回null
     */
    private String generateDiscussionSummary(List<Map<String, Object>> oldMessages) {
        try {
            if (oldMessages == null || oldMessages.isEmpty()) {
                return null;
            }

            // 将旧消息拼接为文本
            StringBuilder conversationText = new StringBuilder();
            for (Map<String, Object> msg : oldMessages) {
                String role = (String) msg.get("role");
                if ("system".equals(role)) continue; // 跳过系统消息
                String msgContent = (String) msg.get("content");
                if (msgContent == null || msgContent.isEmpty()) {
                    // tool_calls消息可能没有content，跳过
                    if (msg.get("tool_calls") != null) continue;
                    if (msg.get("tool_call_id") != null) {
                        // tool响应消息，提取简要内容
                        msgContent = "[工具响应]";
                    } else {
                        continue;
                    }
                }
                // 截断过长的单条消息，避免prompt过长
                if (msgContent.length() > 500) {
                    msgContent = msgContent.substring(0, 500) + "...(已截断)";
                }
                String roleLabel = switch (role) {
                    case "user" -> "用户";
                    case "assistant" -> "AI";
                    case "tool" -> "工具";
                    default -> role;
                };
                conversationText.append(roleLabel).append("：").append(msgContent).append("\n\n");
            }

            if (conversationText.length() == 0) {
                return null;
            }

            // 限制总输入长度，避免超出token限制
            if (conversationText.length() > 8000) {
                conversationText.setLength(8000);
                conversationText.append("\n\n...(对话过长，仅保留前部分)");
            }

            String systemPrompt = """
                你是一位创作对话摘要助手。以下是一段即将被压缩的AI与用户的创作对话记录。
                请从中提取对后续创作至关重要的信息，生成一段简洁摘要。

                重点关注：
                1. 用户明确表达的偏好或忌讳（如"不喜欢虐心"、"想要轻松风格"）
                2. 被否决的方案及否决原因（如"尝试过暗黑风格但用户拒绝"）
                3. 重要的创作决策及其背景（如"从仙侠改为都市，因为用户更熟悉职场题材"）
                4. 尚未解决的分歧或待定事项
                5. 用户提及的现实参考（如"参考某部作品的氛围"）

                忽略：
                - 已在系统参数中记录的硬数据（题材、风格、角色属性等，这些会由其他机制保留）
                - 闲聊和重复讨论
                - 工具调用的技术细节，只保留其结果带来的影响

                要求：
                - 200-300字
                - 用条目式写法，每条一个要点
                - 只记录事实和偏好，不要推测或评价
                - 如果对话内容较少，摘要可以更短
                """;

            logger.info("[讨论摘要AI调用] 开始生成讨论历程摘要, 对话文本长度={}", conversationText.length());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + deepSeekConfig.getApiKey());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", deepSeekConfig.getModel());
            requestBody.put("max_tokens", 500);
            requestBody.put("messages", new Object[]{
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", "请为以下对话生成摘要：\n\n" + conversationText.toString())
            });

            String requestBodyStr = objectMapper.writeValueAsString(requestBody);

            String response = restTemplate.execute(
                    deepSeekConfig.getApiUrl(),
                    org.springframework.http.HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().set("Authorization", "Bearer " + deepSeekConfig.getApiKey());
                        request.getBody().write(requestBodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    },
                    responseEntity -> {
                        String body = new String(responseEntity.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        JsonNode jsonNode = objectMapper.readTree(body);
                        JsonNode choices = jsonNode.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            String content = choices.get(0).path("message").path("content").asText();
                            // 清理思考标签
                            if (content != null && !content.isEmpty()) {
                                content = THINK_TAG_PATTERN.matcher(content).replaceAll("").trim();
                            }
                            return content;
                        }
                        return null;
                    }
            );

            if (response != null && !response.isEmpty()) {
                logger.info("[讨论摘要AI调用] 摘要生成成功, 长度={}", response.length());
                return response;
            }

            logger.warn("[讨论摘要AI调用] API返回空内容");
            return null;

        } catch (Exception e) {
            logger.error("[讨论摘要AI调用] 生成讨论摘要失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private Map<String, Object> buildStateSummaryMessage(CreativeSession session, List<Map<String, Object>> allMessages, List<Map<String, Object>> recentMessages, String discussionSummary) {
        StringBuilder content = new StringBuilder();
        content.append("[创作状态摘要]\n\n");

        // 0. 讨论历程摘要（AI生成）
        if (discussionSummary != null && !discussionSummary.isEmpty()) {
            content.append("## 讨论历程摘要\n");
            content.append(discussionSummary).append("\n\n");
        }

        // 1. 已确认参数
        Map<String, Object> params = parseParams(session.getExtractedParams());
        content.append(buildParamsSection(params));

        // 2. 角色卡和世界观（需要小说ID）
        SessionContext context = getContext(session);
        Long novelId = context.getCurrentNovelId();

        if (novelId != null) {
            // 角色卡
            content.append(buildCharacterCardsSection(novelId));

            // 世界观
            content.append(buildWorldViewSection(novelId));

            // 小说标题
            content.append(buildNovelTitleSection(novelId));
        }

        // 3. 创作进度
        content.append(buildProgressSection(allMessages, recentMessages));

        // 4. 用户偏好
        content.append(buildUserPreferencesSection());

        return Map.of("role", "system", "content", content.toString());
    }

    /**
     * 构建已确认参数部分
     */
    private String buildParamsSection(Map<String, Object> params) {
        if (params.isEmpty()) {
            return "## 已确认参数\n暂无已确认参数\n\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 已确认参数\n");

        // 参数显示名称映射
        Map<String, String> paramNameMap = new LinkedHashMap<>();
        paramNameMap.put("theme", "题材");
        paramNameMap.put("style", "风格");
        paramNameMap.put("protagonistName", "主角姓名");
        paramNameMap.put("protagonistGender", "主角性别");
        paramNameMap.put("protagonistIdentity", "主角身份");
        paramNameMap.put("mainPlot", "故事主线");
        paramNameMap.put("conflictType", "核心冲突");
        paramNameMap.put("endingType", "结局类型");
        paramNameMap.put("chapterCount", "章节数量");
        paramNameMap.put("wordsPerChapter", "每章字数");
        paramNameMap.put("pointOfView", "叙述视角");
        paramNameMap.put("languageStyle", "语言风格");

        boolean hasParams = false;
        for (Map.Entry<String, String> entry : paramNameMap.entrySet()) {
            Object value = params.get(entry.getKey());
            if (value != null && !value.toString().isEmpty()) {
                sb.append("- ").append(entry.getValue()).append("：");
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<?> list = (List<?>) value;
                    sb.append(list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("、")));
                } else {
                    sb.append(value);
                }
                sb.append("\n");
                hasParams = true;
            }
        }

        if (!hasParams) {
            sb.append("暂无已确认参数\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建角色卡部分
     */
    private String buildCharacterCardsSection(Long novelId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 已创建角色卡\n");

        try {
            List<CharacterCard> cards = characterCardService.getCharacterCardsByNovelId(novelId);
            if (cards == null || cards.isEmpty()) {
                sb.append("暂无角色卡\n\n");
                return sb.toString();
            }

            for (CharacterCard card : cards) {
                String roleType = "protagonist".equals(card.getRole()) ? "主角" : "配角";
                sb.append("- ").append(card.getName()).append("（").append(roleType).append("）");

                // 添加简要信息
                List<String> details = new ArrayList<>();
                if (card.getAge() != null) {
                    details.add(card.getAge() + "岁");
                }
                if (card.getGender() != null && !card.getGender().isEmpty()) {
                    details.add(card.getGender());
                }
                if (card.getOccupation() != null && !card.getOccupation().isEmpty()) {
                    details.add(card.getOccupation());
                }

                if (!details.isEmpty()) {
                    sb.append("：").append(String.join("，", details));
                }

                // 性格简介
                if (card.getPersonality() != null && !card.getPersonality().isEmpty()) {
                    String personality = card.getPersonality();
                    if (personality.length() > 50) {
                        personality = personality.substring(0, 50) + "...";
                    }
                    sb.append("，").append(personality);
                }

                sb.append("\n");
            }
        } catch (Exception e) {
            logger.warn("获取角色卡失败: {}", e.getMessage());
            sb.append("暂无角色卡\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建世界观部分
     */
    private String buildWorldViewSection(Long novelId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 世界观\n");

        try {
            Novel novel = novelService.getNovelById(novelId);
            if (novel != null && novel.getWorldView() != null && !novel.getWorldView().isEmpty()) {
                String worldView = novel.getWorldView();
                if (worldView.length() > 300) {
                    worldView = worldView.substring(0, 300) + "...";
                }
                sb.append(worldView).append("\n");
            } else {
                sb.append("暂无世界观设定\n");
            }
        } catch (Exception e) {
            logger.warn("获取世界观失败: {}", e.getMessage());
            sb.append("暂无世界观设定\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建小说标题部分
     */
    private String buildNovelTitleSection(Long novelId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 当前小说\n");

        try {
            Novel novel = novelService.getNovelById(novelId);
            if (novel != null) {
                sb.append("《").append(novel.getTitle()).append("》\n");
            }
        } catch (Exception e) {
            logger.warn("获取小说标题失败: {}", e.getMessage());
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建创作进度部分
     */
    private String buildProgressSection(List<Map<String, Object>> allMessages, List<Map<String, Object>> recentMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 创作进度\n");

        List<Map<String, Object>> toolHistory = extractToolCallHistory(allMessages);

        if (toolHistory.isEmpty()) {
            sb.append("创作尚未开始\n\n");
            return sb.toString();
        }

        // 统计各类操作
        int novelCount = 0;
        int chapterCount = 0;
        int characterCount = 0;

        Set<String> uniqueTools = new LinkedHashSet<>();
        for (Map<String, Object> tool : toolHistory) {
            String name = (String) tool.get("name");
            uniqueTools.add((String) tool.get("displayName"));

            if ("create_novel".equals(name)) novelCount++;
            if ("add_chapter".equals(name)) chapterCount++;
            if ("create_character_card".equals(name)) characterCount++;
        }

        if (novelCount > 0) {
            sb.append("- 已创建小说\n");
        }
        if (characterCount > 0) {
            sb.append("- 已创建 ").append(characterCount).append(" 个角色卡\n");
        }
        if (chapterCount > 0) {
            sb.append("- 已添加 ").append(chapterCount).append(" 个章节\n");
        }

        // 当前讨论焦点
        String currentFocus = extractCurrentFocus(recentMessages);
        sb.append("- 当前讨论焦点：").append(currentFocus).append("\n");

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建用户偏好部分
     */
    private String buildUserPreferencesSection() {
        StringBuilder sb = new StringBuilder();

        try {
            List<CreativeMemory> memories = memoryRepository.findBySessionIdIsNullOrderByUpdateTimeDesc();
            if (memories != null && !memories.isEmpty()) {
                sb.append("## 用户偏好\n");
                for (CreativeMemory memory : memories) {
                    sb.append("- ").append(memory.getValue()).append("\n");
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            logger.warn("获取用户偏好失败: {}", e.getMessage());
        }

        return sb.toString();
    }

    /**
     * 生成状态摘要并压缩对话（当对话过长时）
     * 使用结构化数据 + AI讨论历程摘要
     */
    private void generateAndInsertSummary(List<Map<String, Object>> messages, SseEmitter emitter, int turnsSummarized, CreativeSession session) {
        try {
            int messagesBefore = messages.size();

            // 发送SSE事件通知前端：压缩开始
            try {
                emitter.send(SseEmitter.event()
                        .name("compression_start")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "message", "正在压缩对话历史，生成AI摘要中...",
                                "turnsSummarized", turnsSummarized
                        ))));
            } catch (Exception e) {
                logger.debug("发送compression_start事件失败: {}", e.getMessage());
            }

            // 保留第一条（系统提示词）
            Map<String, Object> systemMessage = messages.get(0);

            // 计算需要保留的最近消息数量（5轮对话 = 10条消息）
            int keepMessageCount = KEEP_RECENT_TURNS * 2;

            // 获取最近的消息（不压缩），确保 tool_calls/tool 消息配对完整
            List<Map<String, Object>> recentMessages = new ArrayList<>();
            if (messages.size() > keepMessageCount) {
                recentMessages = extractRecentMessages(messages, keepMessageCount);
            } else {
                // 消息数量不足，不进行压缩
                logger.info("消息数量不足，跳过压缩: {} <= {}", messages.size(), keepMessageCount);
                return;
            }

            // 提取即将被丢弃的旧消息
            int recentStartIndex = messages.size() - recentMessages.size();
            List<Map<String, Object>> oldMessages = new ArrayList<>(messages.subList(1, recentStartIndex));

            // 调用AI生成讨论历程摘要（降级：失败时返回null）
            String discussionSummary = generateDiscussionSummary(oldMessages);

            // 构建状态摘要（含AI讨论摘要）
            Map<String, Object> stateSummary = buildStateSummaryMessage(session, messages, recentMessages, discussionSummary);

            // 替换消息历史
            messages.clear();
            messages.add(systemMessage);
            messages.add(stateSummary);
            messages.addAll(recentMessages);

            int messagesAfter = messages.size();

            // 详细日志
            logger.info("========== 对话压缩完成 ==========");
            logger.info("压缩前消息数: {}", messagesBefore);
            logger.info("压缩后消息数: {}", messagesAfter);
            logger.info("保留最近轮数: {}", KEEP_RECENT_TURNS);
            logger.info("压缩对话轮数: {}", turnsSummarized);
            logger.info("AI讨论摘要: {}", discussionSummary != null ? "已生成" : "未生成");
            logger.info("状态摘要内容:\n{}", stateSummary.get("content"));
            logger.info("==================================");

            // 发送 SSE 事件通知前端
            emitter.send(SseEmitter.event()
                    .name("summary_generated")
                    .data(objectMapper.writeValueAsString(Map.of(
                            "message", "对话过长，已压缩历史记录",
                            "turnsSummarized", turnsSummarized,
                            "compressionType", "hybrid",
                            "discussionSummaryGenerated", discussionSummary != null
                    ))));

        } catch (Exception e) {
            logger.error("生成状态摘要失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 调用 DeepSeek 生成大纲
     */
    private String callDeepSeekForOutline(String prompt) {
        try {
            logger.info("[大纲AI调用] 开始调用 DeepSeek API 生成大纲, promptLength={}", prompt.length());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + deepSeekConfig.getApiKey());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", deepSeekConfig.getModel());
            requestBody.put("max_tokens", 4096);
            requestBody.put("messages", new Object[]{
                    Map.of("role", "system", "content", "你是一位专业的小说大纲设计师。请根据给定的设定生成结构化的小说大纲。"),
                    Map.of("role", "user", "content", prompt)
            });

            String requestBodyStr = objectMapper.writeValueAsString(requestBody);

            logger.info("[大纲AI调用] 发送请求到 DeepSeek API, model={}", deepSeekConfig.getModel());

            String response = restTemplate.execute(
                    deepSeekConfig.getApiUrl(),
                    org.springframework.http.HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        request.getHeaders().set("Authorization", "Bearer " + deepSeekConfig.getApiKey());
                        request.getBody().write(requestBodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    },
                    responseEntity -> {
                        String body = new String(responseEntity.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        JsonNode jsonNode = objectMapper.readTree(body);
                        JsonNode choices = jsonNode.path("choices");
                        if (choices.isArray() && choices.size() > 0) {
                            String result = choices.get(0).path("message").path("content").asText();
                            logger.info("[大纲AI调用] DeepSeek API 响应成功, responseLength={}", result.length());
                            return result;
                        }
                        logger.warn("[大纲AI调用] DeepSeek API 响应无有效内容: {}", body);
                        return "大纲生成失败";
                    }
            );

            return response != null ? response : "大纲生成失败";
        } catch (Exception e) {
            logger.error("[大纲AI调用] 调用 DeepSeek API 异常: {}", e.getMessage(), e);
            return "大纲生成失败";
        }
    }

    /**
     * 统计用户对话轮数
     */
    private int countUserTurns(List<Map<String, Object>> messages) {
        int count = 0;
        for (Map<String, Object> msg : messages) {
            if ("user".equals(msg.get("role"))) {
                count++;
            }
        }
        return count;
    }

    // ==================== 记忆管理 ====================

    /**
     * 获取所有记忆
     */
    public List<CreativeMemory> getAllMemories() {
        return memoryRepository.findAllByOrderByUpdateTimeDesc();
    }

    /**
     * 获取特定会话的记忆（包含全局记忆和会话记忆）
     * 会话记忆优先级高于全局记忆
     */
    public List<CreativeMemory> getMemoriesForSession(String sessionId) {
        return memoryRepository.findBySessionIdOrSessionIdIsNullOrderByUpdateTimeDesc(sessionId);
    }

    /**
     * 获取全局记忆列表
     */
    public List<CreativeMemory> getGlobalMemories() {
        return memoryRepository.findBySessionIdIsNullOrderByUpdateTimeDesc();
    }

    /**
     * 删除全局记忆
     */
    @Transactional
    public void deleteGlobalMemory(String key) {
        memoryRepository.deleteByKey(key);
        logger.info("删除全局记忆: {}", key);
    }

    /**
     * 构建包含用户记忆的系统提示词
     * @param sessionId 会话ID，用于加载会话特定记忆
     */
    private String buildSystemPromptWithMemories(String sessionId) {
        StringBuilder prompt = new StringBuilder(SYSTEM_PROMPT_BASE);

        // 获取全局记忆和会话记忆
        List<CreativeMemory> globalMemories = memoryRepository.findBySessionIdIsNullOrderByUpdateTimeDesc();
        List<CreativeMemory> sessionMemories = sessionId != null
            ? memoryRepository.findBySessionIdOrderByUpdateTimeDesc(sessionId)
            : List.of();

        // 合并记忆：会话记忆覆盖全局记忆
        Map<String, CreativeMemory> memoryMap = new LinkedHashMap<>();

        // 先添加全局记忆
        for (CreativeMemory memory : globalMemories) {
            memoryMap.put(memory.getKey(), memory);
        }

        // 再添加会话记忆（会覆盖同 key 的全局记忆）
        for (CreativeMemory memory : sessionMemories) {
            memoryMap.put(memory.getKey(), memory);
        }

        if (!memoryMap.isEmpty()) {
            prompt.append("\n\n## 用户偏好记忆\n");
            prompt.append("以下是用户之前保存的偏好，请在引导时参考：\n\n");

            for (CreativeMemory memory : memoryMap.values()) {
                String scope = memory.getSessionId() != null ? "[会话]" : "[全局]";
                prompt.append("- ").append(scope).append(" ").append(memory.getKey())
                      .append("：").append(memory.getValue()).append("\n");
            }
        }

        // 追加写作质量提示词（配置了自定义则用自定义，否则用默认）
        prompt.append("\n\n").append(getWritingQualityPrompt());

        return prompt.toString();
    }

    /**
     * 刷新消息列表中系统消息的记忆部分
     * 每次对话请求前调用，确保记忆始终是最新的
     */
    @SuppressWarnings("unchecked")
    private void refreshMemoriesInSystemMessage(List<Map<String, Object>> messages, String sessionId) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // 查找系统消息（通常是第一条）
        int systemMessageIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("system".equals(messages.get(i).get("role"))) {
                systemMessageIndex = i;
                break;
            }
        }

        if (systemMessageIndex == -1) {
            // 没有系统消息，创建一个
            String newSystemPrompt = buildSystemPromptWithMemories(sessionId);
            messages.add(0, Map.of("role", "system", "content", newSystemPrompt));
            logger.debug("创建新的系统消息（含记忆）");
            return;
        }

        // 构建新的系统提示词
        String newSystemPrompt = buildSystemPromptWithMemories(sessionId);

        // 更新系统消息
        Map<String, Object> newSystemMessage = new LinkedHashMap<>();
        newSystemMessage.put("role", "system");
        newSystemMessage.put("content", newSystemPrompt);
        messages.set(systemMessageIndex, newSystemMessage);

        logger.debug("已刷新系统消息中的记忆部分，sessionId: {}", sessionId);
    }

    /**
     * 获取创作参数（用于前端填充表单）
     */
    public NovelGeneratorRequest getGeneratorRequest(String sessionId) {
        CreativeSession session = getSession(sessionId);
        return getGeneratorRequest(session);
    }

    /**
     * 获取创作参数（内部方法）
     */
    @SuppressWarnings("unchecked")
    private NovelGeneratorRequest getGeneratorRequest(CreativeSession session) {
        Map<String, Object> params = parseParams(session.getExtractedParams());

        NovelGeneratorRequest request = new NovelGeneratorRequest();

        // 构建 keyword（故事大纲）
        StringBuilder keyword = new StringBuilder();
        if (params.get("theme") != null) {
            keyword.append("题材：").append(params.get("theme")).append("。");
        }
        if (params.get("style") != null) {
            keyword.append("风格：").append(params.get("style")).append("。");
        }
        if (params.get("protagonistName") != null) {
            keyword.append("主角：").append(params.get("protagonistName"));
            if (params.get("protagonistGender") != null) {
                keyword.append("（").append(params.get("protagonistGender")).append("）");
            }
            if (params.get("protagonistIdentity") != null) {
                keyword.append("，身份：").append(params.get("protagonistIdentity"));
            }
            keyword.append("。");
        }
        if (params.get("mainPlot") != null) {
            keyword.append("主线：").append(params.get("mainPlot")).append("。");
        }
        if (params.get("conflictType") != null) {
            keyword.append("冲突：").append(params.get("conflictType")).append("。");
        }
        if (params.get("endingType") != null) {
            keyword.append("结局：").append(params.get("endingType")).append("。");
        }

        request.setKeyword(keyword.toString());

        // 设置其他参数
        if (params.get("chapterCount") != null) {
            request.setChapterCount(((Number) params.get("chapterCount")).intValue());
        }
        if (params.get("wordsPerChapter") != null) {
            request.setWordsPerChapter(((Number) params.get("wordsPerChapter")).intValue());
        }
        if (params.get("genre") != null || params.get("theme") != null) {
            request.setGenre(params.get("genre") != null ? (String) params.get("genre") : (String) params.get("theme"));
        }
        if (params.get("protagonistName") != null) {
            request.setProtagonist((String) params.get("protagonistName"));
        }
        if (params.get("languageStyle") != null) {
            request.setLanguageStyle((String) params.get("languageStyle"));
        }
        if (params.get("pointOfView") != null) {
            request.setPointOfView((String) params.get("pointOfView"));
        }

        return request;
    }

    // ==================== 工具定义 ====================

    /**
     * 获取引导工具定义
     */
    private List<Map<String, Object>> getGuidanceTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // preview_params 工具
        tools.add(createTool("preview_params", "展示当前对话中已提取的所有创作参数", Map.of()));

        // fill_params 工具
        Map<String, Object> fillParamsProps = new LinkedHashMap<>();
        fillParamsProps.put("theme", Map.of("type", "string", "description", "题材"));
        fillParamsProps.put("style", Map.of("type", "string", "description", "风格"));
        fillParamsProps.put("protagonistName", Map.of("type", "string", "description", "主角姓名"));
        fillParamsProps.put("protagonistGender", Map.of("type", "string", "description", "主角性别"));
        fillParamsProps.put("protagonistIdentity", Map.of("type", "string", "description", "主角身份"));
        fillParamsProps.put("mainPlot", Map.of("type", "string", "description", "故事主线"));
        fillParamsProps.put("conflictType", Map.of("type", "string", "description", "核心冲突"));
        fillParamsProps.put("endingType", Map.of("type", "string", "description", "结局类型"));
        fillParamsProps.put("chapterCount", Map.of("type", "integer", "description", "章节数量"));
        fillParamsProps.put("wordsPerChapter", Map.of("type", "integer", "description", "每章字数"));
        fillParamsProps.put("pointOfView", Map.of("type", "string", "description", "叙述视角"));
        fillParamsProps.put("languageStyle", Map.of("type", "string", "description", "语言风格"));
        tools.add(createTool("fill_params", "更新创作参数。当用户明确选择或确认某个参数值时调用。", fillParamsProps));

        // add_memory 工具
        Map<String, Object> addMemoryProps = new LinkedHashMap<>();
        addMemoryProps.put("key", Map.of("type", "string", "description", "记忆类型，如 preferred_style, preferred_genre"));
        addMemoryProps.put("value", Map.of("type", "string", "description", "记忆内容"));
        addMemoryProps.put("scope", Map.of("type", "string", "description", "记忆范围：global（全局，跨会话共享）或 session（仅当前会话）", "enum", Arrays.asList("global", "session")));
        tools.add(createTool("add_memory", "添加记忆。仅当用户明确要求「记住」「保存偏好」时调用。默认保存为全局记忆，用户指定「本次会话」时保存为会话记忆。", addMemoryProps, Arrays.asList("key", "value")));

        // remove_memory 工具
        Map<String, Object> removeMemoryProps = new LinkedHashMap<>();
        removeMemoryProps.put("key", Map.of("type", "string", "description", "要删除的记忆类型"));
        removeMemoryProps.put("scope", Map.of("type", "string", "description", "记忆范围：global（全局）或 session（会话）", "enum", Arrays.asList("global", "session")));
        tools.add(createTool("remove_memory", "删除记忆。当用户要求「忘记」「删除偏好」时调用。默认删除全局记忆。", removeMemoryProps, Arrays.asList("key")));

        // show_examples 工具
        Map<String, Object> showExamplesProps = new LinkedHashMap<>();
        showExamplesProps.put("genre", Map.of("type", "string", "description", "题材类型"));
        tools.add(createTool("show_examples", "展示相似题材的作品片段作为参考", showExamplesProps, Arrays.asList("genre")));

        // ========== 新增创作工具 ==========

        // create_novel 工具
        Map<String, Object> createNovelProps = new LinkedHashMap<>();
        createNovelProps.put("title", Map.of("type", "string", "description", "小说标题"));
        tools.add(createTool("create_novel", "创建一部新小说。当用户明确提供小说标题时调用。", createNovelProps, Arrays.asList("title")));

        // add_chapter 工具（子Agent模式：只传标题+概括，异步生成内容）
        Map<String, Object> addChapterProps = new LinkedHashMap<>();
        addChapterProps.put("novelId", Map.of("type", "integer", "description", "小说ID，不填则使用当前会话的小说"));
        addChapterProps.put("title", Map.of("type", "string", "description", "章节标题"));
        addChapterProps.put("summary", Map.of("type", "string", "description", "章节概括（100-200字），描述本章的核心剧情、内容走向和写作要求。用户的个性化写作要求也写在这里"));
        addChapterProps.put("afterChapterId", Map.of("type", "integer", "description", "插入到指定章节之后"));
        tools.add(createTool("add_chapter", "启动章节异步生成任务。提供标题和概括，系统将在后台自动生成完整的章节内容。需要先创建小说且主角角色卡已存在。调用前必须先向用户确认。", addChapterProps, Arrays.asList("title", "summary")));

        // update_chapter 工具
        Map<String, Object> updateChapterProps = new LinkedHashMap<>();
        updateChapterProps.put("chapterId", Map.of("type", "integer", "description", "章节ID"));
        updateChapterProps.put("title", Map.of("type", "string", "description", "新章节标题"));
        updateChapterProps.put("content", Map.of("type", "string", "description", "新章节内容"));
        updateChapterProps.put("summary", Map.of("type", "string", "description", "新章节概括（100-200字）"));
        tools.add(createTool("update_chapter", "修改已有章节的标题、内容或概括。调用前必须先向用户确认。", updateChapterProps, Arrays.asList("chapterId")));

        // update_novel 工具
        Map<String, Object> updateNovelProps = new LinkedHashMap<>();
        updateNovelProps.put("novelId", Map.of("type", "integer", "description", "小说ID，不填则使用当前会话的小说"));
        updateNovelProps.put("worldView", Map.of("type", "string", "description", "小说世界观/概述"));
        tools.add(createTool("update_novel", "修改小说的世界观或概述。", updateNovelProps, Collections.emptyList()));

        // generate_outline 工具
        Map<String, Object> genOutlineProps = new LinkedHashMap<>();
        genOutlineProps.put("novelId", Map.of("type", "integer", "description", "小说ID，不填则使用当前会话的小说"));
        tools.add(createTool("generate_outline", "根据已收集的参数生成小说大纲。用户要求生成大纲时调用。", genOutlineProps, Collections.emptyList()));

        // update_outline 工具
        Map<String, Object> updateOutlineProps = new LinkedHashMap<>();
        updateOutlineProps.put("novelId", Map.of("type", "integer", "description", "小说ID，不填则使用当前会话的小说"));
        updateOutlineProps.put("outline", Map.of("type", "string", "description", "新的大纲内容"));
        tools.add(createTool("update_outline", "更新小说大纲。用户要求修改大纲时调用。", updateOutlineProps, Arrays.asList("outline")));

        // list_character_cards 工具
        Map<String, Object> listCharProps = new LinkedHashMap<>();
        listCharProps.put("novelId", Map.of("type", "integer", "description", "小说ID，不填则使用当前会话的小说"));
        tools.add(createTool("list_character_cards", "查询小说的已有角色卡列表。创建角色前必须先调用此工具查看现有角色，避免重复创建。返回角色ID、姓名、类型等信息。", listCharProps, Collections.emptyList()));

        // create_character_card 工具
        Map<String, Object> createCharProps = new LinkedHashMap<>();
        createCharProps.put("novelId", Map.of("type", "integer", "description", "小说ID"));
        createCharProps.put("name", Map.of("type", "string", "description", "角色姓名"));
        createCharProps.put("role", Map.of("type", "string", "description", "角色定位：protagonist(主角)/supporting(配角)/antagonist(反派)。注意：每部小说只能有一个主角"));
        createCharProps.put("age", Map.of("type", "integer", "description", "年龄"));
        createCharProps.put("gender", Map.of("type", "string", "description", "性别"));
        createCharProps.put("occupation", Map.of("type", "string", "description", "职业/身份"));
        createCharProps.put("description", Map.of("type", "string", "description", "角色背景描述"));
        // 结构化外貌字段
        Map<String, Object> appearanceProps = new LinkedHashMap<>();
        appearanceProps.put("height", Map.of("type", "string", "description", "身高，如：168cm"));
        appearanceProps.put("hair", Map.of("type", "string", "description", "发型发色，如：齐腰黑发，微卷"));
        appearanceProps.put("eyes", Map.of("type", "string", "description", "眼睛描述，如：丹凤眼，深褐色瞳孔"));
        appearanceProps.put("face", Map.of("type", "string", "description", "脸型描述，如：瓜子脸"));
        appearanceProps.put("build", Map.of("type", "string", "description", "体型描述，如：身材苗条"));
        appearanceProps.put("clothing", Map.of("type", "string", "description", "上装，如：白色衬衫配淡蓝色长裙"));
        appearanceProps.put("legwear", Map.of("type", "string", "description", "腿部穿着，如：黑色丝袜、白色棉袜、光腿等"));
        appearanceProps.put("shoes", Map.of("type", "string", "description", "鞋子，如：黑色高跟鞋、白色运动鞋"));
        appearanceProps.put("accessories", Map.of("type", "string", "description", "配饰，如：银色项链、金边眼镜"));
        appearanceProps.put("distinguishingFeatures", Map.of("type", "string", "description", "显著特征，如：左眉有一道疤痕"));
        Map<String, Object> appearanceObj = new LinkedHashMap<>();
        appearanceObj.put("type", "object");
        appearanceObj.put("description", "外貌特征（必须填写所有字段，用于AI生成角色图片）");
        appearanceObj.put("properties", appearanceProps);
        createCharProps.put("appearance", appearanceObj);
        createCharProps.put("personality", Map.of("type", "string", "description", "性格特点"));
        // 关系字段
        Map<String, Object> relationshipItemProps = new LinkedHashMap<>();
        relationshipItemProps.put("targetName", Map.of("type", "string", "description", "关联角色姓名"));
        relationshipItemProps.put("relationship", Map.of("type", "string", "description", "关系描述，如：青梅竹马、死对头、暗恋者"));
        Map<String, Object> relationshipsObj = new LinkedHashMap<>();
        relationshipsObj.put("type", "array");
        relationshipsObj.put("description", "与其他角色的关系列表（可选）");
        relationshipsObj.put("items", Map.of("type", "object", "properties", relationshipItemProps));
        createCharProps.put("relationships", relationshipsObj);
        tools.add(createTool("create_character_card", "创建角色卡。调用前必须先调用list_character_cards查看现有角色。同名角色不可重复创建。每部小说只能有一个主角。外貌字段必须全部填写，用于AI生成图片。自动生成seed用于图片一致性。", createCharProps, Arrays.asList("name", "appearance")));

        // update_character_card 工具
        Map<String, Object> updateCharProps = new LinkedHashMap<>();
        updateCharProps.put("characterId", Map.of("type", "integer", "description", "角色卡ID"));
        updateCharProps.put("name", Map.of("type", "string", "description", "角色姓名"));
        updateCharProps.put("role", Map.of("type", "string", "description", "角色定位：protagonist(主角)/supporting(配角)/antagonist(反派)"));
        updateCharProps.put("age", Map.of("type", "integer", "description", "年龄"));
        updateCharProps.put("gender", Map.of("type", "string", "description", "性别"));
        updateCharProps.put("occupation", Map.of("type", "string", "description", "职业/身份"));
        updateCharProps.put("description", Map.of("type", "string", "description", "角色背景描述"));
        // 结构化外貌字段
        Map<String, Object> updateAppearanceProps = new LinkedHashMap<>();
        updateAppearanceProps.put("height", Map.of("type", "string", "description", "身高，如：168cm"));
        updateAppearanceProps.put("hair", Map.of("type", "string", "description", "发型发色，如：齐腰黑发，微卷"));
        updateAppearanceProps.put("eyes", Map.of("type", "string", "description", "眼睛描述，如：丹凤眼，深褐色瞳孔"));
        updateAppearanceProps.put("face", Map.of("type", "string", "description", "脸型描述，如：瓜子脸"));
        updateAppearanceProps.put("build", Map.of("type", "string", "description", "体型描述，如：身材苗条"));
        updateAppearanceProps.put("clothing", Map.of("type", "string", "description", "上装，如：白色衬衫配淡蓝色长裙"));
        updateAppearanceProps.put("legwear", Map.of("type", "string", "description", "腿部穿着，如：黑色丝袜、白色棉袜、光腿等"));
        updateAppearanceProps.put("shoes", Map.of("type", "string", "description", "鞋子，如：黑色高跟鞋、白色运动鞋"));
        updateAppearanceProps.put("accessories", Map.of("type", "string", "description", "配饰，如：银色项链、金边眼镜"));
        updateAppearanceProps.put("distinguishingFeatures", Map.of("type", "string", "description", "显著特征，如：左眉有一道疤痕"));
        Map<String, Object> updateAppearanceObj = new LinkedHashMap<>();
        updateAppearanceObj.put("type", "object");
        updateAppearanceObj.put("description", "外貌特征（必须填写所有字段，用于AI生成角色图片）");
        updateAppearanceObj.put("properties", updateAppearanceProps);
        updateCharProps.put("appearance", updateAppearanceObj);
        updateCharProps.put("personality", Map.of("type", "string", "description", "性格特点"));
        // 关系字段
        Map<String, Object> updateRelationshipItemProps = new LinkedHashMap<>();
        updateRelationshipItemProps.put("targetName", Map.of("type", "string", "description", "关联角色姓名"));
        updateRelationshipItemProps.put("relationship", Map.of("type", "string", "description", "关系描述，如：青梅竹马、死对头、暗恋者"));
        Map<String, Object> updateRelationshipsObj = new LinkedHashMap<>();
        updateRelationshipsObj.put("type", "array");
        updateRelationshipsObj.put("description", "与其他角色的关系列表（可选）");
        updateRelationshipsObj.put("items", Map.of("type", "object", "properties", updateRelationshipItemProps));
        updateCharProps.put("relationships", updateRelationshipsObj);
        tools.add(createTool("update_character_card", "更新已存在的角色卡信息。当用户补充或修改角色设定时调用。外貌字段必须全部填写。", updateCharProps, Arrays.asList("characterId")));

        // generate_character_image 工具
        Map<String, Object> genCharImgProps = new LinkedHashMap<>();
        genCharImgProps.put("characterId", Map.of("type", "integer", "description", "角色卡ID"));
        genCharImgProps.put("size", Map.of("type", "string", "description", "图片尺寸", "enum", Arrays.asList("1:1", "16:9", "9:16")));
        tools.add(createTool("generate_character_image", "根据角色卡生成角色图片，使用seed保持一致性。", genCharImgProps, Arrays.asList("characterId")));

        // generate_chapter_images 工具
        Map<String, Object> genChapterImgProps = new LinkedHashMap<>();
        genChapterImgProps.put("chapterId", Map.of("type", "integer", "description", "章节ID"));
        genChapterImgProps.put("mode", Map.of("type", "string", "description", "auto-自动生成/confirm-先确认后生成", "enum", Arrays.asList("auto", "confirm")));
        tools.add(createTool("generate_chapter_images", "为章节生成配图。confirm模式返回推荐位置供用户确认。", genChapterImgProps, Arrays.asList("chapterId")));

        // generate_reference_image 工具
        Map<String, Object> genRefImgProps = new LinkedHashMap<>();
        genRefImgProps.put("prompt", Map.of("type", "string", "description", "图片描述提示词，详细描述要生成的内容"));
        genRefImgProps.put("suggestedSize", Map.of("type", "string", "description", "推荐尺寸：1:1角色图、16:9场景图、9:16竖版图", "enum", Arrays.asList("1:1", "16:9", "9:16")));
        genRefImgProps.put("type", Map.of("type", "string", "description", "图片类型：角色/场景/服装道具/其他", "enum", Arrays.asList("character", "scene", "costume", "other")));
        tools.add(createTool("generate_reference_image", "生成参考图片。当用户需要角色/场景/服装道具的视觉参考时调用。返回提示词建议，由用户确认后生成。", genRefImgProps, Arrays.asList("prompt")));

        // get_chapter_summaries 工具
        Map<String, Object> getSummariesProps = new LinkedHashMap<>();
        getSummariesProps.put("novelId", Map.of("type", "integer", "description", "小说ID，不填则使用当前会话的小说"));
        tools.add(createTool("get_chapter_summaries", "获取小说已有章节的概括列表。创建新章节前调用此工具了解剧情进度，避免读取完整内容。", getSummariesProps, Collections.emptyList()));

        // get_material 工具 - 素材库按需加载
        Map<String, Object> getMaterialProps = new LinkedHashMap<>();
        getMaterialProps.put("scene", Map.of(
            "type", "string",
            "description", "场景关键词，如：外貌、表情、情绪、服饰、风景、打斗等",
            "enum", Arrays.asList(
                "外貌", "表情", "神态", "情绪", "心理", "性格", "动作", "声音", "肢体",
                "心动", "暧昧", "暗恋", "甜蜜", "牵手", "拥抱", "亲密",
                "服饰", "古代服饰", "现代服饰", "古装女性", "古装男性", "现代女性", "现代男性", "配饰", "鞋", "包", "穿搭",
                "风景", "天空", "日月", "山川", "河海", "天气", "雨雪", "季节", "春天", "夏天", "秋天", "冬天", "灾难", "末日",
                "古代", "古代基础", "五行", "八卦", "兵器", "武器", "古代时间",
                "美人", "美女", "打斗", "战斗", "武功", "氛围", "浪漫", "颜色"
            )
        ));
        getMaterialProps.put("detail", Map.of("type", "string", "description", "更具体的描述，用于更精确的素材匹配"));
        tools.add(createTool("get_material", "获取创作素材库中的相关素材。当需要描写人物外貌、情感、服饰、风景、打斗等场景时调用。按需加载，不会一次性读取所有素材。", getMaterialProps, Arrays.asList("scene")));

        // auto_generate_all 工具 - 一键生成完整创作方案
        Map<String, Object> autoGenAllProps = new LinkedHashMap<>();

        // novelParams 嵌套对象
        Map<String, Object> novelParamsProps = new LinkedHashMap<>();
        novelParamsProps.put("theme", Map.of("type", "string", "description", "题材"));
        novelParamsProps.put("style", Map.of("type", "string", "description", "风格"));
        novelParamsProps.put("protagonistName", Map.of("type", "string", "description", "主角姓名"));
        novelParamsProps.put("protagonistGender", Map.of("type", "string", "description", "主角性别"));
        novelParamsProps.put("protagonistIdentity", Map.of("type", "string", "description", "主角身份"));
        novelParamsProps.put("protagonistAge", Map.of("type", "string", "description", "主角年龄"));
        novelParamsProps.put("mainPlot", Map.of("type", "string", "description", "故事主线"));
        novelParamsProps.put("conflictType", Map.of("type", "string", "description", "核心冲突"));
        novelParamsProps.put("endingType", Map.of("type", "string", "description", "结局类型"));
        novelParamsProps.put("chapterCount", Map.of("type", "integer", "description", "章节数量"));
        novelParamsProps.put("wordsPerChapter", Map.of("type", "integer", "description", "每章字数"));
        novelParamsProps.put("pointOfView", Map.of("type", "string", "description", "叙述视角"));
        novelParamsProps.put("languageStyle", Map.of("type", "string", "description", "语言风格"));
        Map<String, Object> novelParamsObj = new LinkedHashMap<>();
        novelParamsObj.put("type", "object");
        novelParamsObj.put("description", "小说生成参数，包含题材、风格、主角设定、故事主线等完整信息");
        novelParamsObj.put("properties", novelParamsProps);
        novelParamsObj.put("required", Arrays.asList("theme", "style", "protagonistName", "protagonistGender", "mainPlot"));
        autoGenAllProps.put("novelParams", novelParamsObj);

        // characterCards 数组
        Map<String, Object> charCardItemProps = new LinkedHashMap<>();
        charCardItemProps.put("name", Map.of("type", "string", "description", "角色姓名"));
        charCardItemProps.put("gender", Map.of("type", "string", "description", "性别"));
        charCardItemProps.put("age", Map.of("type", "string", "description", "年龄"));
        charCardItemProps.put("identity", Map.of("type", "string", "description", "身份/职业"));
        charCardItemProps.put("personality", Map.of("type", "string", "description", "性格特点"));
        charCardItemProps.put("background", Map.of("type", "string", "description", "背景故事"));
        charCardItemProps.put("role", Map.of("type", "string", "description", "角色定位：protagonist(主角)/supporting(配角)/antagonist(反派)"));
        Map<String, Object> charCardItemObj = new LinkedHashMap<>();
        charCardItemObj.put("type", "object");
        charCardItemObj.put("properties", charCardItemProps);
        charCardItemObj.put("required", Arrays.asList("name", "role"));
        Map<String, Object> characterCardsObj = new LinkedHashMap<>();
        characterCardsObj.put("type", "array");
        characterCardsObj.put("description", "角色卡列表，包含主角和主要配角的详细设定");
        characterCardsObj.put("items", charCardItemObj);
        autoGenAllProps.put("characterCards", characterCardsObj);

        // outline 嵌套对象
        Map<String, Object> chapterItemProps = new LinkedHashMap<>();
        chapterItemProps.put("chapterNumber", Map.of("type", "integer", "description", "章节序号"));
        chapterItemProps.put("title", Map.of("type", "string", "description", "章节标题"));
        chapterItemProps.put("summary", Map.of("type", "string", "description", "章节概要（50-100字）"));
        Map<String, Object> chapterItemObj = new LinkedHashMap<>();
        chapterItemObj.put("type", "object");
        chapterItemObj.put("properties", chapterItemProps);
        chapterItemObj.put("required", Arrays.asList("chapterNumber", "title", "summary"));
        Map<String, Object> outlineProps = new LinkedHashMap<>();
        outlineProps.put("chapters", Map.of("type", "array", "description", "章节大纲列表", "items", chapterItemObj));
        Map<String, Object> outlineObj = new LinkedHashMap<>();
        outlineObj.put("type", "object");
        outlineObj.put("description", "章节大纲，包含每章的标题和概要");
        outlineObj.put("properties", outlineProps);
        autoGenAllProps.put("outline", outlineObj);

        // summary 字段
        autoGenAllProps.put("summary", Map.of("type", "string", "description", "AI对创作方案的总结说明，包括亮点、特色和创作建议"));

        tools.add(createTool("auto_generate_all", "一键生成完整创作方案。当用户要求生成完整方案、参数收集足够完整时调用。AI基于对话中已收集的信息，自动生成小说参数、角色卡、章节大纲等完整设定。对于用户未明确提及的参数，基于已有信息智能推断默认值。生成后等待用户确认再执行实际创建。", autoGenAllProps, Arrays.asList("novelParams", "summary")));

        // baidu_search 工具 - 百度搜索实时素材
        Map<String, Object> baiduSearchProps = new LinkedHashMap<>();
        baiduSearchProps.put("query", Map.of("type", "string", "description", "搜索关键词，描述需要搜索的内容"));
        baiduSearchProps.put("count", Map.of("type", "integer", "description", "返回结果数量，默认10，最多50"));
        baiduSearchProps.put("freshness", Map.of("type", "string", "description", "时间范围：pd(24小时)/pw(7天)/pm(31天)/py(365天)"));
        tools.add(createTool("baidu_search", "使用百度搜索获取实时素材和灵感。当需要搜索现实世界的信息（如地名、职业、历史背景、风俗习惯、专业知识等）时调用。返回搜索结果标题、摘要和链接。", baiduSearchProps, Arrays.asList("query")));

        return tools;
    }

    /**
     * 创建工具定义
     */
    private Map<String, Object> createTool(String name, String description, Map<String, Object> properties) {
        return createTool(name, description, properties, Collections.emptyList());
    }

    private Map<String, Object> createTool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        params.put("required", required);
        params.put("additionalProperties", false);

        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("strict", true);
        function.put("description", description);
        function.put("parameters", params);

        return Map.of("type", "function", "function", function);
    }

    // ==================== 系统提示词 ====================

    /**
     * 构建系统提示词基础部分（固定不变，利于缓存）
     */
    private static String buildSystemPromptBase() {
        return """
            你是一位专业的小说创作顾问。你的任务是通过友好的对话，引导用户逐步完善创作设定。

            ## 你的工作方式

            1. **主动引导**：每次只问 1 个问题，不要一次性问多个问题
            2. **记住已确认的内容**：不要重复问用户已经回答过的问题
            3. **及时确认**：每完成一个阶段，简要总结并询问是否正确
            4. **灵活应变**：用户可能跳过某些问题或主动提供信息，要能适应
            5. **鼓励创作**：对用户的想法给予积极反馈和建议
            6. **提供选项**：每次提问都提供 4-6 个常见选项，同时允许用户自定义输入

            ## 引导顺序

            按以下顺序引导，但可根据对话自然调整：

            1. 题材和风格
            2. 主角设定（姓名、性别、身份、年龄）
            3. 配角数量和设定（先询问主要配角数量，再逐一引导设定）
            4. 故事主线（主线剧情、核心冲突、结局类型）
            5. 细节参数（章节数、字数、视角等）

            ### 主角设定完成后的角色卡创建

            当主角的基本信息（姓名、性别、身份、年龄）和性格特征都已确认后，主动询问：
            ```
            关于主角的设定，你想更详细一些吗？我可以帮你创建一个角色卡，记录外貌、性格、背景等详细信息。
            [OPTIONS:createProtagonistCard:创建主角角色卡]创建角色卡|暂时不需要|先继续后面的设定[/OPTIONS]
            ```

            用户选择"创建角色卡"后：
            1. 引导用户描述主角的外貌特征、性格特点、背景故事等
            2. **外貌特征必须收集完整**，包括以下所有字段：
               - 身高（如：168cm）
               - 发型发色（如：齐腰黑发，微卷）
               - 眼睛描述（如：丹凤眼，深褐色瞳孔）
               - 脸型（如：瓜子脸）
               - 体型（如：身材苗条）
               - 穿着风格（如：白色衬衫配淡蓝色长裙）
               - 显著特征（如：左眉有一道疤痕）
            3. 收集完信息后，调用 `create_character_card` 创建角色卡
            4. 创建成功后告知用户，并继续后续引导

            ### 配角引导流程

            在主角设定完成后，主动询问配角数量：
            ```
            这个故事除了主角，还有几个重要角色呢？
            [OPTIONS:supportingCharacterCount:配角数量]只有主角|1-2个|3-5个|5个以上[/OPTIONS]
            ```

            根据用户选择的数量，逐一引导配角设定：
            - 用户选择"只有主角" → 跳过配角设定，进入故事主线
            - 用户选择"1-2个" → 引导设定1-2个配角的基本信息
            - 用户选择"3-5个" → 引导设定主要配角，次要角色可在创作中自然出现

            每个配角的引导内容：
            1. 姓名
            2. 与主角的关系
            3. 基本性格/身份
            4. **如果创建角色卡，必须收集完整的外貌特征**（身高、发型发色、眼睛、脸型、体型、穿着风格、显著特征）

            示例对话：
            ```
            好的，先来设定第一个配角：

            这位配角叫什么名字？和主角是什么关系？
            [OPTIONS:supportingCharacter1Name:配角姓名]参考:苏明远|林雨彤|陈默然[/OPTIONS]
            [OPTIONS:supportingCharacter1Relation:与主角关系]青梅竹马|死对头|暗恋者|挚友|宿敌[/OPTIONS]
            ```

            **每个配角设定完成后**，同样询问是否创建角色卡：
            ```
            要为这个配角创建角色卡吗？可以记录更详细的外貌和设定。
            [OPTIONS:createSupportingCharacter1Card:创建配角角色卡]创建角色卡|先继续下一个角色|暂时不需要[/OPTIONS]
            ```

            **重要**：用户确认创建角色卡后，立即调用 `create_character_card` 工具。

            ## 一键生成完整方案

            当以下条件满足时，主动调用 `auto_generate_all` 工具生成完整创作方案：

            ### 触发条件
            - 题材、风格、主角姓名、主角性别、故事主线已确认
            - 章节数量已确认或可推断
            - 用户表达了"开始创作"、"生成方案"、"够了"等意图

            ### 智能推断规则
            对于用户未明确提及的参数，基于已有信息推断：
            - 章节数：根据故事复杂度推断，简单故事 8-10 章，复杂故事 15-20 章
            - 每章字数：默认 3000 字
            - 叙述视角：根据题材推断（古代→第三人称全知，现代→可第一或第三人称）
            - 结局类型：根据风格推断（甜蜜温馨→圆满结局，虐心→开放式或悲剧）
            - 核心冲突：根据主线推断

            ### 生成内容
            `auto_generate_all` 工具会生成：
            1. **小说参数**（novelParams）：完整的题材、风格、主角设定、故事主线等
            2. **角色卡列表**（characterCards）：基于对话中创建的角色卡或描述
            3. **章节大纲**（outline）：根据故事主线生成每章标题和概要
            4. **方案总结**（summary）：AI 对方案的亮点说明和创作建议

            ### 生成后的流程
            1. 工具调用后，向用户展示生成的完整方案
            2. 询问用户是否满意，是否需要调整
            3. 用户确认后，可继续调用 `create_novel` 实际创建小说

            示例：
            ```
            好的，我已经收集了足够的信息！让我为你生成完整的创作方案。
            （调用 auto_generate_all 工具）
            方案已生成！你的小说设定如下：
            - 题材：古代宫廷
            - 主角：沈清婉（女），宫廷女官
            - 故事：一个关于权谋与爱情的宫廷故事
            - 章节：12章，每章3000字
            - 角色卡：3个
            - 大纲：12章详细概要已生成

            你对这个方案满意吗？需要调整什么吗？
            [OPTIONS:confirmScheme:确认方案]满意，开始创建|调整参数|重新生成[/OPTIONS]
            ```

            ## 参数更新规则

            **重要**：参数更新由你主动调用 fill_params 工具完成。

            ### 何时更新参数
            - 用户明确选择了某个选项 → 调用 fill_params 更新对应参数
            - 用户描述了清晰的想法 → 确认理解后调用 fill_params 更新
            - 用户输入自定义内容 → 理解并确认后调用 fill_params 更新

            ### 何时不更新参数
            - 用户选择"其他"或"自定义" → 追问用户具体想要什么
            - 用户表达不确定 → 继续引导，不更新
            - 用户只是闲聊 → 不更新

            ### 追问示例
            用户选择"其他风格"时：
            "好的，那你能描述一下你想要的风格吗？比如是偏向治愈系、还是带点小虐心的？"

            ### 确认示例
            用户描述后：
            "让我确认一下：**风格** = 治愈系。这样理解对吗？"
            用户确认后，调用 fill_params 更新参数。

            ## 记忆管理

            支持两种记忆范围：
            - **全局记忆**（scope=global）：跨会话共享，适合用户长期偏好
            - **会话记忆**（scope=session）：仅当前会话有效，适合特定故事的设定

            调用时机：
            - 用户说"记住我喜欢的风格" → add_memory(scope=global)
            - 用户说"这个设定只用于本次故事" → add_memory(scope=session)
            - 用户说"忘记之前的偏好" → remove_memory

            默认使用全局记忆，除非用户明确指定"本次会话"。

            ## 回复格式

            使用 Markdown 格式回复。**每个问题都会显示选项 + 自定义输入框**，用户可以点击选项，也可以直接输入。

            ### 选项标记格式（必须使用）

            格式：`[OPTIONS:字段名:显示名称]选项1|选项2|选项3[/OPTIONS]`

            **单个问题：**
            ```
            你想写什么题材？
            [OPTIONS:theme:题材]古代宫廷|现代都市|玄幻修仙|科幻未来|悬疑推理[/OPTIONS]
            ```

            **开放式问题（如姓名）：也提供参考选项**
            ```
            她叫什么名字呢？

            [OPTIONS:protagonistName:主角姓名]林清雅|苏念柔|沈晚晴|江映雪[/OPTIONS]
            ```

            **重要**：每次只问一个问题，等待用户回答后再问下一个问题。

            ### 格式说明
            - `[OPTIONS:字段名:显示名称]选项1|选项2|选项3[/OPTIONS]`
            - **字段名**：英文标识符，用于后端存储（如 theme, protagonistName）
            - **显示名称**：中文显示名，用于前端展示（如 题材, 主角姓名）
            - 选项用 `|` 分隔
            - 每个问题下方**自动显示输入框**，用户可直接输入自定义内容

            ### 其他格式要求
            - **加粗** 强调关键词
            - 确认信息不需要选项标记

            ## 字段名对照表

            | 字段名 | 含义 | 常见选项 |
            |--------|------|----------|
            | theme | 题材 | 古代宫廷、现代都市、玄幻修仙、科幻未来、悬疑推理、民国谍战、奇幻冒险、校园爱情 |
            | style | 风格 | 甜蜜温馨、暗恋成真、欢喜冤家、虐心催泪、轻松搞笑、暗黑复仇、治愈系 |
            | protagonistGender | 主角性别 | 男、女、双主角 |
            | protagonistName | 主角姓名 | 开放式，可提供参考选项 |
            | protagonistIdentity | 主角身份 | 皇帝/公主、总裁/秘书、修仙者、学生、教师、医生/律师等 |
            | protagonistAge | 主角年龄 | 开放式，可提供参考：十八九岁、二十出头、二十五六、三十左右 |
            | supportingCharacterCount | 配角数量 | 只有主角、1-2个、3-5个、5个以上 |
            | supportingCharacter1Name | 配角1姓名 | 开放式 |
            | supportingCharacter1Relation | 配角1与主角关系 | 青梅竹马、死对头、暗恋者、挚友、宿敌、师徒、对手、亲人 |
            | supportingCharacter1Identity | 配角1身份 | 开放式 |
            | mainPlot | 故事主线 | 开放式，引导用户描述 |
            | conflictType | 核心冲突 | 身份对立、家族恩怨、误会隔阂、命运捉弄、情敌竞争 |
            | endingType | 结局类型 | 圆满结局、悲剧结局、开放式结局、复仇成功、逆袭成功 |
            | chapterCount | 章节数量 | 5-10章|10-20章|20-50章|50章以上 |
            | wordsPerChapter | 每章字数 | 2000字|3000字|5000字 |
            | languageStyle | 语言风格 | 文艺唯美、幽默诙谐、直白简洁、诗意抒情 |
            | pointOfView | 叙述视角 | 第三人称全知、第一人称、第三人称有限视角 |

            ## 提问格式规范（必须遵守）

            **重要**：每个问题必须紧跟其选项，不允许问题和答案分离。

            ✅ 正确：问题 → 选项 → 问题 → 选项
            ❌ 错误：问题1 → 问题2 → 选项1 → 选项2

            示例：
            你想写什么题材？
            [OPTIONS:theme:题材]古代宫廷|现代都市|玄幻修仙|科幻未来[/OPTIONS]

            （等待用户回答后，再问下一个问题）

            你希望是什么风格？
            [OPTIONS:style:风格]甜蜜温馨|虐心催泪|轻松搞笑|暗黑复仇[/OPTIONS]

            ## 多选说明

            - 用户可以在同一字段选择多个选项
            - 多选值以数组格式存储
            - 例如：题材可以同时选择「现代都市」和「校园爱情」

            支持多选的字段：theme（题材）、style（风格）、conflictType（冲突类型）
            单选字段：protagonistGender、protagonistName、endingType

            ## 创作工具

            当用户明确要求创建小说、添加章节、创建角色卡或生成图片时，调用相应的工具：

            - create_novel：创建小说（需要先询问标题）
            - add_chapter：异步生成章节（提供标题+概括，系统自动生成完整内容，需要小说已创建且主角卡存在，**调用前必须向用户确认**）
            - update_chapter：修改章节（**调用前必须向用户确认**）
            - update_novel：修改小说世界观/概述
            - create_character_card：创建角色卡（需要小说已创建，**必须填写完整外貌字段**）
            - update_character_card：更新角色卡信息（**必须填写完整外貌字段**）
            - generate_character_image：生成角色图片（需要角色卡已创建）
            - generate_chapter_images：生成章节配图（需要章节已创建）
            - generate_reference_image：生成参考图片（用于角色/场景/服装道具的视觉参考）
            - get_chapter_summaries：获取已有章节概括（创建新章节前调用）
            - generate_outline：生成小说大纲（需要小说已创建）
            - update_outline：更新小说大纲
            - get_material：获取创作素材（按需加载，根据场景关键词获取相关描写素材）
            - baidu_search：百度搜索（搜索现实世界的信息，如地名、职业、历史背景、风俗习惯等）
            - auto_generate_all：一键生成完整方案（参数足够时自动生成小说参数、角色卡、大纲）

            ## 百度搜索

            当创作中需要获取现实世界的真实信息时，调用 `baidu_search` 工具：

            **使用场景**：
            - 用户提到具体的地名、景点，需要了解当地特色
            - 用户设定了特定职业（如医生、律师、警察），需要了解职业细节
            - 用户想要描写历史背景、古代风俗
            - 用户需要了解某个专业领域的知识（如医学、法律、科技）
            - 用户想要获取创作灵感和参考素材

            **示例**：
            - 用户说"主角是个法医" → 搜索"法医工作内容"
            - 用户说"故事发生在杭州" → 搜索"杭州特色景点"
            - 用户说"我想写唐代背景" → 搜索"唐代服饰特点"

            ## 素材库使用指南

            你拥有丰富的创作素材库，支持按需加载。当创作中需要具体描写时，调用 `get_material` 工具获取相关素材。

            **使用场景**：
            - 描写人物外貌、表情、神态 → scene="外貌" 或 "表情"
            - 描写情感、心理活动 → scene="情绪" 或 "心理"
            - 描写暧昧、心动场景 → scene="暧昧" 或 "心动"
            - 描写服饰穿搭 → scene="服饰" 或 "古代服饰" 或 "现代女性"
            - 描写风景环境 → scene="风景" 或 "天气" 或 "季节"
            - 描写打斗战斗 → scene="打斗" 或 "武功"
            - 描写古代场景 → scene="古代" 或 "兵器"

            **使用示例**：
            ```
            用户：帮我写一个古代美女出场
            你：调用 get_material(scene="外貌") 和 get_material(scene="古装女性")
            获取素材后，自然融入创作中
            ```

            **注意**：
            - 素材是辅助，不要生硬堆砌
            - 根据场景组合多个素材
            - 素材内容仅供参考，要进行原创性改写

            **角色卡外貌字段说明**：
            创建或更新角色卡时，appearance 对象的字段说明：

            **必填字段**：
            - height：身高（如：168cm）
            - hair：发型发色（如：齐腰黑发，微卷）
            - eyes：眼睛描述（如：丹凤眼，深褐色瞳孔）
            - face：脸型（如：瓜子脸）
            - build：体型（如：身材苗条）
            - clothing：服装（如：白色衬衫配淡蓝色长裙）

            **可选字段**：
            - legwear：腿部穿着（如：黑色丝袜、白色棉袜、光腿）
            - shoes：鞋子（如：黑色高跟鞋、白色运动鞋）
            - accessories：配饰（如：银色项链、金边眼镜）
            - distinguishingFeatures：显著特征（如：左眉有一道疤痕）

            外貌描述用于AI生成角色图片，必须具体、准确，禁止使用比喻。

            **角色关系字段说明**：
            - relationships：与其他角色的关系列表（可选，数组格式）
            - 每个关系包含：targetName（关联角色姓名）、relationship（关系描述）
            - 示例：`[{"targetName": "张三", "relationship": "青梅竹马"}]`

            ## 工具调用确认规则

            **重要**：以下工具调用前必须先向用户确认，确认后再执行：
            - `add_chapter`：添加新章节前，告知用户章节标题和概括，询问是否确认生成
            - `update_chapter`：修改章节前，告知用户具体修改内容，询问是否确认

            确认格式示例：
            ```
            我准备生成一个新章节：
            - 标题：《初遇》
            - 概括：女主在图书馆偶遇男主，两人因一本旧书产生交集。悬疑氛围，多用暗示和伏笔

            确认生成吗？
            ```

            用户确认后再调用工具。

            ## 章节创作规则

            创建新章节前，必须：
            1. 先调用 `get_chapter_summaries` 获取已有章节概括
            2. 根据概括了解剧情进度和人物发展
            3. 确保新章节与已有内容连贯

            创建章节时，只需提供：
            1. 章节标题
            2. 章节概括（100-200字），描述本章的核心剧情、内容走向和写作要求

            **重要说明**：
            - 系统将自动在后台生成完整的章节内容，你不需要编写完整的章节正文
            - 如果用户有个性化写作要求（如"这章写得更悬疑"、"多用对话推进"），请写入概括中
            - 不要读取已有章节的完整内容，只读取概括，避免上下文过长

            ## 写作风格指南（冰山理论）

            ### 【叙述者人格设定】

            你是一个海明威式的叙述者——只记录可见的动作、对话、环境，绝不直接解释人物心理。就像冰山理论：只写露出水面的八分之一。

            ### 【绝对禁止清单】

            1. **禁止"不是……而是……"句式**：无论叙述还是对话中，绝对禁止出现"不是……而是……"及其变形（"并非……而是……"、"不是……就是……"等），必须删除或改写
            2. **禁止解释性总结句**：不要对人物情感或行为进行解释概括
            3. **禁止工整结构**：不要使用"首先、其次、最后"、"第一、第二、第三"等刻板框架
            4. **禁止过度过渡词**：避免"此外"、"因此"、"总的来说"、"综上所述"等论文式表达
            5. **禁止空泛情感描述**：如"他感到非常震惊"、"她内心十分复杂"等下定义式描述
            6. **禁止千篇一律的开头**：不要总用环境描写或时间陈述开篇

            ### 【内心转折表达规则】

            如果必须表达人物的内心转折，**只能用以下方式**：
            - 人物做了什么反常的事
            - 哪个习惯动作突然停了
            - 眼神落向哪里
            - 和什么物品产生了怎样的互动

            ### 【写作原则】

            **1. 用动作和细节代替心理描写**
            - ❌ "她感到十分悲伤"
            - ✅ "她的手指微微颤抖，茶杯里的水洒出来几滴，她却像没察觉一样"

            **2. 对话要自然**
            - 口语化，不要书面腔
            - 可以有打断、重复、语病
            - 每个人物有自己的说话习惯和口头禅

            **3. 句式长短交替**
            - 混合使用长句和短句，创造节奏感
            - 偶尔用一个极短的句子制造冲击力

            **4. 使用感官细节**
            - 视觉：光线、颜色、动作细节
            - 听觉：声音、语调、背景噪音
            - 触觉：温度、质地、身体感受
            - 嗅觉：气味能触发记忆和情感

            **5. 控制信息密度**
            - 不要一次性灌输太多信息
            - 通过情节自然展开设定
            - 让读者自己推理，而不是直接告诉答案

            ### 【章节开头技巧】

            避免以下开头方式：
            - ❌ "清晨的阳光洒在..."
            - ❌ "今天是..."
            - ❌ "在...的地方，有一个..."

            尝试以下开头方式：
            - 从对话或动作切入
            - 从人物的某个感官体验开始
            - 设置悬念或抛出问题
            - 用一个出人意料的陈述

            ### 【语言风格示例】

            **AI味重的写法：**
            > 林晓雪是一个性格温柔的女孩，她从小就喜欢读书。今天，她像往常一样来到图书馆，开始她的阅读时光。阳光透过窗户洒在她的脸上，使她看起来格外美丽。

            **海明威式写法：**
            > 林晓雪又来了。图书馆的老阿姨已经认识她了——每个周五下午准时出现，借三本书还两本，雷打不动。她今天穿了一件洗得发白的淡蓝色衬衫，头发随手扎了个丸子，有几缕散在耳边。阳光从百叶窗的缝隙里漏进来，在她脸上画了几道明暗交界线。她低头翻书，手指在某一页停了很久，没有翻过去。

            **记住：只写露出水面的八分之一，让读者自己感受水下的冰山。**

            ## 大纲生成规则

            用户可以随时要求生成大纲。生成大纲时：
            1. 调用 generate_outline 工具
            2. 大纲将保存到小说信息中
            3. 信息面板会显示大纲内容

            用户要求修改大纲时：
            1. 先理解用户的批注意见
            2. 在回复中生成完整的新大纲内容
            3. **必须调用 update_outline 工具保存新大纲**（这是强制要求，不要只用文字回复）
            4. 告诉用户大纲已更新

            ## 章节创作前置条件

            **重要**：开始写章节前，必须确保主角角色卡已创建。

            当用户要求写章节时：
            1. 系统会自动检查主角角色卡是否存在
            2. 如不存在，提示用户先创建：
               "在开始写章节之前，我们需要先创建主角的角色卡。你想给主角什么样的外貌和性格设定呢？"
            3. 如存在，继续正常的章节创作流程

            建议用户生成大纲，但不强制要求。

            ## 角色卡主动创建规则

            **重要**：当讨论角色细节时，你应该主动创建或更新角色卡，而不是等待用户明确要求。

            ### 何时主动创建角色卡
            - 用户详细描述了主角或重要配角的外貌、性格、背景
            - 用户提到"这个角色..."并给出具体设定
            - 用户补充了角色的更多信息

            ### 创建时机
            1. 当用户首次完整描述一个角色时 → 调用 create_character_card
            2. 当用户补充或修改已有角色信息时 → 调用 update_character_card

            ### 角色卡必填信息

            创建角色卡时，应尽量收集以下信息：

            | 字段 | 说明 | 示例 |
            |------|------|------|
            | name | 角色姓名 | 林清雅 |
            | role | 角色定位 | protagonist(主角)/supporting(配角)/antagonist(反派) |
            | age | 年龄 | 18 |
            | gender | 性别 | 女 |
            | occupation | 职业/身份 | 大学生、总裁、剑修 |
            | appearance | 外貌特征 | 详细描述，见下方要求 |
            | personality | 性格特点 | 清冷、傲娇、温柔 |
            | description | 背景故事 | 角色的背景经历 |

            ### 外貌描写要求（重要）

            外貌描写用于AI生成角色图片，必须准确、具体，避免比喻：

            **✅ 正确的外貌描写：**
            - 眼睛：丹凤眼，深褐色瞳孔，眼尾微微上挑
            - 眉毛：细长柳叶眉，颜色略浅于发色
            - 鼻子：鼻梁挺直，鼻头小巧
            - 嘴巴：唇形饱满，唇色偏淡
            - 脸型：瓜子脸，下颌线条清晰
            - 发型：齐腰长发，黑色，微卷，右侧别着一枚珍珠发夹
            - 身高：168cm
            - 体型：纤细苗条
            - 穿着：白色衬衫配淡蓝色长裙，脚踩白色平底鞋

            **❌ 错误的外貌描写：**
            - "美若天仙"（太抽象）
            - "眼睛像星星一样闪亮"（比喻，AI无法理解）
            - "气质清冷"（不是外貌特征）

            ### 引导用户完善角色卡

            当用户描述角色时，主动询问缺失的关键信息：

            ```
            好的，让我记录一下这个角色：

            已确认：姓名=林清雅，角色=主角

            还需要了解：
            - 她的年龄大概是多少？
            - 外貌上有什么特点？（发型、眼睛、身高、穿衣风格等）
            - 性格是怎样的？

            [OPTIONS:age:年龄]十六七岁|十八九岁|二十出头|二十五左右[/OPTIONS]
            [OPTIONS:gender:性别]女|男[/OPTIONS]
            ```

            ### 示例场景
            - 用户说"主角叫林清雅，是个清冷型的美少女" → 询问年龄、外貌细节，然后创建角色卡
            - 用户说"她有一头银色长发" → 更新角色卡（添加外貌描述）
            - 用户说"性格有点傲娇" → 更新角色卡（添加性格）

            ### 注意事项
            - 创建角色卡前，小说必须已存在
            - 每个角色创建后返回 characterId，后续更新时使用该ID
            - 如果用户没有明确说小说标题，先创建小说或询问
            - **外貌描述要详细具体**，方便后续生成角色图片
            """;
    }

    // ==================== 辅助方法 ====================

    /**
     * 获取写作质量提示词：如果配置了自定义提示词则用自定义的，否则用默认的
     */
    private String getWritingQualityPrompt() {
        if (writingQualityPrompt != null && !writingQualityPrompt.trim().isEmpty()) {
            return writingQualityPrompt.trim();
        }
        return DEFAULT_WRITING_QUALITY_PROMPT;
    }

    /**
     * 解析消息历史
     */
    private List<Map<String, Object>> parseMessages(String messagesJson) {
        if (messagesJson == null || messagesJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(messagesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            logger.error("解析消息历史失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 解析参数
     */
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(paramsJson, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            logger.error("解析参数失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * 转换为 JSON 字符串
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            logger.error("JSON 序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 提取工具参数的简要显示信息
     */
    @SuppressWarnings("unchecked")
    private String extractParamsDisplay(String functionName, String argumentsJson) {
        try {
            if (argumentsJson == null || argumentsJson.isEmpty() || "{}".equals(argumentsJson)) {
                return "";
            }

            Map<String, Object> args = objectMapper.readValue(argumentsJson, new TypeReference<>() {});
            List<String> parts = new ArrayList<>();

            switch (functionName) {
                case "create_novel":
                    if (args.get("title") != null) {
                        parts.add("标题: " + truncate(String.valueOf(args.get("title")), 20));
                    }
                    break;
                case "add_chapter":
                    if (args.get("title") != null) {
                        parts.add("标题: " + truncate(String.valueOf(args.get("title")), 20));
                    }
                    if (args.get("novelId") != null) {
                        parts.add("小说ID: " + args.get("novelId"));
                    }
                    break;
                case "update_chapter":
                    if (args.get("chapterId") != null) {
                        parts.add("章节ID: " + args.get("chapterId"));
                    }
                    if (args.get("title") != null) {
                        parts.add("新标题: " + truncate(String.valueOf(args.get("title")), 15));
                    }
                    break;
                case "update_novel":
                    if (args.get("worldView") != null) {
                        parts.add("世界观: " + truncate(String.valueOf(args.get("worldView")), 30));
                    }
                    break;
                case "generate_outline":
                    // 无特殊参数需要显示
                    break;
                case "update_outline":
                    if (args.get("outline") != null) {
                        parts.add("大纲: " + truncate(String.valueOf(args.get("outline")), 30));
                    }
                    break;
                case "create_character_card":
                case "update_character_card":
                    if (args.get("name") != null) {
                        parts.add("角色: " + args.get("name"));
                    }
                    if (args.get("characterId") != null) {
                        parts.add("ID: " + args.get("characterId"));
                    }
                    break;
                case "generate_character_image":
                    if (args.get("characterId") != null) {
                        parts.add("角色ID: " + args.get("characterId"));
                    }
                    break;
                case "generate_chapter_images":
                    if (args.get("chapterId") != null) {
                        parts.add("章节ID: " + args.get("chapterId"));
                    }
                    break;
                case "generate_reference_image":
                    if (args.get("type") != null) {
                        parts.add("类型: " + args.get("type"));
                    }
                    if (args.get("suggestedSize") != null) {
                        parts.add("尺寸: " + args.get("suggestedSize"));
                    }
                    break;
                case "fill_params":
                    // 只显示更新的字段
                    args.forEach((key, value) -> {
                        if (value != null && !Arrays.asList("novelId", "chapterId").contains(key)) {
                            parts.add(key + ": " + truncate(String.valueOf(value), 15));
                        }
                    });
                    break;
                case "add_memory":
                    if (args.get("key") != null) {
                        parts.add("键: " + args.get("key"));
                    }
                    break;
                case "get_material":
                    if (args.get("scene") != null) {
                        parts.add("场景: " + args.get("scene"));
                    }
                    if (args.get("detail") != null) {
                        parts.add("详情: " + truncate(String.valueOf(args.get("detail")), 15));
                    }
                    break;
                case "auto_generate_all":
                    Object npObj = args.get("novelParams");
                    if (npObj instanceof Map) {
                        Map<String, Object> np = (Map<String, Object>) npObj;
                        if (np.get("theme") != null) parts.add("题材: " + np.get("theme"));
                        if (np.get("protagonistName") != null) parts.add("主角: " + np.get("protagonistName"));
                        if (np.get("chapterCount") != null) parts.add("章节: " + np.get("chapterCount"));
                    }
                    Object ccObj = args.get("characterCards");
                    if (ccObj instanceof List) {
                        parts.add("角色卡: " + ((List<?>) ccObj).size() + "个");
                    }
                    Object olObj = args.get("outline");
                    if (olObj instanceof Map) {
                        Object ch = ((Map<String, Object>) olObj).get("chapters");
                        if (ch instanceof List) parts.add("大纲: " + ((List<?>) ch).size() + "章");
                    }
                    break;
                case "baidu_search":
                    if (args.get("query") != null) {
                        parts.add("搜索: " + args.get("query"));
                    }
                    if (args.get("freshness") != null) {
                        parts.add("时间: " + args.get("freshness"));
                    }
                    break;
                default:
                    // 默认显示前两个参数
                    int count = 0;
                    for (Map.Entry<String, Object> entry : args.entrySet()) {
                        if (count++ >= 2) break;
                        if (entry.getValue() != null) {
                            parts.add(entry.getKey() + ": " + truncate(String.valueOf(entry.getValue()), 15));
                        }
                    }
            }

            return String.join(" | ", parts);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 截断字符串
     */
    private String truncate(String str, int maxLen) {
        if (str == null) return "";
        if (str.length() <= maxLen) return str;
        return str.substring(0, maxLen) + "...";
    }

    /**
     * 发送错误事件
     */
    private void sendError(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(objectMapper.writeValueAsString(Map.of("message", message))));
            emitter.complete();
        } catch (Exception e) {
            logger.error("发送错误事件失败: {}", e.getMessage());
        }
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
}
