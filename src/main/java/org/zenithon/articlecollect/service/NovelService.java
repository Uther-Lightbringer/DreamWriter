package org.zenithon.articlecollect.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.zenithon.articlecollect.config.EvoLinkConfig;
import org.zenithon.articlecollect.dto.ChapterWithTags;
import org.zenithon.articlecollect.dto.CharacterCard;
import org.zenithon.articlecollect.dto.CharacterCardAppearance;
import org.zenithon.articlecollect.dto.CharacterCardRelationship;
import org.zenithon.articlecollect.entity.ChapterTag;
import org.zenithon.articlecollect.entity.Novel;
import org.zenithon.articlecollect.entity.Chapter;
import org.zenithon.articlecollect.entity.ChapterDetailView;
import org.zenithon.articlecollect.entity.CharacterCardEntity;
import org.zenithon.articlecollect.repository.NovelRepository;
import org.zenithon.articlecollect.repository.ChapterRepository;
import org.zenithon.articlecollect.repository.ChapterTagRepository;
import org.zenithon.articlecollect.repository.CharacterCardRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 小说管理服务类
 */
@Service
public class NovelService {
    
    private static final Logger logger = LoggerFactory.getLogger(NovelService.class);
    
    @Autowired
    private NovelRepository novelRepository;
    
    @Autowired
    private ChapterRepository chapterRepository;
    
    @Autowired
    private ChapterTagRepository chapterTagRepository;
    
    @Autowired
    private ChapterTagService chapterTagService;
    
    @Autowired
    private CharacterCardRepository characterCardRepository;
    
    @Autowired
    private CharacterCardService characterCardService;
    
    @Autowired
    private AIPromptService aiPromptService;
    
    @Autowired
    private CharacterCardAsyncService characterCardAsyncService;

    @Autowired
    private EvoLinkConfig evoLinkConfig;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SystemConfigService systemConfigService;
    
    /**
     * 创建新小说
     */
    public Novel createNovel(String title) {
        Novel novel = new Novel(title);
        return novelRepository.save(novel);
    }

    /**
     * 检查小说是否存在
     */
    public boolean existsById(Long novelId) {
        return novelRepository.existsById(novelId);
    }
    
    /**
     * 创建新小说（带作者信息）
     */
    public Novel createNovel(String title, String author) {
        Novel novel = new Novel(title, author);
        return novelRepository.save(novel);
    }
    
    /**
     * 创建新小说（完整信息）
     */
    public Novel createNovel(String title, String author, String description) {
        Novel novel = new Novel(title, author, description);
        return novelRepository.save(novel);
    }
    
    /**
     * 根据ID获取小说
     */
    public Novel getNovelById(Long novelId) {
        Optional<Novel> novelOpt = novelRepository.findById(novelId);
        if (novelOpt.isPresent()) {
            Novel novel = novelOpt.get();
            // 确保格式化时间字段被正确设置
             ensureFormattedTime(novel);
            setChaptersCount(novel);
            // 批量分析章节标签（首次访问时生成，后续直接读取数据库）
            chapterTagService.batchAnalyzeNovelChapters(novelId);

            // 使用直接查询获取排序后的章节，避免懒加载异常
            List<Chapter> sortedChapters = chapterRepository.findByNovelIdOrderByChapterNumberAsc(novelId);
            novel.setChapters(sortedChapters);

            // 设置章节数
            setChaptersCount(novel);

            return novel;
        }
        return null;
    }

    private void setChaptersCount(Novel novel) {
        novel.setChaptersCount((int) chapterRepository.countByNovelId(novel.getId()));
    }

    /**
     * 获取所有小说，按创建时间倒序排列（最新的在最前面）
     */
    public List<Novel> getAllNovels() {
        List<Novel> novels = novelRepository.findAllByOrderByCreateTimeDesc();
        // 使用直接查询章节数量，避免懒加载异常（异步线程中无Hibernate Session）
        novels.forEach(n -> n.setChaptersCount((int) chapterRepository.countByNovelId(n.getId())));
        novels.forEach(this::ensureFormattedTime);
        return novels;
    }
    
    /**
     * 确保小说对象的格式化时间字段被正确设置
     */
    private void ensureFormattedTime(Novel novel) {
        if (novel.getCreateTime() != null && novel.getFormattedCreateTime() == null) {
            novel.setFormattedCreateTime(novel.formatDateTime(novel.getCreateTime()));
        }
    }
    
    /**
     * 删除小说
     */
    public boolean deleteNovel(Long novelId) {
        if (novelRepository.existsById(novelId)) {
            // 先删除相关章节
            List<Chapter> chaptersToDelete = chapterRepository.findByNovelIdOrderByChapterNumberAsc(novelId);
            chapterRepository.deleteAll(chaptersToDelete);
            
            // 删除相关角色卡
            characterCardRepository.deleteByNovelId(novelId);
            
            // 删除小说
            novelRepository.deleteById(novelId);
            return true;
        }
        return false;
    }
    
