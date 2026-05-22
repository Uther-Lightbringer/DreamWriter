package org.zenithon.articlecollect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zenithon.articlecollect.dto.CharacterCard;
import org.zenithon.articlecollect.dto.CharacterCardAppearance;
import org.zenithon.articlecollect.dto.GeneratedOutline;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说生成器单步生成服务
 * 负责调用AIPromptService生成各种内容
 */
@Service
public class NovelGeneratorStepService {

    private static final Logger logger = LoggerFactory.getLogger(NovelGeneratorStepService.class);

    // DeepSeek v4-flash 最大输出token限制
    private static final int MAX_TOKENS = 8192;

    // 统一的 System Prompt 前缀，用于 DeepSeek 前缀缓存优化
    private static final String SYSTEM_PREFIX = "你是一名专业的成人小说创作助手，擅长创作具有张力和吸引力的故事内容。你精通各种文学技巧，能够根据用户的需求创作出高质量的小说内容。\n\n---\n\n";

    private final AIPromptService aiPromptService;
    private final ObjectMapper objectMapper;

    // ThreadLocal 存储当前任务使用的模型
    private final ThreadLocal<String> currentModel = new ThreadLocal<>();

    // ThreadLocal 存储提示词日志回调，用于子Agent记录完整的system/user prompt
    private final ThreadLocal<java.util.function.Consumer<String>> promptLogger = new ThreadLocal<>();

    /**
     * 设置当前任务使用的模型
     */
    public void setCurrentModel(String model) {
        currentModel.set(model);
        logger.info("设置当前任务模型: {}", model);
    }

    /**
     * 获取当前任务使用的模型
     */
    public String getCurrentModel() {
        return currentModel.get();
    }

    /**
     * 清除当前任务的模型设置
     */
    public void clearCurrentModel() {
        currentModel.remove();
    }

    /**
     * 设置提示词日志回调
     */
    public void setPromptLogger(java.util.function.Consumer<String> logger) {
        promptLogger.set(logger);
    }

    /**
     * 清除提示词日志回调
     */
    public void clearPromptLogger() {
        promptLogger.remove();
    }

    /**
     * 记录提示词到日志回调
     */
    private void logPrompts(String systemPrompt, String userPrompt) {
        java.util.function.Consumer<String> logger = promptLogger.get();
        if (logger != null) {
            logger.accept("----- System Prompt -----\n" + (systemPrompt != null ? systemPrompt : "(null)"));
            logger.accept("----- User Prompt -----\n" + (userPrompt != null ? userPrompt : "(null)"));
        }
    }

    private MaterialService materialService;

    public NovelGeneratorStepService(AIPromptService aiPromptService, ObjectMapper objectMapper) {
        this.aiPromptService = aiPromptService;
        this.objectMapper = objectMapper;
    }

    @Autowired
    public void setMaterialService(MaterialService materialService) {
        this.materialService = materialService;
    }

    /**
     * 调用 DeepSeek API（使用当前任务设置的模型）
     */
    private String callAPI(String systemPrompt, String userPrompt,
                           boolean enableThinking, String reasoningEffort, int maxTokens) throws Exception {
        // 记录提示词到日志回调
        logPrompts(systemPrompt, userPrompt);

        String model = currentModel.get();
        if (model != null && !model.isEmpty()) {
            return aiPromptService.callDeepSeekAPIWithModel(systemPrompt, userPrompt,
                enableThinking, reasoningEffort, maxTokens, false, model);
        } else {
            // 当未设置模型时，使用默认模型调用
            return aiPromptService.callDeepSeekAPIWithThinking(systemPrompt, userPrompt,
                enableThinking, reasoningEffort, maxTokens, false);
        }
    }

    /**
     * 调用 DeepSeek API（使用当前任务设置的模型，支持 JSON 模式）
     */
    private String callAPIWithJsonRetry(String systemPrompt, String userPrompt,
                                         boolean enableThinking, String reasoningEffort,
                                         int maxTokens, int maxRetries) throws Exception {
        // 记录提示词到日志回调
        logPrompts(systemPrompt, userPrompt);

        String model = currentModel.get();
        if (model != null && !model.isEmpty()) {
            // 对于带模型参数的 JSON 重试调用
            String currentPrompt = userPrompt;
            String lastError = null;
            int attempt = 0;

            while (attempt < maxRetries) {
                attempt++;
                logger.info("JSON生成尝试 {}/{}, 模型: {}", attempt, maxRetries, model);

                try {
                    String response = aiPromptService.callDeepSeekAPIWithModel(
                        systemPrompt, currentPrompt,
                        enableThinking, reasoningEffort,
                        maxTokens, true, model
                    );

                    if (response == null || response.trim().isEmpty()) {
                        lastError = "API返回空响应";
                        logger.warn("尝试 {}: {}", attempt, lastError);
                        continue;
                    }

                    // 验证JSON格式
                    objectMapper.readTree(response);
                    logger.info("JSON验证成功，尝试次数: {}", attempt);
                    return response;

                } catch (Exception e) {
                    lastError = e.getMessage();
                    logger.warn("尝试 {} JSON解析失败: {}", attempt, lastError);
                    // 构建重试提示
                    if (attempt < maxRetries) {
                        currentPrompt = userPrompt + "\n\n上次生成失败，错误：" + lastError +
                            "。请严格按照JSON格式输出，不要包含任何其他内容。";
                    }
                }
            }
            throw new RuntimeException("JSON生成失败，已重试" + maxRetries + "次: " + lastError);
        } else {
            return aiPromptService.callDeepSeekAPIWithJsonRetry(systemPrompt, userPrompt,
                enableThinking, reasoningEffort, maxTokens, maxRetries);
        }
    }

    // ==================== Step 1: 工具生成 ====================

