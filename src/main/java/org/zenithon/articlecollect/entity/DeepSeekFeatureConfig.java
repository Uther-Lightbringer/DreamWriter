package org.zenithon.articlecollect.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * DeepSeek 功能配置实体
 *
 * 按功能模块独立配置 DeepSeek 模型参数
 */
@Entity
@Table(name = "deepseek_feature_config")
public class DeepSeekFeatureConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 功能代码（枚举）
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "feature_code", unique = true, nullable = false, length = 50)
    private FeatureCode featureCode;

    /**
     * 功能名称（中文显示）
     */
    @Column(name = "feature_name", nullable = false, length = 100)
    private String featureName;

    /**
     * 模型名称
     * 可选值: deepseek-v4-flash, deepseek-v4-pro
     */
    @Column(nullable = false, length = 30)
    private String model = "deepseek-v4-flash";

    /**
     * 是否启用思考模式
     */
    @Column(name = "thinking_enabled", nullable = false)
    private Boolean thinkingEnabled = false;

    /**
     * 推理强度
     * 可选值: high, max
     */
    @Column(name = "reasoning_effort", length = 10)
    private String reasoningEffort = "high";

    /**
     * 温度参数 (0-2)
     * 控制输出随机性：0=最确定，1=默认，2=最随机
     * 较低值适合精确任务，较高值适合创意任务
     */
    @Column(nullable = false)
    private Double temperature = 0.0;

    /**
     * 核采样参数 (0-1)
     * 控制候选词范围：0.1=只选最高概率词，1=考虑所有词
     */
    @Column(name = "top_p", nullable = false)
    private Double topP = 0.1;

    /**
     * 频率惩罚 (-2到2)
     * 降低重复用词概率：正值减少重复，负值允许更多重复
     */
    @Column(name = "frequency_penalty", nullable = false)
    private Double frequencyPenalty = 2.0;

    /**
     * 存在惩罚 (-2到2)
     * 鼓励谈论新话题：正值鼓励多样性，负值允许聚焦同一话题
     */
    @Column(name = "presence_penalty", nullable = false)
    private Double presencePenalty = 1.0;

    /**
     * 功能描述
     */
    @Column(length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 功能代码枚举
     */
    public enum FeatureCode {
        CREATIVE_GUIDANCE("创作引导", "小说创作引导对话，支持工具调用"),
        AI_CHAT("AI 聊天", "通用 AI 对话"),
        PROMPT_GENERATION("提示词生成", "AI 绘画提示词生成");

        private final String displayName;
        private final String description;

        FeatureCode(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    // 默认构造函数
    public DeepSeekFeatureConfig() {}

    // 构造函数（用于初始化）
    public DeepSeekFeatureConfig(FeatureCode featureCode) {
        this.featureCode = featureCode;
        this.featureName = featureCode.getDisplayName();
        this.description = featureCode.getDescription();
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public FeatureCode getFeatureCode() {
        return featureCode;
    }

    public void setFeatureCode(FeatureCode featureCode) {
        this.featureCode = featureCode;
    }

    public String getFeatureName() {
        return featureName;
    }

    public void setFeatureName(String featureName) {
        this.featureName = featureName;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Boolean getThinkingEnabled() {
        return thinkingEnabled;
    }

    public void setThinkingEnabled(Boolean thinkingEnabled) {
        this.thinkingEnabled = thinkingEnabled;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
