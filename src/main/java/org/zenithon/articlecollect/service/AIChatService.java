package org.zenithon.articlecollect.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.zenithon.articlecollect.entity.ChatMessage;
import org.zenithon.articlecollect.entity.Chapter;
import org.zenithon.articlecollect.entity.Novel;
import org.zenithon.articlecollect.repository.ChatMessageRepository;

import java.util.*;

/**
 * AI 聊天服务
 * 管理聊天历史记录和工具调用
 */
@Service
public class AIChatService {

    private static final Logger logger = LoggerFactory.getLogger(AIChatService.class);

    private final ChatMessageRepository chatMessageRepository;
    private final NovelService novelService;

    public AIChatService(ChatMessageRepository chatMessageRepository, NovelService novelService) {
        this.chatMessageRepository = chatMessageRepository;
        this.novelService = novelService;
    }

    // ===== 历史记录管理 =====

    public ChatMessage saveMessage(String role, String content) {
        ChatMessage message = new ChatMessage(role, content);
        return chatMessageRepository.save(message);
    }

    public List<ChatMessage> getHistory() {
        return chatMessageRepository.findAllByOrderByCreateTimeAsc();
    }

    public void clearHistory() {
        chatMessageRepository.deleteAll();
    }

    // ===== 工具定义 =====

    public List<Map<String, Object>> getTools() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(createTool("list_novels", "列出所有小说",
                Map.of()));
        tools.add(createTool("get_novel_detail", "获取小说详情，包含章节列表",
                Map.of("novelId", Map.of("type", "integer", "description", "小说ID"))));
        tools.add(createTool("get_chapter_detail", "获取章节内容",
                Map.of("chapterId", Map.of("type", "integer", "description", "章节ID"))));
        tools.add(createTool("generate_image", "生成AI图片",
                Map.of("prompt", Map.of("type", "string", "description", "图片描述提示词"))));
        tools.add(createTool("search_chapters", "搜索章节内容",
                Map.of("keyword", Map.of("type", "string", "description", "搜索关键词"))));

        return tools;
    }

    private Map<String, Object> createTool(String name, String description, Map<String, Object> properties) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", properties);
        List<String> required = properties.keySet().stream().toList();
        if (!required.isEmpty()) {
            params.put("required", required);
        }
        function.put("parameters", params);

        return Map.of("type", "function", "function", function);
    }

    // ===== 工具调用执行 =====

    public String processToolCall(String toolName, Map<String, Object> arguments) {
        try {
            return switch (toolName) {
                case "list_novels" -> listNovels();
                case "get_novel_detail" -> getNovelDetail(arguments);
                case "get_chapter_detail" -> getChapterDetail(arguments);
                case "generate_image" -> generateImage(arguments);
                case "search_chapters" -> searchChapters(arguments);
                default -> "{\"error\": \"未知工具: " + toolName + "\"}";
            };
        } catch (Exception e) {
            logger.error("工具调用失败: tool={}, error={}", toolName, e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String listNovels() {
        List<Novel> novels = novelService.getAllNovels();
        StringBuilder sb = new StringBuilder();
        sb.append("共有 ").append(novels.size()).append(" 部小说：\n");
        for (Novel novel : novels) {
            sb.append("- ID: ").append(novel.getId())
              .append(", 标题: ").append(novel.getTitle())
              .append("\n");
        }
        return sb.toString();
    }

    private String getNovelDetail(Map<String, Object> args) {
        Long novelId = ((Number) args.get("novelId")).longValue();
        Novel novel = novelService.getNovelById(novelId);
        List<Chapter> chapters = novelService.getChaptersByNovelId(novelId);

        StringBuilder sb = new StringBuilder();
        sb.append("小说: ").append(novel.getTitle()).append("\n");
        sb.append("世界观: ").append(novel.getWorldView() != null ? novel.getWorldView() : "未设置").append("\n");
        sb.append("章节列表 (").append(chapters.size()).append("章)：\n");
        for (Chapter ch : chapters) {
            sb.append("  - 第").append(ch.getChapterNumber()).append("章: ").append(ch.getTitle())
              .append(" (ID: ").append(ch.getId()).append(")\n");
        }
        return sb.toString();
    }

    private String getChapterDetail(Map<String, Object> args) {
        Long chapterId = ((Number) args.get("chapterId")).longValue();
        Chapter chapter = novelService.getChapterById(chapterId);

        StringBuilder sb = new StringBuilder();
        sb.append("第").append(chapter.getChapterNumber()).append("章: ").append(chapter.getTitle()).append("\n\n");
        sb.append(chapter.getContent());
        return sb.toString();
    }

    private String generateImage(Map<String, Object> args) {
        String prompt = (String) args.get("prompt");
        return "图片生成任务已提交，提示词: " + prompt + "。请前往「梦境画坊」查看生成结果。";
    }

    private String searchChapters(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        List<Novel> novels = novelService.getAllNovels();
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (Novel novel : novels) {
            List<Chapter> chapters = novelService.getChaptersByNovelId(novel.getId());
            for (Chapter ch : chapters) {
                if (ch.getContent() != null && ch.getContent().contains(keyword)) {
                    sb.append("- 小说《").append(novel.getTitle()).append("》第")
                      .append(ch.getChapterNumber()).append("章: ").append(ch.getTitle())
                      .append(" (章节ID: ").append(ch.getId()).append(")\n");
                    count++;
                }
            }
        }

        if (count == 0) {
            return "未找到包含「" + keyword + "」的章节。";
        }
        return "找到 " + count + " 个包含「" + keyword + "」的章节：\n" + sb;
    }
}
