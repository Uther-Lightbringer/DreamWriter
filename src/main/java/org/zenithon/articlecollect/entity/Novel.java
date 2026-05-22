package org.zenithon.articlecollect.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 小说实体类
 */
@Entity
@Table(name = "novels")
public class Novel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 255)
    private String title;
    
    @Column(length = 1000)
    private String author;
    
    @Column(length = 10000)
    private String description;
    
    @Column(name = "world_view", length = 50000, columnDefinition = "TEXT")
    private String worldView;

    @Column(name = "outline", length = 50000, columnDefinition = "TEXT")
    private String outline;  // 小说大纲

    @Column(name = "previous_recap", length = 50000, columnDefinition = "TEXT")
    private String previousRecap;  // 前情提要：所有已生成章节的合并概括

    @Column(name = "create_time")
    private LocalDateTime createTime;
    
    @Column(name = "update_time")
    private LocalDateTime updateTime;
    
    // 小说封面图片路径
    @Column(name = "cover_image", length = 500)
    private String coverImage;
    
    // 一对多关系：一个小说对应多个章节
    @OneToMany(mappedBy = "novel", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JsonIgnore
    private List<Chapter> chapters;
    
    // 用于 Thymeleaf 模板的格式化时间字符串
    @Transient
    private String formattedCreateTime;

    // 章节数量（用于 JSON 序列化）
    @Transient
    private Integer chaptersCount;
    
    public Novel() {
    }
    
    public Novel(String title) {
        this.title = title;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.createTime);
    }
    
    public Novel(String title, String author) {
        this.title = title;
        this.author = author;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.createTime);
    }
    
    public Novel(String title, String author, String description) {
        this.title = title;
        this.author = author;
        this.description = description;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.createTime);
    }
    
    public Novel(Long id, String title) {
        this.id = id;
        this.title = title;
        this.createTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.createTime);
    }

    // getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.updateTime);
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.updateTime);
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.updateTime);
    }

    public String getWorldView() {
        return worldView;
    }

    public void setWorldView(String worldView) {
        this.worldView = worldView;
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.updateTime);
    }

    public String getOutline() {
        return outline;
    }

    public void setOutline(String outline) {
        this.outline = outline;
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.updateTime);
    }

    public String getPreviousRecap() {
        return previousRecap;
    }

    public void setPreviousRecap(String previousRecap) {
        this.previousRecap = previousRecap;
        this.updateTime = LocalDateTime.now();
        this.formattedCreateTime = formatTime(this.updateTime);
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
        this.formattedCreateTime = formatTime(createTime);
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
        this.formattedCreateTime = formatTime(updateTime);
    }
    
    public String getCoverImage() {
        return coverImage;
    }
    
    public void setCoverImage(String coverImage) {
        this.coverImage = coverImage;
    }
    
    public List<Chapter> getChapters() {
        return chapters;
    }
    
    public void setChapters(List<Chapter> chapters) {
        this.chapters = chapters;
    }
    
    public String getFormattedCreateTime() {
        return formattedCreateTime;
    }
    
    public void setFormattedCreateTime(String formattedCreateTime) {
        this.formattedCreateTime = formattedCreateTime;
    }
    
    // 公共方法：格式化时间供外部使用
    public String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
    
    // 私有方法：格式化时间
    private String formatTime(LocalDateTime dateTime) {
        return formatDateTime(dateTime);
    }

    public Integer getChaptersCount() {
        return chaptersCount;
    }

    public void setChaptersCount(Integer chaptersCount) {
        this.chaptersCount = chaptersCount;
    }

    @Override
    public String toString() {
        return "Novel{" +
                "id=" + id +
                ", title='" + title + '\'' +
                ", author='" + author + '\'' +
                ", description='" + description + '\'' +
                ", worldView='" + (worldView != null ? worldView.substring(0, Math.min(50, worldView.length())) + "..." : "null") + '\'' +
                ", outline='" + (outline != null ? outline.substring(0, Math.min(50, outline.length())) + "..." : "null") + '\'' +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                ", coverImage='" + getCoverImage() + '\'' +
                ", chapters=" + (chapters != null ? chapters.size() : 0) +
                '}';
    }
}