    /**
     * 生成工具列表
     * @param toolsType 工具类型
     * @return 生成的工具列表文本
     */
    public String generateTools(String toolsType) throws Exception {
        logger.info("开始生成工具列表, 类型: {}", toolsType);

        String systemPrompt = SYSTEM_PREFIX + "输出和" + toolsType + "的玩法相关的道具，以及这些道具的玩法。道具的数量要有20种，不可以重复。工具都要使用中文。\n\n" +
            "以下是要生成的格式的。其中「用法」的字数不得超过20。\n\n" +
            "①「工具」：「用法」；\n\n" +
            "②「工具」：「用法」；";

        String result = callAPI(systemPrompt, null, false, null, MAX_TOKENS);
        logger.info("工具列表生成完成, 长度: {}", result.length());
        return result;
    }

    // ==================== Step 2: 玩法生成 ====================

    /**
     * 生成玩法列表
     * @param gameplayType 玩法类型
     * @return 生成的玩法列表文本
     */
    public String generateGameplay(String gameplayType) throws Exception {
        logger.info("开始生成玩法列表, 类型: {}", gameplayType);

        String systemPrompt = SYSTEM_PREFIX + "输出和" + gameplayType + "的玩法，要有20种，不可以重复。玩法都要使用中文。\n\n" +
            "以下是要生成的格式的。其中解释字数不能超过30。\n\n" +
            "①玩法：解释\n\n" +
            "②玩法：解释";

        String result = callAPI(systemPrompt, null, false, null, MAX_TOKENS);
        logger.info("玩法列表生成完成, 长度: {}", result.length());
        return result;
    }

    // ==================== Step 3: 世界观生成 ====================

    /**
     * 生成世界观
     * @param keyword 关键词/故事大纲
     * @return 生成的世界观文本
     */
    public String generateWorldview(String keyword) throws Exception {
        logger.info("开始生成世界观");

        String systemPrompt = SYSTEM_PREFIX + "基于以下关键词，使用参照以下内容，生成世界观。要严格按照markdown格式。不需要完全拘泥于以下的模板。输出的内容里面只需要包括世界观内容，不要包含其他的任何东西。";

        String result = callAPI(systemPrompt, keyword, true, "high", MAX_TOKENS);
        logger.info("世界观生成完成, 长度: {}", result.length());
        return result;
    }

    // ==================== Step 4: 角色卡生成 ====================

    /**
     * 生成角色卡列表
     * @param keyword 关键词
     * @param worldview 世界观
     * @param roleCount 角色数量
     * @param tools 工具列表
     * @param gameplay 玩法列表
     * @param protagonist 主角名称
     * @return 角色卡列表
     */
    public List<CharacterCard> generateCharacterCards(String keyword, String worldview,
                                                       int roleCount, String tools,
                                                       String gameplay, String protagonist) throws Exception {
        logger.info("开始生成角色卡, 数量: {}", roleCount);

        String systemPrompt = SYSTEM_PREFIX + "根据世界观，生成在这个世界观下的故事的人物,后面要根据这些人物来写小说。生成" + roleCount + "个角色。可以包含以下内容来生成角色卡。不需要严格按照以下的格式。输出的内容里面只需要包括角色卡内容，不要包含其他的任何东西。\n" +
            "生成的角色要围绕：关键词\n" +
            "可以附带的工具：" + tools + "\n" +
            "可以附带的玩法:" + gameplay + "\n" +
            "主角:" + protagonist + "\n" +
            "生成的角色卡的顺序，要把主角放在第一。\n" +
            "生成的角色名称，要以中国人的命名的风格来。人种要以中国人为主。\n\n" +
            "【外貌描写要求 - 重要】\n" +
            "外貌描写用于AI生成角色图片，必须准确、具体，禁止使用比喻：\n" +
            "- 眼睛：描述眼型（丹凤眼、桃花眼、杏眼等）、瞳色、眼尾特点\n" +
            "- 眉毛：描述眉形（柳叶眉、剑眉、平眉等）、粗细、颜色\n" +
            "- 鼻子：描述鼻梁高度、鼻头大小\n" +
            "- 嘴巴：描述唇形、唇色、厚薄\n" +
            "- 脸型：描述脸型（瓜子脸、圆脸、方脸等）、下颌线条\n" +
            "- 发型：描述长度、颜色、卷直、造型\n" +
            "- 身高：给出具体数字\n" +
            "- 体型：描述身材特点\n" +
            "- 穿着：描述日常服装风格、配饰\n\n" +
            "【错误示例】\"美若天仙\"\"眼睛像星星\"（太抽象或比喻）\n" +
            "【正确示例】\"丹凤眼，深褐色瞳孔，眼尾微微上挑。柳叶眉。瓜子脸。齐腰黑发，微卷。身高168cm。穿着白色衬衫配淡蓝色长裙。\"\n\n" +
            "生成的内容里面出现的所有标点符号，都要使用英文输入法的。\n" +
            "要求输出的格式严格按照以下的JSON来：\n\n" +
            "[\n" +
            "  {\n" +
            "    \"name\": \"角色姓名\",\n" +
            "    \"role\": \"protagonist\",\n" +
            "    \"alternativeNames\": [\"别名1\", \"别名2\"],\n" +
            "    \"age\": 0,\n" +
            "    \"gender\": \"性别\",\n" +
            "    \"occupation\": \"职业\",\n" +
            "    \"appearance\": {\n" +
            "      \"height\": \"身高\",\n" +
            "      \"hair\": \"发色和发型\",\n" +
            "      \"eyes\": \"眼睛描述\",\n" +
            "      \"face\": \"脸型描述\",\n" +
            "      \"build\": \"体型\",\n" +
            "      \"clothing\": \"服装\",\n" +
            "      \"legwear\": \"腿部穿着（丝袜、袜子等）\",\n" +
            "      \"shoes\": \"鞋子\",\n" +
            "      \"accessories\": \"配饰\",\n" +
            "      \"distinguishingFeatures\": \"显著特征\"\n" +
            "    },\n" +
            "    \"appearanceDescription\": \"完整的外貌文字描述，用于AI生成图片\",\n" +
            "    \"personality\": \"性格描述\",\n" +
            "    \"background\": \"背景故事\",\n" +
            "    \"relationships\": [\n" +
            "      {\n" +
            "        \"targetName\": \"其他角色姓名\",\n" +
            "        \"relationship\": \"关系描述\"\n" +
            "      }\n" +
            "    ],\n" +
            "    \"notes\": \"备注\"\n" +
            "  }\n" +
            "]";

        String userPrompt = "关键词：" + keyword + "\n\n世界观：" + worldview;

        // 启用JSON模式并带重试机制确保输出有效的JSON
        String result = aiPromptService.callDeepSeekAPIWithJsonRetry(systemPrompt, userPrompt, true, "high", MAX_TOKENS, 3);

        // 解析JSON为角色卡列表
        List<CharacterCard> cards = parseCharacterCards(result);
        logger.info("角色卡生成完成, 数量: {}", cards.size());
        return cards;
    }