    /**
     * 创建小说章节
     */
    public Chapter createChapter(Long novelId, String title, String content, Long index, String storySummary) {
        // 检查小说是否存在
        if (!novelRepository.existsById(novelId)) {
            throw new RuntimeException("小说不存在，ID: " + novelId);
        }
        int chapterNumber = 1;
        if (Objects.isNull(index)){
            chapterNumber = chapterRepository.findByNovelIdOrderByChapterNumberAsc(novelId).size() + 1;
        }else {
            chapterNumber = index.intValue() + 1;
        }
        Chapter chapter = new Chapter(novelId, title, content, chapterNumber, storySummary);
        return chapterRepository.save(chapter);
    }
    
    /**
     * 根据小说ID获取所有章节
     */
    public List<Chapter> getChaptersByNovelId(Long novelId) {
        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNumberAsc(novelId);
        // 为所有章节确保格式化时间字段被正确设置
        chapters.forEach(this::ensureFormattedTime);
        return chapters;
    }

    /**
     * 获取小说所有章节的概括列表（不含内容）
     * 用于创建新章节时了解剧情进度，避免上下文爆炸
     */
    public List<Map<String, Object>> getChapterSummaries(Long novelId) {
        List<Chapter> chapters = chapterRepository.findByNovelIdOrderByChapterNumberAsc(novelId);
        List<Map<String, Object>> summaries = new ArrayList<>();

        for (Chapter chapter : chapters) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("chapterNumber", chapter.getChapterNumber());
            summary.put("title", chapter.getTitle());
            summary.put("summary", chapter.getStorySummary() != null ? chapter.getStorySummary() : "暂无概括");
            summaries.add(summary);
        }

