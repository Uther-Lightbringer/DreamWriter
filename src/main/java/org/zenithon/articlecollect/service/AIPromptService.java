package org.zenithon.articlecollect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.zenithon.articlecollect.config.DeepSeekConfig;
import org.zenithon.articlecollect.dto.CharacterCard;
import org.zenithon.articlecollect.dto.CharacterCardRelationship;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * DeepSeek AI 绘画提示词生成服务
 */
@Service
public class AIPromptService {

    private static final Logger logger = LoggerFactory.getLogger(AIPromptService.class);

    // 用于清理思考标签的正则表达式
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    // ==================== 画风选项常量 ====================
    /** 写实摄影风格，追求真实感和细节质感，光影自然 */
    public static final String STYLE_REALISTIC = "写实摄影风格，追求真实感和细节质感，光影自然";
    /** 日系动漫风格，线条流畅，色彩鲜艳，人物比例修长 */
    public static final String STYLE_ANIME = "日系动漫风格，线条流畅，色彩鲜艳，人物比例修长";
    /** 韩系插画风格，柔和的光影，精致的五官，浪漫氛围 */
    public static final String STYLE_KOREAN = "韩系插画风格，柔和的光影，精致的五官，浪漫氛围";
    /** 油画风格，笔触厚重，色彩浓郁，古典艺术感 */
    public static final String STYLE_OIL_PAINTING = "油画风格，笔触厚重，色彩浓郁，古典艺术感";
    /** 水彩风格，色彩淡雅，笔触轻盈，朦胧梦幻 */
    public static final String STYLE_WATERCOLOR = "水彩风格，色彩淡雅，笔触轻盈，朦胧梦幻";
    /** 素描风格，黑白线条，明暗对比强烈，艺术感强 */
    public static final String STYLE_SKETCH = "素描风格，黑白线条，明暗对比强烈，艺术感强";

    /** 默认画风 */
    public static final String DEFAULT_STYLE = STYLE_REALISTIC;

    // ==================== 质量增强词（根据画风，融入教程元素） ====================
    /** 写实摄影质量增强词 */
    public static final String QUALITY_REALISTIC = "，高清摄影，8K画质，细节丰富，景深效果极佳，专业摄影质感，电影级光影";
    /** 日系动漫质量增强词 */
    public static final String QUALITY_ANIME = "，精细线条，鲜艳色彩，赛璐璐上色，动漫风格，清晰轮廓，动态光影";
    /** 韩系插画质量增强词 */
    public static final String QUALITY_KOREAN = "，柔和光影，精致五官，浪漫氛围，韩系插画，细腻质感，唯美画面";
    /** 油画质量增强词 */
    public static final String QUALITY_OIL_PAINTING = "，笔触厚重，色彩浓郁，古典艺术感，油画质感，明暗对比强烈";
    /** 水彩质量增强词 */
    public static final String QUALITY_WATERCOLOR = "，色彩淡雅，笔触轻盈，朦胧梦幻，水彩质感，诗意氛围";
    /** 素描质量增强词 */
    public static final String QUALITY_SKETCH = "，黑白线条，明暗对比强烈，艺术感，素描质感，细腻笔触";

    /** 默认排除词（嵌入prompt末尾） */
    public static final String DEFAULT_NEGATIVE_WORDS = "，无多余手指，无变形，无模糊，无水印，无签名，高质量，清晰细节，无任何文字";

    // ==================== 场景氛围词（根据场景类型） ====================
    /** 室内/办公室场景 */
    public static final String ATMOSPHERE_INDOOR = "明亮整洁，现代感，室内光线";
    /** 室外/自然场景 */
    public static final String ATMOSPHERE_OUTDOOR = "清新开阔，自然光，蓝天白云";
    /** 夜晚/酒吧场景 */
    public static final String ATMOSPHERE_NIGHT = "神秘暧昧，暖色调灯光，昏暗氛围";
    /** 古典/宫廷场景 */
    public static final String ATMOSPHERE_CLASSICAL = "华丽庄重，古典美感，精致装饰";
    /** 居家/温馨场景 */
    public static final String ATMOSPHERE_HOME = "温馨舒适，柔和光线，生活气息";
    /** 运动/动作场景 */
    public static final String ATMOSPHERE_ACTION = "动感活力，强对比光，紧张氛围";

    // ==================== 镜头语言 ====================
    /** 镜头角度选项 */
    public static final String[] CAMERA_ANGLES = {
        "低角度仰视", "俯视镜头", "平视角度", "虫视角度(worm's-eye view)", "高角度俯拍"
    };
    /** 景别选项 */
    public static final String[] SHOT_TYPES = {
        "特写镜头", "半身景", "全身景", "远景", "广角镜头", "超广角镜头"
    };

    // ==================== 光线效果 ====================
    /** 光线类型 */
    public static final String[] LIGHTING_TYPES = {
        "夕阳侧光", "逆光轮廓光", "柔和漫射光", "戏剧性明暗对比(chiaroscuro)",
        "月光冷光", "暖色调灯光", "霓虹光效", "自然窗光"
    };

    // ==================== 质量标签（英文，放在提示词末尾） ====================
    public static final String QUALITY_TAGS_CN = "，杰作，最佳质量，极致细节，震撼画面";
    public static final String QUALITY_TAGS_EN = ", masterpiece, best quality, highly detailed, dramatic composition, complex lighting";

    // ==================== 图片类型定义（影响整体风格） ====================
    /** Editorial 配图类型 */
    public static final String[] IMAGE_TYPES = {
        "editorial illustration（文章配图）",
        "editorial visual（评论型配图）",
        "cinematic tech editorial style（科技评论风格）",
        "lifestyle photography（生活方式摄影）",
        "conceptual illustration（概念插画）",
        "product photography（产品摄影）",
        "character portrait（人物肖像）",
        "scene visualization（场景可视化）"
    };

    // ==================== 构图类型 ====================
    /** 构图方式 */
    public static final String[] COMPOSITION_TYPES = {
        "split composition（分裂对比构图）",
        "isometric view（等距视角）",
        "top-down view（俯视图）",
        "centered composition（中心构图）",
        "rule of thirds（三分法构图）",
        "symmetrical composition（对称构图）",
        "depth composition（纵深构图）"
    };

    // ==================== 重要排除词 ====================
    /** 防止生成假文字、假UI的关键词 */
    public static final String NO_TEXT_TAG = "，无任何文字，无水印，无假UI，无假标签";
    public static final String NO_TEXT_TAG_EN = ", absolutely no text, no watermark, no fake UI, no fake labels";