    /**
     * 解析角色卡JSON
     */
    private List<CharacterCard> parseCharacterCards(String json) throws Exception {
        List<CharacterCard> cards = new ArrayList<>();

        // 尝试提取JSON数组
        String jsonArray = extractJsonArray(json);

        if (jsonArray == null) {
            logger.warn("无法从响应中提取JSON数组，尝试直接解析");
            jsonArray = json;
        }

        try {
            JsonNode arrayNode = objectMapper.readTree(jsonArray);
            if (arrayNode.isArray()) {
                for (JsonNode node : arrayNode) {
                    CharacterCard card = new CharacterCard();
                    card.setName(node.path("name").asText());
                    card.setRole(node.path("role").asText());  // 角色类型
                    card.setAge(node.path("age").asInt(0));
                    card.setGender(node.path("gender").asText());
                    card.setOccupation(node.path("occupation").asText());
                    card.setPersonality(node.path("personality").asText());
                    card.setBackground(node.path("background").asText());
                    card.setNotes(node.path("notes").asText());

                    // 外貌文字描述（用于AI生成图片）
                    card.setAppearanceDescription(node.path("appearanceDescription").asText());

                    // 解析外貌结构
                    JsonNode appearanceNode = node.path("appearance");
                    if (!appearanceNode.isMissingNode()) {
                        CharacterCardAppearance appearance = new CharacterCardAppearance();
                        appearance.setHeight(appearanceNode.path("height").asText());
                        appearance.setHair(appearanceNode.path("hair").asText());
                        appearance.setEyes(appearanceNode.path("eyes").asText());
                        appearance.setFace(appearanceNode.path("face").asText());
                        appearance.setBuild(appearanceNode.path("build").asText());
                        appearance.setClothing(appearanceNode.path("clothing").asText());
                        appearance.setLegwear(appearanceNode.path("legwear").asText());
                        appearance.setShoes(appearanceNode.path("shoes").asText());
                        appearance.setAccessories(appearanceNode.path("accessories").asText());
                        appearance.setDistinguishingFeatures(appearanceNode.path("distinguishingFeatures").asText());
                        card.setAppearance(appearance);
                    }

                    cards.add(card);
                }
            }
        } catch (Exception e) {
            logger.error("解析角色卡JSON失败: {}", e.getMessage());
            throw new RuntimeException("解析角色卡失败: " + e.getMessage());
        }

        return cards;
    }

    /**
     * 从文本中提取JSON数组 [...]
     */
    private String extractJsonArray(String text) {
        if (text == null) return null;

        // 找到第一个 [ 和匹配的 ]
        int start = text.indexOf('[');
        if (start == -1) return null;

        int depth = 0;
        int end = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
        }

        if (end > start) {
            return text.substring(start, end + 1);
        }

