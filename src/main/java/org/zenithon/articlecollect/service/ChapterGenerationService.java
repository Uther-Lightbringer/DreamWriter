package org.zenithon.articlecollect.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zenithon.articlecollect.entity.Chapter;
import org.zenithon.articlecollect.dto.CharacterCard;
import org.zenithon.articlecollect.dto.GeneratedOutline;
import org.zenithon.articlecollect.entity.CreativeSession;
import org.zenithon.articlecollect.entity.Novel;
import org.zenithon.articlecollect.repository.CreativeSessionRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子Agent章节生成服务
 *
 * 负责异步生成章节内容，复用 NovelGeneratorStepService 的扩写流程。
 * 主Agent只需传入标题+概括，子Agent在独立上下文中完成章节生成。
 */
@Service
public class ChapterGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(ChapterGenerationService.class);

    // 单章节生成超时时间（毫秒）
    private static final long GENERATION_TIMEOUT_MS = 5 * 60 * 1000;

    // 同一小说最大并发生成数
    private static final int MAX_CONCURRENT_PER_NOVEL = 5;

    // 日志文件根目录
    private static final String LOG_ROOT = "logs/chapter-generation";

    // 日志时间格式
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 内存存储任务
    private final ConcurrentHashMap<String, ChapterGenerationTask> tasks = new ConcurrentHashMap<>();

    // 每个小说的生成队列
    private final ConcurrentHashMap<Long, LinkedList<String>> novelQueues = new ConcurrentHashMap<>();

    // 每个小说当前正在生成的任务数
    private final ConcurrentHashMap<Long, Integer> novelActiveCount = new ConcurrentHashMap<>();

    // 每个任务的文件日志 Writer
    private final ConcurrentHashMap<String, java.io.PrintWriter> taskLogWriters = new ConcurrentHashMap<>();

    private final NovelGeneratorStepService stepService;
    private final NovelService novelService;
    private final CharacterCardService characterCardService;
    private final CreativeSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChapterGenerationService(NovelGeneratorStepService stepService,
                                     NovelService novelService,
                                     CharacterCardService characterCardService,
                                     CreativeSessionRepository sessionRepository,
                                     ObjectMapper objectMapper) {
        this.stepService = stepService;
        this.novelService = novelService;
        this.characterCardService = characterCardService;
        this.sessionRepository = sessionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建任务并启动异步生成
     *
     * @param novelId 小说ID
     * @param title 章节标题
     * @param summary 章节概括（含写作要求）
     * @param emitter SSE发射器
     * @param sessionId 创作会话ID，用于更新上下文
     * @param extractedParamsJson 会话参数JSON，用于获取体裁/风格等
     * @return 任务ID
     */
    public String startGeneration(Long novelId, String title, String summary,
                                   SseEmitter emitter, String sessionId,
                                   String extractedParamsJson) {
        String taskId = UUID.randomUUID().toString();
        ChapterGenerationTask task = new ChapterGenerationTask();
        task.setTaskId(taskId);
        task.setNovelId(novelId);
        task.setTitle(title);
        task.setSummary(summary);
        task.setStatus("queued");
        task.setCreatedAt(LocalDateTime.now());
        task.setEmitter(emitter);
        task.setSessionId(sessionId);
        task.setExtractedParamsJson(extractedParamsJson);

        tasks.put(taskId, task);

        // 加入队列
        synchronized (novelQueues) {
            novelQueues.computeIfAbsent(novelId, k -> new LinkedList<>()).add(taskId);
        }

        logger.info("[子Agent] 章节生成任务入队: taskId={}, novelId={}, title={}, queueSize={}",
            taskId, novelId, title, novelQueues.get(novelId).size());

        // 尝试启动下一个任务
        tryStartNext(novelId);

        return taskId;
    }

    /**
     * 尝试从队列中启动待生成的任务（填满并发槽位）
     */
    private void tryStartNext(Long novelId) {
        synchronized (novelQueues) {
            int active = novelActiveCount.getOrDefault(novelId, 0);

            // 填满并发槽位
            while (active < MAX_CONCURRENT_PER_NOVEL) {
                LinkedList<String> queue = novelQueues.get(novelId);
                if (queue == null || queue.isEmpty()) {
                    break;
                }

                String taskId = queue.poll();
                if (taskId == null) {
                    break;
                }

                ChapterGenerationTask task = tasks.get(taskId);
                if (task != null) {
                    active++;
                    novelActiveCount.put(novelId, active);
                    task.setStatus("pending");
                    logger.info("[子Agent] 启动队列任务: taskId={}, novelId={}, active={}/{}", taskId, novelId, active, MAX_CONCURRENT_PER_NOVEL);
                    asyncGenerate(taskId);
                }
                // task为null时跳过，继续尝试下一个
            }
        }
    }

    /**
     * 当前任务完成后，减少并发计数，并尝试启动队列中下一个任务
     */
    private void onGenerationComplete(Long novelId) {
        synchronized (novelQueues) {
            int active = novelActiveCount.merge(novelId, -1, (old, delta) -> Math.max(0, old + delta));
            logger.info("[子Agent] 任务完成, novelId={}, 剩余active={}", novelId, active);

            // 尝试填充空出的槽位
            tryStartNext(novelId);

            // 如果没有活跃任务且队列为空，清理
            if (active <= 0) {
                LinkedList<String> queue = novelQueues.get(novelId);
                if (queue == null || queue.isEmpty()) {
                    novelQueues.remove(novelId);
                    novelActiveCount.remove(novelId);
                }
            }
        }
    }

    /**
     * 查询任务状态
     */
    public ChapterGenerationTask getTaskStatus(String taskId) {
        return tasks.get(taskId);
    }

    /**
     * 异步执行章节生成
     */
    @Async("chapterGenerationTaskExecutor")
    public void asyncGenerate(String taskId) {
        ChapterGenerationTask task = tasks.get(taskId);
        if (task == null) {
            logger.error("任务不存在: {}", taskId);
            return;
        }

        try {
            task.setStatus("generating");
            sendProgressEvent(task, "extracting_context", "正在提取创作上下文...");

            Long novelId = task.getNovelId();
            String title = task.getTitle();
            String summary = task.getSummary();

            logger.info("========== 子Agent章节生成开始 ==========");
            logger.info("[子Agent] taskId={}, novelId={}, title={}", taskId, novelId, title);
            logger.info("[子Agent] summary={}", summary);

            // 1. 获取角色卡
            List<CharacterCard> cards = characterCardService.getCharacterCardsByNovelId(novelId);
            String charactersJson = objectMapper.writeValueAsString(cards);
            logger.info("[子Agent] 角色卡数量={}, JSON长度={}", cards.size(), charactersJson.length());
            logger.debug("[子Agent] 角色卡内容: {}", charactersJson.length() > 500 ? charactersJson.substring(0, 500) + "..." : charactersJson);

            // 2. 获取世界观
            Novel novel = novelService.getNovelById(novelId);
            String worldview = (novel != null && novel.getWorldView() != null) ? novel.getWorldView() : "";
            logger.info("[子Agent] 世界观长度={}, 小说标题={}", worldview.length(), novel != null ? novel.getTitle() : "null");
            logger.debug("[子Agent] 世界观内容: {}", worldview.length() > 300 ? worldview.substring(0, 300) + "..." : worldview);

            // 初始化文件日志（需要先获取小说标题）
            initFileLog(taskId, novelId, novel != null ? novel.getTitle() : "unknown", title);
            fileLog(taskId, "taskId: {}", taskId);
            fileLog(taskId, "summary: {}", summary);
            fileLog(taskId, "");

            // 3. 获取前情提要（替代逐条章节摘要）
            String previousRecap = (novel != null && novel.getPreviousRecap() != null) ? novel.getPreviousRecap() : "";
            logger.info("[子Agent] 前情提要长度={}", previousRecap.length());

            fileLog(taskId, "----- 前情提要 -----");
            fileLog(taskId, previousRecap.isEmpty() ? "(暂无，为首章)" : previousRecap);
            fileLog(taskId, "");

            // 4. 解析创作参数
            Map<String, Object> params = parseParams(task.getExtractedParamsJson());
            String genre = getParamAsString(params, "genre", getParamAsString(params, "theme", ""));
            String style = getParamAsString(params, "style", "");
            String pointOfView = getParamAsString(params, "pointOfView", "第三人称");
            String languageStyle = getParamAsString(params, "languageStyle", "文学性叙述");
            int wordsPerChapter = getParamAsInt(params, "wordsPerChapter", 3000);
            String tools = getParamAsString(params, "tools", "");
            String gameplay = getParamAsString(params, "gameplay", "");
            String requires = getParamAsString(params, "requires", "");
            String model = getParamAsString(params, "model", null);
            logger.info("[子Agent] 创作参数: genre={}, style={}, pointOfView={}, languageStyle={}, wordsPerChapter={}, model={}",
                genre, style, pointOfView, languageStyle, wordsPerChapter, model);
            logger.info("[子Agent] tools长度={}, gameplay长度={}, requires长度={}", tools.length(), gameplay.length(), requires.length());
            logger.debug("[子Agent] requires内容: {}", requires.length() > 300 ? requires.substring(0, 300) + "..." : requires);

            fileLog(taskId, "----- 创作参数 -----");
            fileLog(taskId, "genre: {}", genre);
            fileLog(taskId, "style: {}", style);
            fileLog(taskId, "pointOfView: {}", pointOfView);
            fileLog(taskId, "languageStyle: {}", languageStyle);
            fileLog(taskId, "wordsPerChapter: {}", wordsPerChapter);
            fileLog(taskId, "model: {}", model);
            fileLog(taskId, "tools: {}", tools.isEmpty() ? "(无)" : tools);
            fileLog(taskId, "gameplay: {}", gameplay.isEmpty() ? "(无)" : gameplay);
            fileLog(taskId, "requires: {}", requires.isEmpty() ? "(无)" : requires);
            fileLog(taskId, "extractedParams原始: {}", task.getExtractedParamsJson());
            fileLog(taskId, "");

            // 5. 构建 ChapterInfo
            GeneratedOutline.ChapterInfo chapterInfo = new GeneratedOutline.ChapterInfo(title, summary);

            // 6. 构建精简大纲（只含小说整体大纲 + 前情提要，不再逐条列章节摘要）
            String outline = buildCondensedOutline(novel, previousRecap);
            logger.info("[子Agent] 精简大纲长度={}", outline.length());

            fileLog(taskId, "----- 精简大纲（含前情提要） -----");
            fileLog(taskId, outline);
            fileLog(taskId, "");

            // 7. 提取精简上下文
            sendProgressEvent(task, "extracting_context", "正在分析角色和世界观...");
            fileLog(taskId, "----- 角色卡（完整） -----");
            fileLog(taskId, charactersJson);
            fileLog(taskId, "");
            fileLog(taskId, "----- 世界观 -----");
            fileLog(taskId, worldview.isEmpty() ? "(无世界观)" : worldview);
            fileLog(taskId, "");

            // 设置提示词日志回调，将stepService的所有API提示词写入文件日志
            stepService.setPromptLogger(prompt -> fileLog(taskId, prompt));

            String relevantContext = stepService.extractRelevantContext(charactersJson, worldview, chapterInfo);
            logger.info("[子Agent] 精简上下文长度={}", relevantContext.length());
            logger.debug("[子Agent] 精简上下文内容: {}", relevantContext.length() > 500 ? relevantContext.substring(0, 500) + "..." : relevantContext);

            fileLog(taskId, "----- 精简上下文（extractRelevantContext输出） -----");
            fileLog(taskId, relevantContext);
            fileLog(taskId, "");

            // 8. 扩写章节
            sendProgressEvent(task, "expanding", "正在创作章节内容...");
            fileLog(taskId, "===== 开始扩写 =====");
            stepService.setCurrentModel(model);
            try {
                String expandedContent = stepService.expandChapter(
                    chapterInfo, outline, relevantContext,
                    tools, gameplay, genre, requires, pointOfView,
                    languageStyle, wordsPerChapter
                );
                logger.info("[子Agent] 扩写完成, 内容长度={}", expandedContent.length());

                fileLog(taskId, "----- 扩写结果（长度={}） -----", expandedContent.length());
                fileLog(taskId, expandedContent);
                fileLog(taskId, "");

                // 9. 润色
                sendProgressEvent(task, "polishing", "正在润色章节内容...");
                fileLog(taskId, "===== 开始润色 =====");
                String polishedContent = stepService.polishContent(expandedContent, wordsPerChapter);
                logger.info("[子Agent] 润色完成, 内容长度={}", polishedContent.length());

                fileLog(taskId, "----- 润色结果（长度={}） -----", polishedContent.length());
                fileLog(taskId, polishedContent);
                fileLog(taskId, "");

                // 10. 字数校验：如果字数不足，自动续写补足（最多2次）
                int maxExpandRetries = 2;
                for (int retry = 0; retry < maxExpandRetries; retry++) {
                    int actualWords = polishedContent.length();
                    if (actualWords >= wordsPerChapter) {
                        fileLog(taskId, "===== 字数校验: 通过 =====");
                        fileLog(taskId, "实际字数: {}, 要求: {}", actualWords, wordsPerChapter);
                        fileLog(taskId, "");
                        break;
                    }

                    int deficit = wordsPerChapter - actualWords;
                    logger.info("[子Agent] 字数不足: 实际={}, 要求={}, 缺口={}, 第{}次续写", actualWords, wordsPerChapter, deficit, retry + 1);
                    fileLog(taskId, "===== 字数校验: 不足（第{}次） =====", retry + 1);
                    fileLog(taskId, "实际字数: {}, 要求: {}, 缺口: {}", actualWords, wordsPerChapter, deficit);

                    sendProgressEvent(task, "expanding", "字数不足，正在续写补足（第" + (retry + 1) + "次）...");
                    String expandedAgain = stepService.expandContent(polishedContent, deficit, languageStyle);
                    logger.info("[子Agent] 续写完成, 新长度={}", expandedAgain.length());

                    fileLog(taskId, "----- 续写结果（长度={}） -----", expandedAgain.length());
                    fileLog(taskId, expandedAgain);
                    fileLog(taskId, "");

                    polishedContent = expandedAgain;

                    // 最后一次仍不足，记录警告但不继续
                    if (retry == maxExpandRetries - 1 && polishedContent.length() < wordsPerChapter) {
                        logger.warn("[子Agent] 字数仍不足: 实际={}, 要求={}，已达最大续写次数", polishedContent.length(), wordsPerChapter);
                        fileLog(taskId, "===== 字数校验: 仍未达标（已达最大续写次数） =====");
                        fileLog(taskId, "最终字数: {}, 要求: {}", polishedContent.length(), wordsPerChapter);
                        fileLog(taskId, "");
                    }
                }

                // 11. 保存章节
                sendProgressEvent(task, "saving", "正在保存章节...");
                Chapter chapter = novelService.createChapter(novelId, title, polishedContent, null, summary);

                // 12. 生成前情提要：将已有提要+本章内容合并为新的提要，供下一章使用
                sendProgressEvent(task, "generating_recap", "正在生成前情提要...");
                fileLog(taskId, "===== 开始生成前情提要 =====");
                try {
                    String newRecap = stepService.generateRecap(previousRecap, title, polishedContent, summary);
                    novel.setPreviousRecap(newRecap);
                    novelService.updateNovel(novel);
                    logger.info("[子Agent] 前情提要已更新, 长度={}", newRecap.length());

                    fileLog(taskId, "----- 新前情提要（长度={}） -----", newRecap.length());
                    fileLog(taskId, newRecap);
                    fileLog(taskId, "");
                } catch (Exception e) {
                    // 前情提要生成失败不影响章节保存
                    logger.warn("[子Agent] 前情提要生成失败，不影响章节: {}", e.getMessage());
                    fileLog(taskId, "前情提要生成失败: {}", e.getMessage());
                }

                // 13. 更新任务状态
                task.setStatus("completed");
                task.setChapterId(chapter.getId());
                task.setCompletedAt(LocalDateTime.now());

                // 14. 更新 CreativeSession 上下文
                updateSessionContext(task.getSessionId(), chapter.getId());

                // 15. 发送完成事件
                logger.info("[子Agent] 章节保存成功: chapterId={}, wordCount={}", chapter.getId(), polishedContent.length());
                logger.info("========== 子Agent章节生成完成 ==========");
                sendGeneratedEvent(task, chapter.getId(), title, polishedContent.length());

                fileLog(taskId, "===== 生成完成 =====");
                fileLog(taskId, "chapterId: {}", chapter.getId());
                fileLog(taskId, "wordCount: {}", polishedContent.length());

                logger.info("章节生成完成: taskId={}, novelId={}, chapterId={}, title={}, wordCount={}",
                    taskId, novelId, chapter.getId(), title, polishedContent.length());

            } finally {
                stepService.clearCurrentModel();
                stepService.clearPromptLogger();
            }

        } catch (Exception e) {
            logger.error("章节生成失败: taskId={}, error={}", taskId, e.getMessage(), e);
            task.setStatus("failed");
            task.setError(e.getMessage());
            task.setCompletedAt(LocalDateTime.now());
            sendFailedEvent(task, e.getMessage());

            fileLog(taskId, "===== 生成失败 =====");
            fileLog(taskId, "错误: {}", e.getMessage());
            if (e.getStackTrace().length > 0) {
                fileLog(taskId, "位置: {}", e.getStackTrace()[0]);
            }
        } finally {
            // 标记当前任务完成，启动队列中下一个任务
            onGenerationComplete(task.getNovelId());
            // 关闭文件日志
            closeFileLog(taskId);
        }
    }

    /**
     * 构建精简大纲：只含小说整体大纲 + 前情提要
     * 替代旧的逐条章节摘要拼接方式，大幅节省token
     */
    private String buildCondensedOutline(Novel novel, String previousRecap) {
        StringBuilder sb = new StringBuilder();

        // 小说整体大纲
        if (novel != null && novel.getOutline() != null && !novel.getOutline().isEmpty()) {
            sb.append(novel.getOutline());
        }

        // 前情提要
        if (previousRecap != null && !previousRecap.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("【前情提要】\n").append(previousRecap);
        }

        return sb.length() > 0 ? sb.toString() : "暂无大纲和前情提要";
    }

    /**
     * 更新 CreativeSession 上下文中的 currentChapterId
     */
    private void updateSessionContext(String sessionId, Long chapterId) {
        if (sessionId == null) return;
        try {
            Optional<CreativeSession> opt = sessionRepository.findBySessionId(sessionId);
            if (opt.isPresent()) {
                CreativeSession session = opt.get();
                // 直接解析 JSON 上下文，避免与 CreativeSessionService 循环依赖
                String contextData = session.getContextData();
                Map<String, Object> contextMap;
                if (contextData != null && !contextData.isEmpty()) {
                    contextMap = objectMapper.readValue(contextData, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                } else {
                    contextMap = new LinkedHashMap<>();
                }
                contextMap.put("currentChapterId", chapterId);
                session.setContextData(objectMapper.writeValueAsString(contextMap));
                sessionRepository.save(session);
                logger.info("更新会话上下文: sessionId={}, chapterId={}", sessionId, chapterId);
            }
        } catch (Exception e) {
            logger.warn("更新会话上下文失败: {}", e.getMessage());
        }
    }

    /**
     * 解析参数JSON
     */
    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(paramsJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            logger.warn("解析参数失败: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String getParamAsString(Map<String, Object> params, String key, String defaultValue) {
        Object value = params.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    private int getParamAsInt(Map<String, Object> params, String key, int defaultValue) {
        Object value = params.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    // ==================== SSE 事件发送 ====================

    private void sendProgressEvent(ChapterGenerationTask task, String step, String message) {
        try {
            SseEmitter emitter = task.getEmitter();
            if (emitter != null) {
                emitter.send(SseEmitter.event()
                    .name("chapter_generation_progress")
                    .data(objectMapper.writeValueAsString(Map.of(
                        "taskId", task.getTaskId(),
                        "step", step,
                        "message", message
                    ))));
            }
        } catch (Exception e) {
            logger.warn("发送进度事件失败: {}", e.getMessage());
        }
    }

    private void sendGeneratedEvent(ChapterGenerationTask task, Long chapterId, String title, int wordCount) {
        try {
            SseEmitter emitter = task.getEmitter();
            if (emitter != null) {
                emitter.send(SseEmitter.event()
                    .name("chapter_generated")
                    .data(objectMapper.writeValueAsString(Map.of(
                        "taskId", task.getTaskId(),
                        "chapterId", chapterId,
                        "title", title,
                        "wordCount", wordCount,
                        "success", true
                    ))));
            }
        } catch (Exception e) {
            logger.warn("发送完成事件失败: {}", e.getMessage());
        }
    }

    private void sendFailedEvent(ChapterGenerationTask task, String error) {
        try {
            SseEmitter emitter = task.getEmitter();
            if (emitter != null) {
                emitter.send(SseEmitter.event()
                    .name("chapter_generation_failed")
                    .data(objectMapper.writeValueAsString(Map.of(
                        "taskId", task.getTaskId(),
                        "error", error,
                        "success", false
                    ))));
            }
        } catch (Exception e) {
            logger.warn("发送失败事件失败: {}", e.getMessage());
        }
    }

    // ==================== 文件日志 ====================

    /**
     * 初始化文件日志
     * 目录结构: logs/chapter-generation/{novelId}_{小说标题}/{章节序号}_{章节标题}.log
     */
    private void initFileLog(String taskId, Long novelId, String novelTitle, String chapterTitle) {
        try {
            // 清理文件名中的非法字符
            String safeNovelTitle = sanitizeFileName(novelTitle != null ? novelTitle : "unknown");
            String safeChapterTitle = sanitizeFileName(chapterTitle != null ? chapterTitle : "untitled");

            // 获取当前章节序号
            int chapterNumber = novelService.getChapterSummaries(novelId).size() + 1;

            String dirName = novelId + "_" + safeNovelTitle;
            String fileName = chapterNumber + "_" + safeChapterTitle + ".log";

            Path dirPath = Paths.get(LOG_ROOT, dirName);
            Files.createDirectories(dirPath);

            Path filePath = dirPath.resolve(fileName);
            java.io.PrintWriter writer = new java.io.PrintWriter(
                Files.newBufferedWriter(filePath, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING));

            taskLogWriters.put(taskId, writer);
            fileLog(taskId, "========== 子Agent章节生成日志 ==========");
            fileLog(taskId, "小说ID: {}", novelId);
            fileLog(taskId, "小说标题: {}", novelTitle);
            fileLog(taskId, "章节标题: {}", chapterTitle);
            fileLog(taskId, "日志文件: {}", filePath.toAbsolutePath());
            fileLog(taskId, "");

            logger.info("[子Agent] 日志文件: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            logger.warn("创建文件日志失败: {}", e.getMessage());
        }
    }

    /**
     * 写入文件日志
     */
    private void fileLog(String taskId, String message, Object... args) {
        java.io.PrintWriter writer = taskLogWriters.get(taskId);
        if (writer != null) {
            String timestamp = LocalDateTime.now().format(LOG_TIME_FMT);
            String content = args.length > 0 ? message.replace("{}", "%s") : message;
            if (args.length > 0) {
                Object[] stringArgs = Arrays.stream(args)
                    .map(a -> a instanceof String ? (String) a : String.valueOf(a))
                    .toArray(String[]::new);
                content = String.format(content.replace("{}", "%s"), stringArgs);
            }
            writer.println("[" + timestamp + "] " + content);
            writer.flush();
        }
    }

    /**
     * 关闭文件日志
     */
    private void closeFileLog(String taskId) {
        java.io.PrintWriter writer = taskLogWriters.remove(taskId);
        if (writer != null) {
            writer.close();
        }
    }

    /**
     * 清理文件名中的非法字符
     */
    private String sanitizeFileName(String name) {
        if (name == null) return "unknown";
        // 移除 Windows/Linux 文件名非法字符，截断过长名称
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                   .replaceAll("_+", "_")
                   .trim();
    }

    // ==================== 任务记录类 ====================

    public static class ChapterGenerationTask {
        private String taskId;
        private Long novelId;
        private String title;
        private String summary;
        private String status; // queued / pending / generating / completed / failed
        private Long chapterId;
        private String error;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private transient SseEmitter emitter;
        private String sessionId;
        private String extractedParamsJson;

        // Getters and Setters
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }

        public Long getNovelId() { return novelId; }
        public void setNovelId(Long novelId) { this.novelId = novelId; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Long getChapterId() { return chapterId; }
        public void setChapterId(Long chapterId) { this.chapterId = chapterId; }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

        public LocalDateTime getCompletedAt() { return completedAt; }
        public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

        public SseEmitter getEmitter() { return emitter; }
        public void setEmitter(SseEmitter emitter) { this.emitter = emitter; }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }

        public String getExtractedParamsJson() { return extractedParamsJson; }
        public void setExtractedParamsJson(String extractedParamsJson) { this.extractedParamsJson = extractedParamsJson; }
    }
}