    private final DeepSeekConfig deepSeekConfig;
    private final DeepSeekConfigService deepSeekConfigService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public AIPromptService(DeepSeekConfig deepSeekConfig,
                           DeepSeekConfigService deepSeekConfigService,
                           RestTemplate restTemplate,
                           ObjectMapper objectMapper) {
        this.deepSeekConfig = deepSeekConfig;
        this.deepSeekConfigService = deepSeekConfigService;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据角色卡信息生成 AI 绘画提示词（使用默认画风）
     * @param characterCard 角色卡信息
     * @return 生成的 AI 绘画提示词，如果失败则返回 null
     */
    public String generateAIPrompt(CharacterCard characterCard) {
        return generateAIPrompt(characterCard, DEFAULT_STYLE);
    }

    /**
     * 根据角色卡信息生成 AI 绘画提示词（指定画风）
     * @param characterCard 角色卡信息
     * @param style 画风选项（使用 STYLE_* 常量）
     * @return 生成的 AI 绘画提示词，如果失败则返回 null
     */
    public String generateAIPrompt(CharacterCard characterCard, String style) {
        // 检查 API Key 是否配置
        if (deepSeekConfig.getApiKey() == null || deepSeekConfig.getApiKey().trim().isEmpty()) {
            logger.warn("DeepSeek API Key 未配置，跳过 AI 提示词生成");
            return null;
        }

        try {
            // 构建角色描述
            String characterDescription = buildCharacterDescription(characterCard);

            // 构建请求 prompt（使用统一模板）
            String prompt = buildImagePromptTemplate(characterDescription, null, style, 200, 500);

            // 调用 DeepSeek API
            String aiResponse = callDeepSeekAPI(prompt);

            // 解析响应并提取提示词，限制长度在1800字符以下
            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                String trimmedPrompt = trimPromptToLength(aiResponse, 1800);
                logger.info("AI 绘画提示词生成成功，长度: {} 字符，画风: {}", trimmedPrompt.length(), style);
                return trimmedPrompt;
            }
        } catch (Exception e) {
            logger.error("生成 AI 绘画提示词失败：" + e.getMessage(), e);
        }

        return null;
    }