        // 如果没有找到匹配的 ]，返回从 [ 开始到末尾的内容
        logger.warn("JSON数组未正确闭合，返回从 [ 开始的所有内容");
        return text.substring(start);
    }

    // ==================== Step 5: 大纲和章节生成 ====================

    /**
     * 生成大纲和章节列表
     * @param keyword 关键词
     * @param worldview 世界观
     * @param charactersJson 角色卡JSON
     * @param chapterCount 章节数量
     * @param genre 体裁
     * @param requires 扩写需求
     * @param tools 工具
     * @param gameplay 玩法
     * @param pointOfView 叙述视角
     * @param protagonist 主角
     * @return 大纲结果
     */
    public GeneratedOutline generateOutline(String keyword, String worldview, String charactersJson,
                                            int chapterCount, String genre, String requires,
                                            String tools, String gameplay, String pointOfView,
                                            String protagonist) throws Exception {
        logger.info("开始生成大纲和章节列表, 章节数: {}", chapterCount);

        String systemPrompt = SYSTEM_PREFIX + "根据关键词得出来的内容，我需要进行续扩写。对故事的扩写保持输入的内容的基本基调，不要偏离基调。\n" +
            "文章中要加入更多具体的例子、生动的描述和感官细节等。使用具体的例子和生动的故事。\n" +
            "可用的创作工具：" + tools + "\n" +
            "玩法：" + gameplay + "\n" +
            "世界观:" + worldview + "\n" +
            "可用的人物角色卡:" + charactersJson + "\n" +
            "叙述视角：" + pointOfView + "\n" +
            "主角:" + protagonist + "\n\n" +
            "## 写作原则\n" +
            "- 采用海明威式冰山理论：只写露出水面的八分之一，用动作和细节代替心理描写\n" +
            "- 禁止解释性总结句和空泛情感描述\n" +
            "- 章节概括只描写动作和事实，不要带有总结性话语";

        String userPrompt = "先生成整个故事的大纲，然后帮我生成" + chapterCount + "个章节，章节标题和章节概括。\n\n" +
            "根据" + keyword + "来创作一个故事。\n\n" +
            "# 生成范式：\n" +
            " 章节标题要准确，概括章节的主要内容。标题要口语化。\n" +
            "1. 章节概括要求不得少于100字。章节概括不要带有任何总结的话语，仅需描写动作和事实即可。\n" +
            "2. 设计各个章节之间的时候要有联系。后面的章节可以适度地回应前文。\n" +
            "3 . 在个别章节，可以适当地带出和显示角色的工作等。\n" +
            "必须严格按照以下JSON格式输出：\n" +
            "{\n" +
            "  \"outline\": \"故事大纲内容\",\n" +
            "  \"chapters\": [\n" +
            "    {\"section\": \"章节标题\", \"description\": \"章节概括\"}\n" +
            "  ]\n" +
            "}";

        // 启用JSON模式并带重试机制确保输出有效的JSON
        String result = aiPromptService.callDeepSeekAPIWithJsonRetry(systemPrompt, userPrompt, true, "max", MAX_TOKENS, 3);

        // 解析大纲和章节
        GeneratedOutline outline = parseOutline(result);
        logger.info("大纲生成完成, 章节数: {}", outline.getChapters() != null ? outline.getChapters().size() : 0);
        return outline;
    }

    /**
     * 解析大纲JSON
     */
    private GeneratedOutline parseOutline(String json) throws Exception {
        GeneratedOutline outline = new GeneratedOutline();

        try {
            // 记录原始响应用于调试
            logger.info("AI返回的原始内容长度: {} 字符", json != null ? json.length() : 0);

            if (json == null || json.trim().isEmpty()) {
                throw new RuntimeException("AI返回的内容为空");
            }

            // 先尝试从文本中提取JSON对象
            String jsonContent = extractJsonObject(json);

            if (jsonContent == null) {
                // 如果没有找到JSON对象，尝试提取JSON数组
                jsonContent = extractJsonArray(json);
            }

            if (jsonContent == null) {
                logger.error("无法从响应中提取JSON内容");
                logger.error("响应内容前500字符: {}", json.length() > 500 ? json.substring(0, 500) : json);
                throw new RuntimeException("AI返回的内容中未找到有效的JSON格式");
            }

            // 尝试修复常见的JSON问题
            jsonContent = repairJson(jsonContent);

            logger.info("提取到的JSON内容长度: {} 字符", jsonContent.length());
            logger.debug("提取到的JSON内容: {}", jsonContent.length() > 1000 ? jsonContent.substring(0, 1000) + "..." : jsonContent);

            JsonNode rootNode = objectMapper.readTree(jsonContent);

            // 提取大纲
            if (rootNode.has("outline")) {
                outline.setOutline(rootNode.get("outline").asText());
            }

            // 提取章节列表 - 支持 "chapters" 或 "chapter" 字段
            JsonNode chaptersNode = null;
            if (rootNode.has("chapters")) {
                chaptersNode = rootNode.get("chapters");
            } else if (rootNode.has("chapter")) {
                chaptersNode = rootNode.get("chapter");
            }

            if (chaptersNode != null && chaptersNode.isArray()) {
                List<GeneratedOutline.ChapterInfo> chapters = new ArrayList<>();
                for (JsonNode node : chaptersNode) {
                    GeneratedOutline.ChapterInfo chapter = new GeneratedOutline.ChapterInfo();
                    chapter.setSection(node.path("section").asText());
                    chapter.setDescription(node.path("description").asText());
                    chapters.add(chapter);
                }
                outline.setChapters(chapters);
            } else if (rootNode.isArray()) {
                // 如果直接是数组，当作章节列表处理
                List<GeneratedOutline.ChapterInfo> chapters = new ArrayList<>();
                for (JsonNode node : rootNode) {
                    GeneratedOutline.ChapterInfo chapter = new GeneratedOutline.ChapterInfo();
                    chapter.setSection(node.path("section").asText());
                    chapter.setDescription(node.path("description").asText());
                    chapters.add(chapter);
                }
                outline.setChapters(chapters);
            }

            // 如果没有提取到章节，记录警告
            if (outline.getChapters() == null || outline.getChapters().isEmpty()) {
                logger.warn("未能从JSON中提取到章节列表");
            }

        } catch (Exception e) {
            logger.error("解析大纲JSON失败: {}", e.getMessage());
            // 打印更多上下文帮助调试
            if (json != null && json.length() > 0) {
                logger.error("原始JSON前1000字符: {}", json.length() > 1000 ? json.substring(0, 1000) : json);
            }
            throw new RuntimeException("解析大纲失败: " + e.getMessage());
        }

        return outline;
    }

    /**
     * 尝试修复常见的JSON问题
     */
    private String repairJson(String json) {
        if (json == null) return null;

        String repaired = json.trim();

        // 计算当前括号状态
        int braceCount = 0;   // { }
        int bracketCount = 0; // [ ]
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < repaired.length(); i++) {
            char c = repaired.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') braceCount++;
                else if (c == '}') braceCount--;
                else if (c == '[') bracketCount++;
                else if (c == ']') bracketCount--;
            }
        }

        // 如果括号已经平衡，直接返回
        if (braceCount == 0 && bracketCount == 0 && !inString) {
            return repaired;
        }

        logger.warn("JSON格式不完整，尝试修复... braceCount={}, bracketCount={}, inString={}", braceCount, bracketCount, inString);

        // 如果在字符串中间截断，先关闭字符串
        if (inString) {
            repaired += "\"";
        }

        // 补全未闭合的括号（注意顺序：先关数组，再关对象）
        // 但要根据实际的嵌套关系来决定顺序
        // 简单处理：先补 ] 再补 }
        for (int i = 0; i < bracketCount; i++) {
            repaired += "]";
        }
        for (int i = 0; i < braceCount; i++) {
            repaired += "}";
        }

        logger.info("JSON修复完成，添加了 {} 个 ] 和 {} 个 }}", bracketCount, braceCount);

        return repaired;
    }

    /**
     * 从文本中提取JSON对象 {...}
     */
    private String extractJsonObject(String text) {
        if (text == null) return null;

        // 找到第一个 { 和匹配的 }
        int start = text.indexOf('{');
        if (start == -1) return null;

        int depth = 0;
        int end = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
        }

        if (end > start) {
            return text.substring(start, end + 1);
        }

        // 如果没有找到匹配的 }，返回从 { 开始到末尾的内容
        logger.warn("JSON对象未正确闭合，返回从 {{ 开始的所有内容");
        return text.substring(start);
    }

    // ==================== Step 6: 生成小说标题 ====================

    /**
     * 生成小说标题
     * @param outline 大纲
     * @param pointOfView 叙述视角
     * @return 小说标题
     */
    public String generateNovelTitle(String outline, String pointOfView) throws Exception {
        logger.info("开始生成小说标题");

        String systemPrompt = SYSTEM_PREFIX + "生成这个大纲和标题所代表的小说名字。只需要说出标题即可，标题可以带有隐喻和概括。生成的小说标题不要带有任何标点符号。叙述视角：" + pointOfView;

        String result = callAPI(systemPrompt, outline, false, null, MAX_TOKENS);
        // 清理可能的引号
        result = result.replaceAll("[\"'「」『』【】]", "").trim();
        logger.info("小说标题生成完成: {}", result);
        return result;
    }

    // ==================== Step 7: 章节扩写 ====================

    /**
     * 提取当前章节需要的角色和世界观
     * @param allCharacters 所有角色卡JSON
     * @param worldview 世界观
     * @param chapterInfo 当前章节信息
     * @return 精简后的角色和世界观描述
     */
    public String extractRelevantContext(String allCharacters, String worldview,
                                          GeneratedOutline.ChapterInfo chapterInfo) throws Exception {
        logger.debug("开始提取章节相关上下文");

        String systemPrompt = SYSTEM_PREFIX + "你是一个人物角色总结大师。我下面给出了人物的角色卡、世界观和故事的章节概括。帮我基于章节概括里面出现的人物和世界观，只抽取在本章节内需要体现的特质。我的目的是缩小人物和世界观的所占的上下文，然后结合章节概括，提供给另一个AI来扩展生成符合人物和世界观的小说。\n\n" +
            "按照以下模板生成：\n\n" +
            "  基于以下角色卡和故事背景，生成[章节内容/对话/情节发展]：\n\n" +
            "  **故事背景**：[简要描述世界观、当前阶段]\n\n" +
            "  **角色卡参考**：\n" +
            "  1. [角色A姓名]：[关键特质摘要]\n" +
            "  2. [角色B姓名]：[关键特质摘要]\n\n" +
            "  **当前情境**：[具体场景、冲突、需要解决的问题]\n\n" +
            "  **生成要求**：\n" +
            "  - 保持角色性格一致性\n" +
            "  - 符合角色能力和限制\n" +
            "  - 推动预设的角色发展弧线";

        String userPrompt = "故事的角色卡：" + allCharacters + "\n\n世界观：" + worldview + "\n\n当前的故事章节和大纲：" +
            chapterInfo.getSection() + "\n" + chapterInfo.getDescription();

        String result = callAPI(systemPrompt, userPrompt, true, "high", MAX_TOKENS);
        return result;
    }

    /**
     * 扩写章节（两阶段生成）
     * 第一阶段：AI 分析章节内容，输出需要的素材关键词
     * 第二阶段：加载素材后，正式扩写
     *
     * @param chapterInfo 章节信息
     * @param outline 整体大纲
     * @param relevantContext 相关上下文
     * @param tools 工具
     * @param gameplay 玩法
     * @param genre 体裁
     * @param requires 扩写需求
     * @param pointOfView 叙述视角
     * @param languageStyle 语言风格
     * @param wordsPerChapter 每章字数要求
     * @return 扩写后的章节内容
     */
    public String expandChapter(GeneratedOutline.ChapterInfo chapterInfo, String outline,
                                String relevantContext, String tools, String gameplay,
                                String genre, String requires, String pointOfView,
                                String languageStyle, int wordsPerChapter) throws Exception {
        logger.info("开始扩写章节: {}", chapterInfo.getSection());

        // ========== 第一阶段：AI 分析需要的素材 ==========
        String materialsContent = "";
        if (materialService != null) {
            materialsContent = analyzeMaterialsByAI(chapterInfo, outline, relevantContext, genre);
        }

        // ========== 第二阶段：带素材正式扩写 ==========
        String systemPrompt = SYSTEM_PREFIX + "可用的创作工具：" + tools + "\n" +
            "玩法：" + gameplay + "\n" +
            requires + "\n" +
            "# 以" + genre + "的格式进行描写。\n" +
            "体裁: " + genre + "\n" +
            "语言风格：" + languageStyle + "\n" +
            "每章字数要求：" + wordsPerChapter + "字以上\n" +
            "故事扩写里面的人物要严格按照角色卡进行：" + relevantContext + "\n" +
            "叙述视角：" + pointOfView + "\n\n" +
            "## 写作风格指南（冰山理论）\n\n" +
            "### 【叙述者人格设定】\n\n" +
            "你是一个海明威式的叙述者——只记录可见的动作、对话、环境，绝不直接解释人物心理。就像冰山理论：只写露出水面的八分之一。\n\n" +
            "### 【绝对禁止清单】\n\n" +
            "1. **禁止对比转折句式**：\n" +
            "   - 禁止：\"他感到的不是愤怒，而是……\"\n" +
            "   - 禁止：\"她并非软弱，而是……\"\n" +
            "   - 禁止：\"这不仅仅是一次失败，更是……\"\n" +
            "   - **绝对禁止\"不是……而是……\"句式**，无论出现在叙述还是对话中都不允许\n" +
            "   - 禁止任何\"不是……是……\"的变形句式\n" +
            "2. **禁止解释性总结句**：不要对人物情感或行为进行解释概括\n" +
            "3. **禁止工整结构**：不要使用\"首先、其次、最后\"、\"第一、第二、第三\"等刻板框架\n" +
            "4. **禁止过度过渡词**：避免\"此外\"、\"因此\"、\"总的来说\"、\"综上所述\"等论文式表达\n" +
            "5. **禁止空泛情感描述**：如\"他感到非常震惊\"、\"她内心十分复杂\"等下定义式描述\n" +
            "6. **禁止陈述句式**：人物对话不要有陈述句，要口语化、有个性\n\n" +
            "### 【内心转折表达规则】\n\n" +
            "如果必须表达人物的内心转折，**只能用以下方式**：\n" +
            "- 人物做了什么反常的事\n" +
            "- 哪个习惯动作突然停了\n" +
            "- 眼神落向哪里\n" +
            "- 和什么物品产生了怎样的互动\n\n" +
            materialsContent;

        String userPrompt = "## 整体大纲\n" + outline + "\n## 需要扩展的段落\n" +
            chapterInfo.getSection() + "\n" + chapterInfo.getDescription() + "\n\n" +
            "生成的内容的第一句要标题开头。然后换行。\n" +
            "如：\n" +
            "```\n" +
            "# 第一章： xxxx\n" +
            "   xxxxx\n\n" +
            "```";

        String result = callAPI(systemPrompt, userPrompt, true, "max", MAX_TOKENS);
        logger.info("章节扩写完成, 长度: {}", result.length());
        return result;
    }

    /**
     * 第一阶段：让 AI 分析章节内容，输出需要的素材关键词
     * 然后根据 AI 返回的关键词加载素材
     */
    private String analyzeMaterialsByAI(GeneratedOutline.ChapterInfo chapterInfo,
                                         String outline, String relevantContext, String genre) {
        try {
            // 构建分析提示词
            String analyzePrompt = "你是一个创作素材分析师。请分析以下章节内容，列出创作时需要参考的素材类型。\n\n" +
                "## 可用的素材类型\n" +
                "外貌、表情、神态、情绪、心理、性格、动作、声音、肢体\n" +
                "心动、暧昧、暗恋、甜蜜、牵手、拥抱、亲密\n" +
                "服饰、古代服饰、现代服饰、古装女性、古装男性、现代女性、现代男性\n" +
                "风景、天空、日月、山川、河海、天气、雨雪、季节、春天、夏天、秋天、冬天\n" +
                "古代、古代基础、五行、八卦、兵器、武器\n" +
                "美人、美女、打斗、战斗、武功、氛围、浪漫、颜色\n\n" +
                "## 章节信息\n" +
                "标题：" + chapterInfo.getSection() + "\n" +
                "描述：" + chapterInfo.getDescription() + "\n" +
                "体裁：" + genre + "\n\n" +
                "## 输出要求\n" +
                "请输出3-5个最相关的素材类型关键词，用逗号分隔，不要输出其他内容。\n" +
                "示例输出：外貌,古代服饰,情绪,风景";

            String analysisResult = callAPI(
                "你是一个精准的素材分析师，只输出关键词，不要任何解释。",
                analyzePrompt,
                false, null, 200
            );

            // 解析 AI 返回的关键词
            List<String> materialTypes = parseMaterialTypes(analysisResult);
            logger.info("AI 分析需要的素材类型: {}", materialTypes);

            if (materialTypes.isEmpty()) {
                // 如果 AI 分析失败，回退到关键词匹配
                return analyzeAndLoadMaterialsByKeyword(chapterInfo);
            }

            // 根据关键词加载素材
            StringBuilder materials = new StringBuilder();
            materials.append("## 【参考素材】\n\n");
            materials.append("以下是根据章节内容分析后加载的参考素材，请在创作中参考使用（要进行原创性改写，不要生硬堆砌）：\n\n");

            List<String> loadedScenes = new ArrayList<>();
            for (String type : materialTypes) {
                String material = loadMaterial(type.trim(), loadedScenes);
                if (!material.isEmpty()) {
                    materials.append(material);
                }
            }

            materials.append("\n**使用提示**：以上素材仅供参考，请根据剧情需要选择性使用，并进行原创性改写。\n\n");

            logger.info("加载素材完成，章节: {}, 加载的素材类型: {}", chapterInfo.getSection(), loadedScenes);
            return materials.toString();

        } catch (Exception e) {
            logger.warn("AI 分析素材失败，回退到关键词匹配: {}", e.getMessage());
            return analyzeAndLoadMaterialsByKeyword(chapterInfo);
        }
    }

    /**
     * 解析 AI 返回的素材类型关键词
     */
    private List<String> parseMaterialTypes(String aiResponse) {
        List<String> types = new ArrayList<>();
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            return types;
        }

        // 清理响应，移除可能的标点和空格
        String cleaned = aiResponse.replaceAll("[,.;:!?\u3000-\u303F\uFF00-\uFFEF\\s]+", ",");

        // 有效的素材类型列表
        List<String> validTypes = List.of(
            "外貌", "表情", "神态", "情绪", "心理", "性格", "动作", "声音", "肢体",
            "心动", "暧昧", "暗恋", "甜蜜", "牵手", "拥抱", "亲密",
            "服饰", "古代服饰", "现代服饰", "古装女性", "古装男性", "现代女性", "现代男性", "配饰", "鞋", "包", "穿搭",
            "风景", "天空", "日月", "山川", "河海", "天气", "雨雪", "季节", "春天", "夏天", "秋天", "冬天", "灾难", "末日",
            "古代", "古代基础", "五行", "八卦", "兵器", "武器", "古代时间",
            "美人", "美女", "打斗", "战斗", "武功", "氛围", "浪漫", "颜色"
        );

        // 分割并匹配有效类型
        String[] parts = cleaned.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (validTypes.contains(trimmed)) {
                types.add(trimmed);
            }
        }

        return types;
    }

    /**
     * 回退方案：根据关键词匹配加载素材
     */
    private String analyzeAndLoadMaterialsByKeyword(GeneratedOutline.ChapterInfo chapterInfo) {
        StringBuilder materials = new StringBuilder();
        materials.append("## 【参考素材】\n\n");
        materials.append("以下是根据章节内容自动加载的参考素材，请在创作中参考使用（要进行原创性改写，不要生硬堆砌）：\n\n");

        String section = chapterInfo.getSection() != null ? chapterInfo.getSection() : "";
        String description = chapterInfo.getDescription() != null ? chapterInfo.getDescription() : "";
        String combinedText = section + " " + description;

        List<String> loadedScenes = new ArrayList<>();

        // 情感相关
        if (containsAny(combinedText, "心动", "暧昧", "暗恋", "喜欢", "爱", "恋爱", "甜蜜", "情")) {
            materials.append(loadMaterial("暧昧", loadedScenes));
            materials.append(loadMaterial("心动", loadedScenes));
        }
        if (containsAny(combinedText, "伤心", "难过", "哭", "泪", "悲伤", "痛苦", "绝望")) {
            materials.append(loadMaterial("情绪", loadedScenes));
        }

        // 外貌相关
        if (containsAny(combinedText, "美女", "帅哥", "容貌", "外貌", "长相", "漂亮", "英俊", "美")) {
            materials.append(loadMaterial("外貌", loadedScenes));
        }
        if (containsAny(combinedText, "表情", "神态", "眼神", "目光", "微笑", "笑", "哭")) {
            materials.append(loadMaterial("表情", loadedScenes));
        }

        // 服饰相关
        if (containsAny(combinedText, "衣服", "穿着", "服饰", "裙", "西装", "古装", "汉服", "校服")) {
            if (containsAny(combinedText, "古", "古代", "汉服", "唐装", "宋", "明", "清")) {
                materials.append(loadMaterial("古代服饰", loadedScenes));
            } else {
                materials.append(loadMaterial("现代服饰", loadedScenes));
            }
        }

        // 场景相关
        if (containsAny(combinedText, "风景", "景色", "山", "水", "河", "海", "天空", "云", "日", "月", "星")) {
            materials.append(loadMaterial("风景", loadedScenes));
        }
        if (containsAny(combinedText, "雨", "雪", "风", "霜", "雾", "天气", "晴", "阴")) {
            materials.append(loadMaterial("天气", loadedScenes));
        }
        if (containsAny(combinedText, "春", "夏", "秋", "冬", "季节", "花开", "落叶", "雪")) {
            materials.append(loadMaterial("季节", loadedScenes));
        }

        // 动作相关
        if (containsAny(combinedText, "打", "斗", "战", "武", "拳", "剑", "刀", "战斗", "打架")) {
            materials.append(loadMaterial("打斗", loadedScenes));
        }
        if (containsAny(combinedText, "跑", "跳", "走", "坐", "站", "躺", "动作", "姿态")) {
            materials.append(loadMaterial("动作", loadedScenes));
        }

        // 声音相关
        if (containsAny(combinedText, "声音", "说话", "喊", "叫", "笑", "哭", "语", "声")) {
            materials.append(loadMaterial("声音", loadedScenes));
        }

        // 心理相关
        if (containsAny(combinedText, "想", "心", "回忆", "记忆", "思考", "内心", "心理")) {
            materials.append(loadMaterial("心理", loadedScenes));
        }

        // 如果没有匹配到任何素材，加载一些通用素材
        if (loadedScenes.isEmpty()) {
            materials.append(loadMaterial("情绪", loadedScenes));
            materials.append(loadMaterial("动作", loadedScenes));
        }

        materials.append("\n**使用提示**：以上素材仅供参考，请根据剧情需要选择性使用，并进行原创性改写。\n\n");

        logger.info("预加载素材完成（关键词匹配），章节: {}, 加载的素材类型: {}", section, loadedScenes);
        return materials.toString();
    }

    /**
     * 加载单个素材
     */
    private String loadMaterial(String scene, List<String> loadedScenes) {
        if (loadedScenes.contains(scene)) {
            return ""; // 避免重复加载
        }
        loadedScenes.add(scene);

        try {
            String content = materialService.getMaterialsByScene(scene);
            if (content != null && !content.isEmpty()) {
                // 截取部分内容，避免提示词过长
                if (content.length() > 1500) {
                    content = content.substring(0, 1500) + "\n...(内容已截断)";
                }
                return content + "\n\n";
            }
        } catch (Exception e) {
            logger.warn("加载素材失败: scene={}, error={}", scene, e.getMessage());
        }
        return "";
    }

    /**
     * 检查文本是否包含任意一个关键词
     */
    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Step 8: 去除AI味润色 ====================

    /**
     * 去除AI味润色
     * @param content 原始内容
     * @return 润色后的内容
     */
    public String polishContent(String content, int wordsPerChapter) throws Exception {
        logger.info("开始去除AI味润色");

        String systemPrompt = SYSTEM_PREFIX + "# 质量检查与润色\n\n" +
            "你是一名专业的文字润色师，需要对传入的内容进行深度润色。结果只输出修改后的文章，不要附带其他任何说明。\n\n" +
            "## 【叙述者人格设定 - 海明威式冰山理论】\n\n" +
            "你是一个海明威式的叙述者——只记录可见的动作、对话、环境，绝不直接解释人物心理。就像冰山理论：只写露出水面的八分之一。\n\n" +
            "## 【绝对禁止清单 - 必须检查并修改】\n\n" +
            "1. **禁止对比转折句式**：\n" +
            "   - 禁止：\"他感到的不是愤怒，而是……\"\n" +
            "   - 禁止：\"她并非软弱，而是……\"\n" +
            "   - 禁止：\"这不仅仅是一次失败，更是……\"\n" +
            "   - **绝对禁止\"不是……而是……\"句式**，无论出现在叙述还是对话中都必须删除或改写\n" +
            "   - 禁止任何\"不是……是……\"、\"与其说……不如说……\"的变形句式\n" +
            "   - 直接描述事实和感受，不要用对比来解释\n\n" +
            "2. **禁止解释性总结句**：\n" +
            "   - 不要对人物情感或行为进行解释概括\n" +
            "   - 删除所有\"这说明了...\"、\"这意味着...\"、\"由此可见...\"等总结性语句\n\n" +
            "3. **禁止空泛情感描述**：\n" +
            "   - 删除\"他感到非常震惊\"、\"她内心十分复杂\"、\"心中涌起复杂的情感\"等下定义式描述\n" +
            "   - 改为具体的动作、表情、互动\n\n" +
            "4. **禁止过度修饰**：\n" +
            "   - 删减「璀璨」、「瑰丽」、「绚烂」等AI常用词堆砌\n" +
            "   - 避免「心潮澎湃、热血沸腾」等陈词滥调\n\n" +
            "5. **禁止工整结构**：\n" +
            "   - 不要使用\"首先、其次、最后\"、\"第一、第二、第三\"等刻板框架\n" +
            "   - 避免\"此外\"、\"因此\"、\"总的来说\"、\"综上所述\"等论文式表达\n\n" +
            "6. **禁止陈述句式**：\n" +
            "   - 人物对话不要有陈述句，要口语化、有个性\n" +
            "   - 去掉类似「记住xxx」这样的对话，替换成更贴近人的话语\n\n" +
            "## 【内心转折表达规则】\n\n" +
            "如果必须表达人物的内心转折，**只能用以下方式**：\n" +
            "- 人物做了什么反常的事\n" +
            "- 哪个习惯动作突然停了\n" +
            "- 眼神落向哪里\n" +
            "- 和什么物品产生了怎样的互动\n\n" +
            "示例：\n" +
            "- ❌ \"她感到的不是失望，而是一种释然\"\n" +
            "- ✅ \"她把戒指摘下来，放在桌上，转了两圈，然后起身去倒水\"\n\n" +
            "## 【必须遵循的写作原则】\n\n" +
            "1. **用动作和细节代替心理描写**\n" +
            "2. **口语化表达**：人物对话要有个性，避免书面语套话\n" +
            "3. **句式长短交替**：长句和短句交替，避免句式单调\n" +
            "4. **细节具象化**：用具体的视觉/听觉/嗅觉细节替代笼统描述\n" +
            "5. **语言平实直述**：避免抽象隐喻，优先选择具体名词\n" +
            "6. **段落简明**：保持段落不超过5行\n\n" +
            "字数不得少于" + wordsPerChapter + "字。";

        String result = callAPI(systemPrompt, content, true, "high", MAX_TOKENS);
        logger.info("润色完成, 长度: {}", result.length());
        return result;
    }

    /**
     * 对已有内容进行续写扩写
     * @param content 已有内容
     * @param needWords 需要增加的字数
     * @param languageStyle 语言风格
     * @return 扩写后的内容
     */
    public String expandContent(String content, int needWords, String languageStyle) throws Exception {
        logger.info("开始续写扩写，需增加约 {} 字", needWords);

        String systemPrompt = SYSTEM_PREFIX + "你是一名专业的小说作家。当前文章字数不足，需要你进行续写扩写。\n" +
            "语言风格：" + languageStyle + "\n\n" +
            "要求：\n" +
            "1. 继续文章内容，自然衔接，不要重复已有内容\n" +
            "2. 增加至少 " + needWords + " 字的新内容\n" +
            "3. 保持故事情节连贯，人物性格一致\n" +
            "4. 可以增加更多细节描写、对话、场景描述\n" +
            "5. 输出完整的新文章（包括原有内容+新增内容）\n" +
            "6. 不要在开头或结尾添加任何说明";

        String userPrompt = "以下是现有文章内容，请继续扩写：\n\n" + content;

        String result = callAPI(systemPrompt, userPrompt, true, "max", MAX_TOKENS);
        logger.info("续写扩写完成, 长度: {}", result.length());
        return result;
    }

    /**
     * 格式化章节内容（句号后换行）
     */
    public String formatChapterContent(String content) {
        if (content == null) return null;
        // 在每个中文句号后面添加一个换行符
        return content.replaceAll("。", "。\n");
    }

    // ==================== 前情提要生成 ====================

    /**
     * 生成前情提要：将已有的前情提要和新章节内容合并为一段精简概括
     *
     * @param existingRecap 已有的前情提要（可为空，表示第一章）
     * @param chapterTitle 新章节标题
     * @param chapterContent 新章节内容（完整正文，会自动截取以节省token）
     * @param chapterSummary 新章节概括
     * @return 合并后的新前情提要
     */
    public String generateRecap(String existingRecap, String chapterTitle,
                                 String chapterContent, String chapterSummary) throws Exception {
        logger.info("开始生成前情提要, 现有提要长度={}, 新章节标题={}",
            existingRecap != null ? existingRecap.length() : 0, chapterTitle);

        String systemPrompt = SYSTEM_PREFIX +
            "你是一名故事概括专家。你的任务是将已有的前情提要和新章节的内容，合并为一段简洁的前情提要。\n\n" +
            "要求：\n" +
            "1. 保留所有关键情节线索、人物关系变化、重要事件\n" +
            "2. 删除细节描写，只保留推动剧情发展的核心事件\n" +
            "3. 按时间顺序组织，逻辑清晰\n" +
            "4. 控制在300-500字以内\n" +
            "5. 不要添加任何评价、解释或额外说明，只输出前情提要内容";

        String userPrompt;
        if (existingRecap == null || existingRecap.isEmpty()) {
            // 第一章：直接概括本章内容
            userPrompt = "请概括以下章节内容，作为后续章节的前情提要：\n\n" +
                "章节标题：" + chapterTitle + "\n" +
                "章节概括：" + (chapterSummary != null ? chapterSummary : "") + "\n" +
                "章节内容（节选）：" + truncateForRecap(chapterContent);
        } else {
            // 后续章节：合并已有提要 + 新章节
            userPrompt = "已有前情提要：\n" + existingRecap +
                "\n\n新章节标题：" + chapterTitle +
                "\n新章节概括：" + (chapterSummary != null ? chapterSummary : "") +
                "\n新章节内容（节选）：" + truncateForRecap(chapterContent) +
                "\n\n请将已有前情提要和新章节内容合并，生成一段更新后的前情提要。保留所有关键情节，控制在300-500字以内。";
        }

        String result = callAPI(systemPrompt, userPrompt, false, null, 2000);
        logger.info("前情提要生成完成, 长度: {}", result.length());
        return result;
    }

    /**
     * 截取章节内容用于前情提要生成（取首尾各一部分，节省token）
     */
    private String truncateForRecap(String content) {
        if (content == null || content.isEmpty()) return "(无内容)";
        int maxLen = 1500;
        if (content.length() <= maxLen) return content;
        // 取开头800字 + 结尾700字
        return content.substring(0, 800) + "\n...(中间省略)...\n" + content.substring(content.length() - 700);
    }
}