        return summaries;
    }

    /**
     * 根据小说ID获取所有带标签的章节
     */
    public List<ChapterWithTags> getChaptersWithTagsByNovelId(Long novelId) {
        List<Chapter> chapters = getChaptersByNovelId(novelId);
        Map<Long, List<ChapterTag>> chapterTagsMap = chapterTagService.batchAnalyzeNovelChapters(novelId);
        
        List<ChapterWithTags> result = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<ChapterTag> tags = chapterTagsMap.getOrDefault(chapter.getId(), new ArrayList<>());
            result.add(new ChapterWithTags(chapter, tags));
        }
        
        return result;
    }
    
    /**
     * 根据ID获取章节
     */
    public Chapter getChapterById(Long chapterId) {
        Optional<Chapter> chapterOpt = chapterRepository.findById(chapterId);
        if (chapterOpt.isPresent()) {
            Chapter chapter = chapterOpt.get();
            // 确保格式化时间字段被正确设置
            ensureFormattedTime(chapter);
            return chapter;
        }
        return null;
    }
    
    /**
     * 更新章节
     */
    public Chapter updateChapter(Long chapterId, String title, String content, String storySummary) {
        Optional<Chapter> chapterOpt = chapterRepository.findById(chapterId);
        if (chapterOpt.isPresent()) {
            Chapter chapter = chapterOpt.get();
            if (title != null) {
                chapter.setTitle(title);
            }
            if (content != null) {
                chapter.setContent(content);
            }
            if (storySummary != null) {
                chapter.setStorySummary(storySummary);
            }
            chapter.setUpdateTime(LocalDateTime.now());
            return chapterRepository.save(chapter);
        }
        return null;
    }
    
    
    /**
     * 删除章节
     */
    public boolean deleteChapter(Long chapterId) {
        if (chapterRepository.existsById(chapterId)) {
            chapterRepository.deleteById(chapterId);
            return true;
        }
        return false;
    }
    
    /**
     * 获取章节详情（包含导航信息）
     */
    public ChapterDetailView getChapterDetailView(Long novelId, Long chapterId) {
        // 验证小说和章节是否存在
        Novel novel = getNovelById(novelId);
        Chapter chapter = getChapterById(chapterId);
        
        if (novel == null || chapter == null || !chapter.getNovelId().equals(novelId)) {
            return null;
        }
        
        // 获取该小说的所有章节并排序
        List<Chapter> novelChapters = getChaptersByNovelId(novelId);
        
        // 找到当前章节的索引
        int currentIndex = -1;
        for (int i = 0; i < novelChapters.size(); i++) {
            if (novelChapters.get(i).getId().equals(chapterId)) {
                currentIndex = i;
                break;
            }
        }
        
        if (currentIndex == -1) {
            return null;
        }
        
        // 计算前后章节信息
        boolean hasPrevious = currentIndex > 0;
        boolean hasNext = currentIndex < novelChapters.size() - 1;
        
        Long previousChapterId = hasPrevious ? novelChapters.get(currentIndex - 1).getId() : null;
        Long nextChapterId = hasNext ? novelChapters.get(currentIndex + 1).getId() : null;
        
        return new ChapterDetailView(
            chapter,
            hasNext,
            hasPrevious,
            nextChapterId,
            previousChapterId,
            novel.getTitle(),
            novelId
        );
    }
    
    /**
     * 确保章节对象的格式化时间字段被正确设置
     */
    private void ensureFormattedTime(Chapter chapter) {
        if (chapter.getCreateTime() != null && chapter.getFormattedCreateTime() == null) {
            chapter.setFormattedCreateTime(chapter.formatDateTime(chapter.getCreateTime()));
        }
    }
    
    /**
     * 更新小说的世界观
     */
    public Novel updateWorldView(Long novelId, String worldView) {
        Optional<Novel> novelOpt = novelRepository.findById(novelId);
        if (novelOpt.isPresent()) {
            Novel novel = novelOpt.get();
            novel.setWorldView(worldView);
            return novelRepository.save(novel);
        }
        throw new RuntimeException("小说不存在，ID: " + novelId);
    }

    /**
     * 更新小说（通用方法）
     */
    public Novel updateNovel(Novel novel) {
        return novelRepository.save(novel);
    }
    

    /**
     * 批量更新小说的角色卡（结构化数据）- 保存到数据库表
     */
    public Novel updateCharacterCardsBatch(Long novelId, List<CharacterCard> characterCards) {
        Optional<Novel> novelOpt = novelRepository.findById(novelId);
        if (novelOpt.isPresent()) {
            // 保存到数据库表
            saveCharacterCardsToDatabase(novelId, characterCards);
            return novelOpt.get();
        }
        throw new RuntimeException("小说不存在，ID: " + novelId);
    }
    
    /**
     * 保存单个角色卡（新增或更新）
     */
    public CharacterCard saveSingleCharacterCard(Long novelId, CharacterCard characterCard) {
        return characterCardService.saveSingleCharacterCard(novelId, characterCard);
    }
    

    
    /**
     * 保存角色卡到数据库表
     */
    public List<CharacterCard> saveCharacterCardsToDatabase(Long novelId, List<CharacterCard> characterCards) {
        return characterCardService.saveCharacterCards(novelId, characterCards);
    }
    
    /**
     * 从数据库获取角色卡列表
     */
    public List<CharacterCard> getCharacterCardsFromDatabase(Long novelId) {
        return characterCardService.getCharacterCardsByNovelId(novelId);
    }


    
    /**
     * 获取小说的角色卡列表（结构化数据）- 从数据库读取
     */
    public List<CharacterCard> getCharacterCardsList(Long novelId) {
        // 验证小说是否存在
        if (!novelRepository.existsById(novelId)) {
            throw new RuntimeException("小说不存在，ID: " + novelId);
        }
        
        // 从数据库表读取角色卡
        return getCharacterCardsFromDatabase(novelId);
    }


    /**
     * 为单个角色生成图片
     */
    public String generateImageForCharacter(CharacterCard characterCard) throws Exception {
        return generateAndSaveCharacterImage(characterCard);
    }

    /**
     * 生成并保存角色卡图片到本地
     */
    private String generateAndSaveCharacterImage(CharacterCard card) {
        try {
            // 创建 EvoLinkImageService 实例
            EvoLinkImageService imageService = new EvoLinkImageService(evoLinkConfig, restTemplate, objectMapper, systemConfigService);
            String taskId = imageService.generateImage(card.getAppearanceDescription(), "1:1");

            // 等待图片生成完成
            String imageUrl = waitForImageCompletion(imageService, taskId, 5 * 60 * 1000);

            if (imageUrl != null) {
                // 下载并保存到本地
                return downloadAndSaveToLocal(card, imageUrl);
            }

            return null;

        } catch (Exception e) {
            logger.error("生成并保存角色卡图片失败：" + e.getMessage(), e);
            throw new RuntimeException("生成图片失败：" + e.getMessage(), e);
        }
    }

    /**
     * 下载图片并保存到本地角色卡目录
     */
    private String downloadAndSaveToLocal(CharacterCard card, String imageUrl) {
        try {
            // 下载图片
            ResponseEntity<byte[]> response = restTemplate.getForEntity(imageUrl, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // 使用 FileUploadUtil 保存图片
                String localPath = org.zenithon.articlecollect.util.FileUploadUtil.saveCharacterCardImage(
                    response.getBody(),
                    card.getName()
                );

                logger.info("角色卡图片已保存到本地：{}", localPath);
                return localPath;
            }

            return null;

        } catch (Exception e) {
            logger.error("下载并保存角色卡图片失败：" + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 等待图片生成完成
     */
    private String waitForImageCompletion(EvoLinkImageService imageService, String taskId, long timeout) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                EvoLinkImageService.TaskStatus status = imageService.getTaskStatus(taskId);

                if (status.isCompleted()) {
                    return status.getImageUrl();
                }

                if (status.isFailed()) {
                    logger.error("图片生成失败：{}", status.getError());
                    return null;
                }

                // 等待 2 秒后再次检查
                Thread.sleep(2000);

            } catch (Exception e) {
                logger.error("检查任务状态失败：" + e.getMessage(), e);
                return null;
            }
        }

        logger.error("等待图片生成超时");
        return null;
    }
}