    /**
     * 构建角色描述文本
     */
    private String buildCharacterDescription(CharacterCard card) {
        StringBuilder sb = new StringBuilder();

        // 基本信息
        sb.append("角色姓名：").append(card.getName()).append("\n");

        if (card.getAge() != null) {
            sb.append("年龄：").append(card.getAge()).append("\n");
        }

        if (card.getGender() != null && !card.getGender().isEmpty()) {
            sb.append("性别：").append(card.getGender()).append("\n");
        }

        if (card.getOccupation() != null && !card.getOccupation().isEmpty()) {
            sb.append("职业：").append(card.getOccupation()).append("\n");
        }

        // 外貌特征
        if (card.getAppearance() != null) {
            sb.append("\n【外貌特征】\n");
            if (card.getAppearance().getHeight() != null && !card.getAppearance().getHeight().isEmpty()) {
                sb.append("- 身高：").append(card.getAppearance().getHeight()).append("\n");
            }
            if (card.getAppearance().getHair() != null && !card.getAppearance().getHair().isEmpty()) {
                sb.append("- 发色：").append(card.getAppearance().getHair()).append("\n");
            }
            if (card.getAppearance().getEyes() != null && !card.getAppearance().getEyes().isEmpty()) {
                sb.append("- 瞳色：").append(card.getAppearance().getEyes()).append("\n");
            }
            if (card.getAppearance().getFace() != null && !card.getAppearance().getFace().isEmpty()) {
                sb.append("- 脸型：").append(card.getAppearance().getFace()).append("\n");
            }
            if (card.getAppearance().getBuild() != null && !card.getAppearance().getBuild().isEmpty()) {
                sb.append("- 体型：").append(card.getAppearance().getBuild()).append("\n");
            }
            if (card.getAppearance().getClothing() != null && !card.getAppearance().getClothing().isEmpty()) {
                sb.append("- 服装：").append(card.getAppearance().getClothing()).append("\n");
            }
            if (card.getAppearance().getLegwear() != null && !card.getAppearance().getLegwear().isEmpty()) {
                sb.append("- 腿部穿着：").append(card.getAppearance().getLegwear()).append("\n");
            }
            if (card.getAppearance().getShoes() != null && !card.getAppearance().getShoes().isEmpty()) {
                sb.append("- 鞋子：").append(card.getAppearance().getShoes()).append("\n");
            }
            if (card.getAppearance().getAccessories() != null && !card.getAppearance().getAccessories().isEmpty()) {
                sb.append("- 配饰：").append(card.getAppearance().getAccessories()).append("\n");
            }
            if (card.getAppearance().getDistinguishingFeatures() != null && !card.getAppearance().getDistinguishingFeatures().isEmpty()) {
                sb.append("- 显著特征：").append(card.getAppearance().getDistinguishingFeatures()).append("\n");
            }
        }

        // 性格描述
        if (card.getPersonality() != null && !card.getPersonality().isEmpty()) {
            sb.append("\n【性格描述】\n").append(card.getPersonality()).append("\n");
        }

        // 背景故事
        if (card.getBackground() != null && !card.getBackground().isEmpty()) {
            sb.append("\n【背景故事】\n").append(card.getBackground()).append("\n");
        }

        // 人际关系
        if (card.getRelationships() != null && !card.getRelationships().isEmpty()) {
            sb.append("\n【人际关系】\n");
            for (CharacterCardRelationship rel : card.getRelationships()) {
                if (rel.getTargetName() != null && rel.getRelationship() != null) {
                    sb.append("- 与").append(rel.getTargetName()).append("的关系：").append(rel.getRelationship()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    // ==================== 统一的 AI 绘画提示词模板 ====================

    /**
     * 根据画风获取质量增强词
     */
    public String getQualityEnhancers(String style) {
        if (style == null) style = DEFAULT_STYLE;

        if (style.contains("写实摄影")) {
            return QUALITY_REALISTIC;
        } else if (style.contains("日系动漫")) {
            return QUALITY_ANIME;
        } else if (style.contains("韩系插画")) {
            return QUALITY_KOREAN;
        } else if (style.contains("油画")) {
            return QUALITY_OIL_PAINTING;
        } else if (style.contains("水彩")) {
            return QUALITY_WATERCOLOR;
        } else if (style.contains("素描")) {
            return QUALITY_SKETCH;
        }
        return QUALITY_REALISTIC;
    }

    /**
     * 根据场景描述识别场景氛围词
     */
    public String getAtmosphereByScene(String sceneDescription) {
        if (sceneDescription == null || sceneDescription.isEmpty()) {
            return ATMOSPHERE_INDOOR;
        }

        String lowerScene = sceneDescription.toLowerCase();

        // 夜晚场景
        if (lowerScene.contains("夜") || lowerScene.contains("酒吧") ||
            lowerScene.contains("酒吧") || lowerScene.contains("黑暗") ||
            lowerScene.contains("月光") || lowerScene.contains("霓虹")) {
            return ATMOSPHERE_NIGHT;
        }

        // 古典场景
        if (lowerScene.contains("宫廷") || lowerScene.contains("古代") ||
            lowerScene.contains("古典") || lowerScene.contains("皇宫") ||
            lowerScene.contains("府邸") || lowerScene.contains("庭院")) {
            return ATMOSPHERE_CLASSICAL;
        }

        // 居家场景
        if (lowerScene.contains("家") || lowerScene.contains("卧室") ||
            lowerScene.contains("客厅") || lowerScene.contains("厨房") ||
            lowerScene.contains("温馨") || lowerScene.contains("沙发")) {
            return ATMOSPHERE_HOME;
        }

        // 运动/动作场景
        if (lowerScene.contains("奔跑") || lowerScene.contains("运动") ||
            lowerScene.contains("打斗") || lowerScene.contains("战斗") ||
            lowerScene.contains("追逐") || lowerScene.contains("跳跃")) {
            return ATMOSPHERE_ACTION;
        }

        // 室外场景
        if (lowerScene.contains("室外") || lowerScene.contains("街道") ||
            lowerScene.contains("公园") || lowerScene.contains("森林") ||
            lowerScene.contains("海边") || lowerScene.contains("山") ||
            lowerScene.contains("田野") || lowerScene.contains("花园")) {
            return ATMOSPHERE_OUTDOOR;
        }

        // 默认室内
        return ATMOSPHERE_INDOOR;
    }

    /**
     * 构建 AI 绘画提示词的统一模板
     * @param characterDescription 角色描述（可为null）
     * @param sceneDescription 场景描述（可为null）
     * @param style 画风选项
     * @param minWords 最小字数
     * @param maxWords 最大字数
     * @return 完整的提示词
     */
    public String buildImagePromptTemplate(String characterDescription, String sceneDescription,
                                           String style, int minWords, int maxWords) {
        StringBuilder prompt = new StringBuilder();

        // 系统角色
        prompt.append("你是专业的AI绘画提示词工程师，擅长将文字描述转化为精准的图像生成提示词。\n\n");

        // ==================== 黄金结构模板 ====================
        prompt.append("【黄金提示词结构】\n");
        prompt.append("按照以下顺序构建提示词，每个部分用逗号分隔：\n");
        prompt.append("1. 图片类型：定义这是什么类型的图\n");
        prompt.append("2. 主体描述：主角或核心物体\n");
        prompt.append("3. 构图方式：视角、位置关系\n");
        prompt.append("4. 风格氛围：情绪、材质、配色\n");
        prompt.append("5. 质量约束：清晰度、细节要求\n");
        prompt.append("6. 排除词：不需要的元素\n\n");

        // 画风控制
        prompt.append("【画风要求】\n");
        prompt.append(style != null ? style : DEFAULT_STYLE).append("\n\n");

        // 核心原则
        prompt.append("【核心原则 - 极其重要！】\n");
        prompt.append("1. 禁止出现任何人名、角色名！只描述人物的外貌特征\n");
        prompt.append("2. 使用流畅的自然语言描述画面，像在描述一张照片\n");
        prompt.append("3. 描述要具体：不要说\"美丽的场景\"，要说\"金色沙漠在夕阳下泛着微光\"\n");
        prompt.append("4. 光线决定氛围：描述光源、方向、强度\n");
        prompt.append("5. 严格控制字数在 ").append(minWords).append("-").append(maxWords).append(" 字\n");
        prompt.append("6. 【简化原则】场景描述要精简，最多2-3个背景元素，避免画面拥挤\n\n");

        // ==================== 智能构图指导 ====================
        prompt.append("【智能构图指导】\n");
        prompt.append("根据场景内容自动选择最佳构图方案：\n\n");

        prompt.append("【单人构图方案】\n");
        prompt.append("1. 特写肖像：人物面部占据画面大部分，适合表现情绪\n");
        prompt.append("   - 位置：人物居中或略偏一侧（黄金分割位）\n");
        prompt.append("   - 背景：简洁虚化，突出面部\n\n");

        prompt.append("2. 半身像：人物胸部以上，适合展示表情和上半身动作\n");
        prompt.append("   - 位置：人物居中，头顶留出空间\n");
        prompt.append("   - 背景：适度虚化，保留环境氛围\n\n");

        prompt.append("3. 全身像：展示完整人物形象和服装\n");
        prompt.append("   - 位置：人物居中或三分法构图\n");
        prompt.append("   - 背景：清晰度适中，与人物协调\n\n");

        prompt.append("【双人构图方案】\n");
        prompt.append("1. 对话构图：两人面对面交谈\n");
        prompt.append("   - 位置：画面左右两侧各一人，面向中间\n");
        prompt.append("   - 景别：中景，两人都在画面内\n");
        prompt.append("   - 视线：两人视线相交\n\n");

        prompt.append("2. 并排构图：两人并肩站立或行走\n");
        prompt.append("   - 位置：画面中央并排站立\n");
        prompt.append("   - 间距：保持适当距离，不要重叠\n");
        prompt.append("   - 景别：全身或大半身\n\n");

        prompt.append("3. 主次构图：一主一次，突出重点人物\n");
        prompt.append("   - 主角：画面中心偏前，较大\n");
        prompt.append("   - 配角：画面一侧偏后，较小\n");
        prompt.append("   - 景别：中景到全景\n\n");

        prompt.append("4. 上下级构图：表现地位差异\n");
        prompt.append("   - 上位者：坐着，画面一侧\n");
        prompt.append("   - 下位者：站立，画面另一侧\n");
        prompt.append("   - 景别：中景\n\n");

        prompt.append("【重要提醒】\n");
        prompt.append("1. 画面最多2人！超过2人的场景必须简化或选择代表性人物\n");
        prompt.append("2. 两人之间要留出空间，避免肢体重叠或遮挡\n");
        prompt.append("3. 复杂场景要简化背景，聚焦人物互动\n\n");

        // 输出顺序要求
        prompt.append("【输出格式】使用流畅的自然语言描述画面\n");
        prompt.append("像在描述一张真实照片，用连贯的句子而不是关键词堆砌\n");
        prompt.append("示例结构：这是一张[图片类型]。画面中[人物描述]，[动作姿态]，[表情]。");
        prompt.append("[服装配饰]。背景是[场景环境]。[光线描述]。整体氛围[情感基调]。\n\n");

        // 角色外貌一致性（特征前置强调）
        if (characterDescription != null && !characterDescription.isEmpty()) {
            prompt.append("【人物外貌参考】\n");
            prompt.append("以下是需要描绘的人物外貌特征，请用这些外貌特征来描述人物：\n");
            prompt.append("（注意：输出时不要出现人物名字，只用外貌描述）\n\n");
            prompt.append(characterDescription).append("\n");
        }

        // 必须包含的画面元素
        prompt.append("【必须包含的画面元素】\n\n");

        prompt.append("0. 图片类型（选择最合适的）：\n");
        prompt.append("   - editorial illustration：文章配图\n");
        prompt.append("   - lifestyle photography：生活方式摄影\n");
        prompt.append("   - character portrait：人物肖像\n");
        prompt.append("   - scene visualization：场景可视化\n\n");

        prompt.append("1. 人物外貌描述（最多2人，禁止出现名字）：\n");
        prompt.append("   描述格式：【性别】【年龄感】【体型】【发型发色】【瞳色】【肤色】\n");
        prompt.append("   示例：年轻女性，苗条身材，黑色长直发，棕色眼睛，白皙肤色\n");
        prompt.append("   示例：中年男性，健壮体型，灰色短发，深色皮肤\n\n");

        prompt.append("2. 人物动作姿态：\n");
        prompt.append("   - 站立/坐着/跪坐/躺卧/依靠/蹲下\n");
        prompt.append("   - 行走/转身/伸手/弯腰\n");
        prompt.append("   - 两人对视/并肩站立/面对面交谈\n\n");

        prompt.append("3. 人物表情：\n");
        prompt.append("   微笑/严肃/专注/平静/惊讶/害羞\n\n");

        prompt.append("4. 服装描述：\n");
        prompt.append("   格式：【颜色】【款式】【材质】\n");
        prompt.append("   示例：白色丝绸连衣裙 / 深蓝色羊毛西装\n\n");

        prompt.append("5. 配饰细节：\n");
        prompt.append("   描述人物佩戴的饰品、眼镜、帽子、手表等\n");
        prompt.append("   示例：金边眼镜、银色手表、珍珠耳环、黑色皮带\n\n");

        prompt.append("6. 场景环境（重要：必须简化）：\n");
        prompt.append("   【简化原则】只保留2-3个关键背景元素，避免画面拥挤\n");
        prompt.append("   【正确示例】简洁办公室背景，书架和窗户\n");
        prompt.append("   【错误示例】办公室里有书架、窗户、盆栽、电脑、文件柜、沙发...（太多元素）\n\n");

        prompt.append("7. 光线效果：\n");
        prompt.append("   描述光源位置、方向、强度、色温\n");
        prompt.append("   示例：午后阳光从左侧窗户照入，形成柔和侧光\n\n");

        prompt.append("8. 情感基调：\n");
        prompt.append("   描述画面的整体情绪氛围\n");
        prompt.append("   温馨/紧张/神秘/浪漫/忧郁/欢快/宁静/压抑\n\n");

        prompt.append("9. 构图角度：\n");
        prompt.append("   景别：特写/半身/全身/远景\n");
        prompt.append("   角度：平视/俯视/仰视\n\n");

        prompt.append("10. 景深效果：\n");
        prompt.append("   描述背景清晰度和焦点位置\n");
        prompt.append("   - 浅景深：背景虚化，焦点在人物（推荐用于人像）\n");
        prompt.append("   - 深景深：前景背景都清晰（推荐用于场景）\n\n");

        prompt.append("11. 风格氛围：\n");
        prompt.append(style != null ? style : DEFAULT_STYLE).append("\n\n");

        // 输出示例（自然语言描述）
        prompt.append("【输出示例 - 使用自然语言描述】\n\n");

        prompt.append("示例1（单人半身像）：\n");
        prompt.append("这是一张人物肖像照片。画面中央是一位年轻女性，她身材苗条，有着黑色长直发和棕色眼睛，");
        prompt.append("皮肤白皙。她身穿白色丝绸连衣裙，耳垂上挂着珍珠耳环，站立时微微侧身，脸上带着温柔的微笑。");
        prompt.append("背景是简洁的办公室，可以看到书架和窗户，背景被柔和地虚化处理。");
        prompt.append("午后阳光从左侧窗户照进来，形成柔和的侧光效果，整体氛围温馨宁静。");
        prompt.append("半身构图，平视角度，焦点清晰地锁定在她的面部。");
        prompt.append("写实摄影风格，杰作，最佳质量，无多余手指，无变形，无水印。\n\n");

        prompt.append("示例2（双人对话构图）：\n");
        prompt.append("这是一张文章配图风格的图片。画面左侧坐着一位中年男性，他身材健壮，黑色短发，");
        prompt.append("戴着金边眼镜，身穿深蓝色西装，正面向右侧，表情专注。");
        prompt.append("画面右侧站着一位年轻女性，她身材苗条，棕色卷发，身穿白色衬衫，面向左侧微笑着。");
        prompt.append("两人的视线在画面中央交汇。背景是简洁的会议室，落地窗外可以看到城市天际线。");
        prompt.append("明亮的自然光从窗户照进来，营造出专业严肃的氛围。中景构图，平视角度。");
        prompt.append("写实摄影风格，杰作，最佳质量，无多余手指，无水印。\n\n");

        prompt.append("示例3（双人并排构图）：\n");
        prompt.append("这是一张生活方式摄影照片。画面中有两位年轻人并排站立，中间保持着适当的距离。");
        prompt.append("左边是一位年轻男性，高挑身材，黑色短发，穿着灰色休闲西装，面向前方微笑。");
        prompt.append("右边是一位年轻女性，苗条身材，棕色长发，穿着白色连衣裙，同样面向前方微笑。");
        prompt.append("背景是公园的小径，地上铺满了秋日的落叶，背景被适度虚化。");
        prompt.append("柔和的自然光照耀着他们，营造出温馨浪漫的氛围。全身构图，平视角度。");
        prompt.append("写实摄影风格，杰作，最佳质量，无多余手指，无水印。\n\n");

        // 质量增强和排除词要求
        prompt.append("【必须在末尾添加质量标签】\n");
        prompt.append("杰作，最佳质量，极致细节，震撼画面\n\n");

        prompt.append("【必须在末尾添加排除要求】\n");
        prompt.append("无多余手指，无变形，无模糊，无水印，无签名，无任何文字\n\n");

        // 场景描述
        if (sceneDescription != null && !sceneDescription.isEmpty()) {
            prompt.append("【场景描述】\n");
            prompt.append(sceneDescription).append("\n\n");

            // 自动识别场景氛围
            String atmosphere = getAtmosphereByScene(sceneDescription);
            prompt.append("【场景氛围】\n");
            prompt.append("根据场景自动添加以下氛围词：").append(atmosphere).append("\n\n");
        }

        // 人物数量限制提示
        prompt.append("【重要限制】\n");
        prompt.append("1. 画面中最多只能出现2个人物\n");
        prompt.append("2. 禁止出现任何人名、角色名！只用外貌特征描述人物\n");
        prompt.append("3. 只描述画面中能直接看到的东西\n");

        return prompt.toString();
    }

    /**
     * 构建 AI 提示词生成的 Prompt - 旧版兼容方法
     */
    private String buildPrompt(String characterDescription) {
        return buildImagePromptTemplate(characterDescription, null, DEFAULT_STYLE, 200, 500);
    }

    /**
     * 从场景描述中提取核心人物并生成文生图提示词
     * 最多提取2个核心人物（只提取外貌特征，不输出名字）
     *
     * @param sceneDescription 场景描述文本
     * @param characterCards 可用的角色卡列表（可为null）
     * @param style 画风选项
     * @return 生成的文生图提示词
     */
    public String generatePromptWithCoreCharacters(String sceneDescription,
                                                     java.util.List<org.zenithon.articlecollect.entity.CharacterCardEntity> characterCards,
                                                     String style) throws Exception {
        // 构建提取核心人物的提示词
        StringBuilder extractPrompt = new StringBuilder();

        extractPrompt.append("你是场景分析专家。请从以下场景描述中找出最核心、最重要的2个人物。\n");
        extractPrompt.append("注意：输出时不要出现人物名字，只用外貌特征描述人物。\n\n");

        // 如果有角色卡信息，提供给AI参考（只提供外貌，不提供名字用于最终输出）
        if (characterCards != null && !characterCards.isEmpty()) {
            extractPrompt.append("【角色外貌参考】\n");
            for (int i = 0; i < characterCards.size(); i++) {
                org.zenithon.articlecollect.entity.CharacterCardEntity card = characterCards.get(i);
                extractPrompt.append("角色").append(i + 1).append("：");
                if (card.getGender() != null) {
                    extractPrompt.append(card.getGender()).append("，");
                }
                if (card.getAge() != null) {
                    extractPrompt.append(card.getAge()).append("岁左右，");
                }
                if (card.getAppearanceDescription() != null && !card.getAppearanceDescription().isEmpty()) {
                    extractPrompt.append(card.getAppearanceDescription());
                }
                extractPrompt.append("\n");
            }
            extractPrompt.append("\n");
        }

        extractPrompt.append("【分析要求】\n");
        extractPrompt.append("1. 从场景描述中识别所有出现的人物\n");
        extractPrompt.append("2. 判断哪些人物是场景的核心（对话者、动作执行者、情感主体）\n");
        extractPrompt.append("3. 最多选择2个最核心的人物\n");
        extractPrompt.append("4. 如果场景中没有明确人物，返回空数组\n\n");

        extractPrompt.append("【输出格式】严格的JSON数组（name字段填写角色序号，如\"角色1\"）\n");
        extractPrompt.append("[\n");
        extractPrompt.append("  {\n");
        extractPrompt.append("    \"name\": \"角色1\" 或 \"角色2\"，\n");
        extractPrompt.append("    \"importance\": \"high/medium/low\"\n");
        extractPrompt.append("  }\n");
        extractPrompt.append("]\n\n");

        extractPrompt.append("【场景描述】\n");
        extractPrompt.append(sceneDescription);

        // 调用AI提取核心人物
        String extractResult = callDeepSeekAPI(extractPrompt.toString());

        // 解析结果，构建角色描述
        String characterDescription = parseCharacterExtraction(extractResult, characterCards);

        // 使用统一模板生成文生图提示词
        return buildImagePromptTemplate(characterDescription, sceneDescription, style, 100, 300);
    }

    /**
     * 解析人物提取结果，构建角色描述（只包含外貌特征，不包含名字）
     */
    private String parseCharacterExtraction(String extractResult,
                                             java.util.List<org.zenithon.articlecollect.entity.CharacterCardEntity> characterCards) {
        if (extractResult == null || extractResult.isEmpty()) {
            return null;
        }

        try {
            // 尝试解析JSON
            String jsonArray = extractJsonArray(extractResult);
            if (jsonArray == null) {
                return null;
            }

            com.fasterxml.jackson.databind.JsonNode arrayNode = objectMapper.readTree(jsonArray);
            if (!arrayNode.isArray() || arrayNode.size() == 0) {
                return null;
            }

            StringBuilder characterDesc = new StringBuilder();
            int count = 0;

            for (com.fasterxml.jackson.databind.JsonNode node : arrayNode) {
                if (count >= 2) break; // 最多2个人物

                String name = node.path("name").asText();

                if (name != null && !name.isEmpty()) {
                    characterDesc.append("人物").append(count + 1).append("外貌：\n");

                    // 尝试从角色卡中匹配外貌信息（不输出名字）
                    boolean foundAppearance = false;
                    if (characterCards != null) {
                        for (org.zenithon.articlecollect.entity.CharacterCardEntity card : characterCards) {
                            if (card.getName() != null && name.contains(card.getName())) {
                                if (card.getGender() != null) {
                                    characterDesc.append("  - 性别：").append(card.getGender()).append("\n");
                                }
                                if (card.getAge() != null) {
                                    characterDesc.append("  - 年龄感：").append(card.getAge()).append("岁左右\n");
                                }
                                if (card.getAppearanceDescription() != null && !card.getAppearanceDescription().isEmpty()) {
                                    characterDesc.append("  - 外貌特征：").append(card.getAppearanceDescription()).append("\n");
                                }
                                foundAppearance = true;
                                break;
                            }
                        }
                    }

                    // 如果没有匹配到角色卡，根据名字推断基本信息
                    if (!foundAppearance) {
                        // 根据名字判断性别
                        if (name.contains("她") || name.contains("女") || name.contains("姐") || name.contains("妈")) {
                            characterDesc.append("  - 性别：女性\n");
                        } else if (name.contains("他") || name.contains("男") || name.contains("哥") || name.contains("爸")) {
                            characterDesc.append("  - 性别：男性\n");
                        }
                        characterDesc.append("  - 外貌特征：根据场景描述推断\n");
                    }

                    characterDesc.append("\n");
                    count++;
                }
            }

            return characterDesc.length() > 0 ? characterDesc.toString() : null;

        } catch (Exception e) {
            logger.error("解析人物提取结果失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从文本中提取JSON数组
     */
    private String extractJsonArray(String text) {
        if (text == null) return null;

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

        return null;
    }

    /**
     * 调用 DeepSeek API (同步模式)
     * @param prompt 提示词
     * @return AI 返回的内容
     * @throws Exception 调用异常
     */
    public String callDeepSeekAPI(String prompt) throws Exception {
        logger.info("调用DeepSeek API, model={}, prompt长度={}", deepSeekConfig.getModel(), prompt.length());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + deepSeekConfig.getApiKey());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", deepSeekConfig.getModel());
        requestBody.put("max_tokens", 2000);

        Map<String, String> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt);
        requestBody.put("messages", new Object[]{message});

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            deepSeekConfig.getApiUrl(),
            request,
            String.class
        );

        logger.info("DeepSeek API响应状态: {}", response.getStatusCode());

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            String responseBody = response.getBody();
            logger.debug("DeepSeek API响应体长度: {}", responseBody.length());

            // 解析 JSON 响应
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode choices = rootNode.path("choices");

            logger.debug("choices节点存在: {}, 是数组: {}, 大小: {}",
                !choices.isMissingNode(), choices.isArray(), choices.isArray() ? choices.size() : 0);

            if (choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).path("message");
                String content = messageNode.path("content").asText();

                logger.debug("content字段值: {}", content != null ? "长度=" + content.length() : "null");

                // 如果content为空，尝试使用reasoning_content（DeepSeek v4-flash思考模式）
                if ((content == null || content.isEmpty()) && messageNode.has("reasoning_content")) {
                    content = messageNode.path("reasoning_content").asText();
                    logger.info("使用reasoning_content作为响应内容, 长度={}", content.length());
                }

                // 清理可能的思考标签
                if (content != null && !content.isEmpty()) {
                    content = THINK_TAG_PATTERN.matcher(content).replaceAll("").trim();
                    logger.info("DeepSeek API返回成功, 内容长度={}", content.length());
                    return content;
                }
            }

            // 检查是否有错误信息
            JsonNode errorNode = rootNode.path("error");
            if (!errorNode.isMissingNode()) {
                String errorMsg = errorNode.path("message").asText();
                logger.error("DeepSeek API错误: {}", errorMsg);
                throw new RuntimeException("DeepSeek API错误: " + errorMsg);
            }

            logger.warn("DeepSeek API响应中没有有效内容, 响应体: {}",
                responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);
        }

        return null;
    }

    /**
     * 调用 DeepSeek API (完整参数版本，支持思考模式)
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param enableThinking 是否开启思考模式
     * @param reasoningEffort 思考强度 (high/max)
     * @param maxTokens 最大token数
     * @return AI返回的内容
     * @throws Exception 调用异常
     */
    public String callDeepSeekAPIWithThinking(String systemPrompt, String userPrompt,
                                               boolean enableThinking, String reasoningEffort,
                                               int maxTokens) throws Exception {
        return callDeepSeekAPIWithThinking(systemPrompt, userPrompt, enableThinking, reasoningEffort, maxTokens, false, null, null, null, null, null);
    }

    /**
     * 调用 DeepSeek API (完整参数版本，支持思考模式和JSON模式)
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param enableThinking 是否开启思考模式
     * @param reasoningEffort 思考强度 (high/max)
     * @param maxTokens 最大token数
     * @param jsonMode 是否启用JSON模式
     * @return AI返回的内容
     * @throws Exception 调用异常
     */
    public String callDeepSeekAPIWithThinking(String systemPrompt, String userPrompt,
                                               boolean enableThinking, String reasoningEffort,
                                               int maxTokens, boolean jsonMode) throws Exception {
        return callDeepSeekAPIWithThinking(systemPrompt, userPrompt, enableThinking, reasoningEffort, maxTokens, jsonMode, null, null, null, null, null);
    }

    /**
     * 调用 DeepSeek API (完整参数版本，支持所有配置)
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param enableThinking 是否开启思考模式
     * @param reasoningEffort 思考强度 (high/max)
     * @param maxTokens 最大token数
     * @param jsonMode 是否启用JSON模式
     * @param temperature 温度参数 (0-2)
     * @param topP 核采样参数 (0-1)
     * @param frequencyPenalty 频率惩罚 (-2到2)
     * @param presencePenalty 存在惩罚 (-2到2)
     * @param model 指定模型 (可为null使用默认)
     * @return AI返回的内容
     * @throws Exception 调用异常
     */
    public String callDeepSeekAPIWithThinking(String systemPrompt, String userPrompt,
                                               boolean enableThinking, String reasoningEffort,
                                               int maxTokens, boolean jsonMode,
                                               Double temperature, Double topP,
                                               Double frequencyPenalty, Double presencePenalty,
                                               String model) throws Exception {
        // 检查 API Key 是否配置
        if (deepSeekConfig.getApiKey() == null || deepSeekConfig.getApiKey().trim().isEmpty()) {
            logger.warn("DeepSeek API Key 未配置");
            throw new RuntimeException("DeepSeek API Key 未配置，请设置环境变量 DEEPSEEK_API_KEY");
        }

        // 使用指定的模型，如果为空则使用默认模型
        String useModel = (model != null && !model.isEmpty()) ? model : deepSeekConfig.getModel();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + deepSeekConfig.getApiKey());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", useModel);
        requestBody.put("max_tokens", maxTokens);

        // 温度参数
        if (temperature != null) {
            requestBody.put("temperature", temperature);
        }

        // 核采样参数
        if (topP != null) {
            requestBody.put("top_p", topP);
        }

        // 频率惩罚
        if (frequencyPenalty != null) {
            requestBody.put("frequency_penalty", frequencyPenalty);
        }

        // 存在惩罚
        if (presencePenalty != null) {
            requestBody.put("presence_penalty", presencePenalty);
        }

        // 思考模式配置
        if (enableThinking) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            requestBody.put("thinking", thinking);
            if (reasoningEffort != null) {
                requestBody.put("reasoning_effort", reasoningEffort);
            }
        }

        // JSON模式配置
        if (jsonMode) {
            Map<String, String> responseFormat = new HashMap<>();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);
        }

        // 构建消息
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            Map<String, String> systemMessage = new HashMap<>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemPrompt);
            messages.add(systemMessage);
        }
        if (userPrompt != null && !userPrompt.isEmpty()) {
            Map<String, String> userMessage = new HashMap<>();
            userMessage.put("role", "user");
            userMessage.put("content", userPrompt);
            messages.add(userMessage);
        }
        requestBody.put("messages", messages);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        logger.debug("调用DeepSeek API, model={}, thinking={}, maxTokens={}, jsonMode={}, temp={}, topP={}, freqPen={}, presPen={}",
                useModel, enableThinking, maxTokens, jsonMode, temperature, topP, frequencyPenalty, presencePenalty);
        ResponseEntity<String> response = restTemplate.postForEntity(
            deepSeekConfig.getApiUrl(),
            request,
            String.class
        );

        logger.debug("DeepSeek API响应状态: {}", response.getStatusCode());

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            String responseBody = response.getBody();
            logger.debug("DeepSeek API响应体: {}", responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody);

            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode choices = rootNode.path("choices");

            if (choices.isArray() && choices.size() > 0) {
                JsonNode messageNode = choices.get(0).path("message");
                String content = messageNode.path("content").asText();

                // 如果content为空，尝试使用reasoning_content
                if ((content == null || content.isEmpty()) && messageNode.has("reasoning_content")) {
                    content = messageNode.path("reasoning_content").asText();
                    logger.debug("使用reasoning_content作为响应内容");
                }

                // 清理可能的思考标签
                if (content != null && !content.isEmpty()) {
                    content = THINK_TAG_PATTERN.matcher(content).replaceAll("").trim();
                    return content;
                }
            }

            // 检查是否有错误信息
            JsonNode errorNode = rootNode.path("error");
            if (!errorNode.isMissingNode()) {
                String errorMsg = errorNode.path("message").asText();
                throw new RuntimeException("DeepSeek API错误: " + errorMsg);
            }

            logger.warn("DeepSeek API响应中没有有效内容, choices: {}", choices);
        }

        throw new RuntimeException("API调用失败: " + response.getStatusCode());
    }

    /**
     * 调用 DeepSeek API (支持指定模型)
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param enableThinking 是否开启思考模式
     * @param reasoningEffort 思考强度 (high/max)
     * @param maxTokens 最大token数
     * @param jsonMode 是否启用JSON模式
     * @param model 指定的模型名称（如果为null则使用默认模型）
     * @return AI返回的内容
     * @throws Exception 调用异常
     */
    public String callDeepSeekAPIWithModel(String systemPrompt, String userPrompt,
                                            boolean enableThinking, String reasoningEffort,
                                            int maxTokens, boolean jsonMode, String model) throws Exception {
        return callDeepSeekAPIWithThinking(systemPrompt, userPrompt, enableThinking, reasoningEffort,
                maxTokens, jsonMode, null, null, null, null, model);
    }

    /**
     * 流式 AI 对话
     * @param prompt 用户输入的提示词
     * @param emitter SSE 发射器
     * @throws Exception 可能抛出的异常
     */
    public void chatStream(String prompt, SseEmitter emitter) throws Exception {
        // 检查 API Key 是否配置
        if (deepSeekConfig.getApiKey() == null || deepSeekConfig.getApiKey().trim().isEmpty()) {
            logger.warn("DeepSeek API Key 未配置");
            emitter.send(SseEmitter.event()
                .name("error")
                .data("{\"error\": \"DeepSeek API Key 未配置，请在 application.properties 中配置 deepseek.api.key\"}"));
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + deepSeekConfig.getApiKey());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", deepSeekConfig.getModel());
            requestBody.put("stream", true); // 启用流式响应

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", new Object[]{message});

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            // 使用 RestTemplate 执行流式请求
            ResponseEntity<String> response = restTemplate.postForEntity(
                deepSeekConfig.getApiUrl(),
                request,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // 处理流式响应
                String responseBody = response.getBody();
                String[] lines = responseBody.split("\\n");

                StringBuilder fullContent = new StringBuilder();

                for (String line : lines) {
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

                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).path("delta");
                                String content = delta.path("content").asText();

                                if (content != null && !content.isEmpty()) {
                                    fullContent.append(content);

                                    // 发送数据块到前端（保留原始空格）
                                    emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(content));
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("解析 SSE 数据块失败：" + e.getMessage());
                        }
                    }
                }

                logger.info("流式对话完成，总内容长度：{}", fullContent.length());
            } else {
                throw new RuntimeException("API 调用失败：" + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("流式对话失败：" + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 调用 DeepSeek API 并验证JSON格式，失败时自动重试
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @param enableThinking 是否开启思考模式
     * @param reasoningEffort 思考强度 (high/max)
     * @param maxTokens 最大token数
     * @param maxRetries 最大重试次数 (默认3次)
     * @return AI返回的JSON字符串
     * @throws Exception 调用异常或JSON格式错误
     */
    public String callDeepSeekAPIWithJsonRetry(String systemPrompt, String userPrompt,
                                                boolean enableThinking, String reasoningEffort,
                                                int maxTokens, int maxRetries) throws Exception {
        String currentPrompt = userPrompt;
        String lastError = null;
        int attempt = 0;

        while (attempt < maxRetries) {
            attempt++;
            logger.info("JSON生成尝试 {}/{}", attempt, maxRetries);

            try {
                String response = callDeepSeekAPIWithThinking(
                    systemPrompt, currentPrompt,
                    enableThinking, reasoningEffort,
                    maxTokens, true
                );

                if (response == null || response.trim().isEmpty()) {
                    lastError = "API返回空响应";
                    logger.warn("尝试 {}: {}", attempt, lastError);
                    currentPrompt = buildRetryPrompt(userPrompt, lastError, response);
                    continue;
                }

                // 验证JSON格式
                objectMapper.readTree(response);
                logger.info("JSON验证成功，尝试次数: {}", attempt);
                return response;

            } catch (Exception e) {
                lastError = e.getMessage();
                logger.warn("尝试 {} JSON解析失败: {}", attempt, lastError);

                if (attempt < maxRetries) {
                    // 构建重试提示词
                    currentPrompt = buildRetryPrompt(userPrompt, lastError, null);
                }
            }
        }

        // 所有重试都失败
        String errorMsg = String.format("JSON生成失败，已重试%d次。最后错误: %s", maxRetries, lastError);
        logger.error(errorMsg);
        throw new RuntimeException(errorMsg);
    }

    /**
     * 构建重试提示词
     */
    private String buildRetryPrompt(String originalPrompt, String errorMessage, String invalidContent) {
        StringBuilder retryPrompt = new StringBuilder();
        retryPrompt.append("【重要】之前的生成结果JSON格式不正确，请重新生成。\n\n");
        retryPrompt.append("错误信息: ").append(errorMessage).append("\n\n");

        if (invalidContent != null && !invalidContent.isEmpty()) {
            retryPrompt.append("之前生成的内容:\n").append(invalidContent).append("\n\n");
        }

        retryPrompt.append("请严格按照JSON格式输出，注意:\n");
        retryPrompt.append("1. 所有字符串必须用双引号包裹\n");
        retryPrompt.append("2. 对象的键必须用双引号包裹\n");
        retryPrompt.append("3. 不要在JSON中添加注释\n");
        retryPrompt.append("4. 确保所有括号成对出现\n");
        retryPrompt.append("5. 不要在JSON前后添加任何说明文字\n\n");
        retryPrompt.append("原始请求:\n").append(originalPrompt);

        return retryPrompt.toString();
    }

    /**
     * 将提示词截断到指定字符数以内
     * 在最后一个空格或标点处截断，避免截断单词
     */
    private String trimPromptToLength(String prompt, int maxLength) {
        if (prompt == null || prompt.length() <= maxLength) {
            return prompt;
        }

        // 先尝试在最后一个空格处截断
        int lastSpace = prompt.lastIndexOf(' ', maxLength);
        if (lastSpace > maxLength * 0.7) { // 确保不会截断太短
            return prompt.substring(0, lastSpace);
        }

        // 尝试在标点符号处截断
        char[] punctuation = {'.', ',', '!', '?', ';', ':'};
        for (char p : punctuation) {
            int lastPunc = prompt.lastIndexOf(p, maxLength);
            if (lastPunc > maxLength * 0.7) {
                return prompt.substring(0, lastPunc + 1);
            }
        }

        // 如果没有找到合适的截断点，就直接截断
        logger.warn("提示词过长({}字符)，已截断到{}字符", prompt.length(), maxLength);
        return prompt.substring(0, maxLength);
    }

    // ==================== 支持运行时配置的方法 ====================

    /**
     * 使用运行时配置调用 DeepSeek API
     *
     * @param prompt 用户提示词
     * @param config 运行时配置（可为 null，使用默认配置）
     * @return AI 返回的内容
     * @throws Exception 调用异常
     */
    public String callDeepSeekAPIWithConfig(String prompt, org.zenithon.articlecollect.dto.DeepSeekRuntimeConfig config) throws Exception {
        // 如果没有传入配置，使用默认配置
        if (config == null) {
            config = deepSeekConfigService.getDefaultRuntimeConfig(
                    org.zenithon.articlecollect.entity.DeepSeekFeatureConfig.FeatureCode.PROMPT_GENERATION);
        }

        return callDeepSeekAPIWithThinking(
                null, prompt,
                config.getThinkingEnabled(),
                config.getReasoningEffort(),
                2000, false,
                config.getTemperature(),
                config.getTopP(),
                config.getFrequencyPenalty(),
                config.getPresencePenalty(),
                config.getModel()
        );
    }

    /**
     * 使用运行时配置进行流式 AI 对话
     *
     * @param prompt 用户输入的提示词
     * @param emitter SSE 发射器
     * @param config 运行时配置（可为 null，使用默认配置）
     * @throws Exception 可能抛出的异常
     */
    public void chatStreamWithConfig(String prompt, SseEmitter emitter,
                                      org.zenithon.articlecollect.dto.DeepSeekRuntimeConfig config) throws Exception {
        // 如果没有传入配置，使用默认配置
        if (config == null) {
            config = deepSeekConfigService.getDefaultRuntimeConfig(
                    org.zenithon.articlecollect.entity.DeepSeekFeatureConfig.FeatureCode.AI_CHAT);
        }

        // 检查 API Key 是否配置
        if (deepSeekConfig.getApiKey() == null || deepSeekConfig.getApiKey().trim().isEmpty()) {
            logger.warn("DeepSeek API Key 未配置");
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data("{\"error\": \"DeepSeek API Key 未配置，请在 application.properties 中配置 deepseek.api.key\"}"));
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + deepSeekConfig.getApiKey());

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", config.getModel());
            requestBody.put("stream", true);

            // 温度参数
            if (config.getTemperature() != null) {
                requestBody.put("temperature", config.getTemperature());
            }

            // 核采样参数
            if (config.getTopP() != null) {
                requestBody.put("top_p", config.getTopP());
            }

            // 频率惩罚
            if (config.getFrequencyPenalty() != null) {
                requestBody.put("frequency_penalty", config.getFrequencyPenalty());
            }

            // 存在惩罚
            if (config.getPresencePenalty() != null) {
                requestBody.put("presence_penalty", config.getPresencePenalty());
            }

            // 思考模式配置
            if (config.getThinkingEnabled()) {
                Map<String, Object> thinking = new HashMap<>();
                thinking.put("type", "enabled");
                requestBody.put("thinking", thinking);
                requestBody.put("reasoning_effort", config.getReasoningEffort());
            }

            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            requestBody.put("messages", new Object[]{message});

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

            logger.info("调用 DeepSeek API (流式), model={}, thinking={}", config.getModel(), config.getThinkingEnabled());

            // 使用 RestTemplate 执行流式请求
            ResponseEntity<String> response = restTemplate.postForEntity(
                    deepSeekConfig.getApiUrl(),
                    request,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String responseBody = response.getBody();
                String[] lines = responseBody.split("\\n");

                StringBuilder fullContent = new StringBuilder();

                for (String line : lines) {
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

                            if (choices.isArray() && choices.size() > 0) {
                                JsonNode delta = choices.get(0).path("delta");
                                String content = null;

                                // 处理 content
                                if (delta.has("content") && !delta.get("content").isNull()) {
                                    content = delta.get("content").asText();
                                }

                                // 处理 reasoning_content
                                if (content == null || content.isEmpty()) {
                                    JsonNode reasoningNode = delta.get("reasoning_content");
                                    if (reasoningNode != null && !reasoningNode.isNull()) {
                                        content = reasoningNode.asText();
                                    }
                                }

                                if (content != null && !content.isEmpty()) {
                                    fullContent.append(content);
                                    emitter.send(SseEmitter.event()
                                            .name("message")
                                            .data(content));
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("解析 SSE 数据块失败：" + e.getMessage());
                        }
                    }
                }

                logger.info("流式对话完成，总内容长度：{}", fullContent.length());
            } else {
                throw new RuntimeException("API 调用失败：" + response.getStatusCode());
            }
        } catch (Exception e) {
            logger.error("流式对话失败：" + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 流式 AI 对话 - 支持完整消息列表（真正流式）
     * 使用 Java HttpClient 逐行读取 DeepSeek SSE 响应，实时推送给前端
     *
     * @param messages 完整的消息列表（多轮对话上下文）
     * @param emitter  SSE 发射器
     * @param config   运行时配置
     * @return 完整的 AI 回复内容
     */
    public ChatStreamResult chatStreamWithMessages(
            List<Map<String, Object>> messages,
            SseEmitter emitter,
            org.zenithon.articlecollect.dto.DeepSeekRuntimeConfig config) throws Exception {

        if (config == null) {
            config = deepSeekConfigService.getDefaultRuntimeConfig(
                    org.zenithon.articlecollect.entity.DeepSeekFeatureConfig.FeatureCode.AI_CHAT);
        }

        if (deepSeekConfig.getApiKey() == null || deepSeekConfig.getApiKey().trim().isEmpty()) {
            emitter.send(SseEmitter.event().name("error")
                    .data("{\"error\": \"DeepSeek API Key 未配置\"}"));
            return new ChatStreamResult("", null, 0, 0, 0);
        }

        // 构建请求体
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", config.getModel());
        requestBody.put("stream", true);

        if (config.getTemperature() != null) requestBody.put("temperature", config.getTemperature());
        if (config.getTopP() != null) requestBody.put("top_p", config.getTopP());
        if (config.getFrequencyPenalty() != null) requestBody.put("frequency_penalty", config.getFrequencyPenalty());
        if (config.getPresencePenalty() != null) requestBody.put("presence_penalty", config.getPresencePenalty());

        if (config.getThinkingEnabled()) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("type", "enabled");
            requestBody.put("thinking", thinking);
            requestBody.put("reasoning_effort", config.getReasoningEffort());
        }

        requestBody.put("messages", messages);

        String requestBodyJson = objectMapper.writeValueAsString(requestBody);

        // 使用 Java HttpClient 实现真正的流式读取
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(deepSeekConfig.getApiUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + deepSeekConfig.getApiKey())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBodyJson))
                .build();

        logger.info("调用 DeepSeek API (流式), model={}, thinking={}",
                config.getModel(), config.getThinkingEnabled());

        java.net.http.HttpResponse<java.io.InputStream> httpResponse = client.send(
                httpRequest, java.net.http.HttpResponse.BodyHandlers.ofInputStream());

        if (httpResponse.statusCode() != 200) {
            // 读取错误响应体
            String errorBody = new String(httpResponse.body().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            throw new RuntimeException("API 调用失败：" + httpResponse.statusCode() + " " + errorBody);
        }

        StringBuilder fullContent = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(httpResponse.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith(":")) continue;

                if (line.startsWith("data: ")) {
                    String data = line.substring(6);
                    if ("[DONE]".equals(data.trim())) break;

                    try {
                        JsonNode jsonNode = objectMapper.readTree(data);
                        JsonNode choices = jsonNode.path("choices");

                        if (choices.isArray() && choices.size() > 0) {
                            JsonNode delta = choices.get(0).path("delta");

                            // 处理 content
                            if (delta.has("content") && !delta.get("content").isNull()) {
                                String content = delta.get("content").asText();
                                if (!content.isEmpty()) {
                                    fullContent.append(content);
                                    emitter.send(SseEmitter.event().name("message").data(content));
                                }
                            }

                            // 处理 reasoning_content
                            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()) {
                                String rc = delta.get("reasoning_content").asText();
                                if (!rc.isEmpty()) {
                                    reasoningContent.append(rc);
                                    emitter.send(SseEmitter.event().name("reasoning_content").data(rc));
                                }
                            }
                        }

                        // 解析 usage（通常在最后一个 chunk）
                        JsonNode usageNode = jsonNode.path("usage");
                        if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                            promptTokens = usageNode.path("prompt_tokens").asInt(0);
                            completionTokens = usageNode.path("completion_tokens").asInt(0);
                            totalTokens = usageNode.path("total_tokens").asInt(0);
                        }
                    } catch (Exception e) {
                        logger.warn("解析 SSE 数据块失败：" + e.getMessage());
                    }
                }
            }
        }

        logger.info("流式对话完成，内容长度: {}, tokens: {}/{}/{}",
                fullContent.length(), promptTokens, completionTokens, totalTokens);

        return new ChatStreamResult(fullContent.toString(), reasoningContent.toString(),
                promptTokens, completionTokens, totalTokens);
    }

    /**
     * 流式对话结果
     */
    public static class ChatStreamResult {
        private final String content;
        private final String reasoningContent;
        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        public ChatStreamResult(String content, String reasoningContent,
                                int promptTokens, int completionTokens, int totalTokens) {
            this.content = content;
            this.reasoningContent = reasoningContent;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public String getContent() { return content; }
        public String getReasoningContent() { return reasoningContent; }
        public int getPromptTokens() { return promptTokens; }
        public int getCompletionTokens() { return completionTokens; }
        public int getTotalTokens() { return totalTokens; }
    }
}
