package org.zenithon.articlecollect.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 百度搜索服务
 * 集成百度千帆AI搜索API，为创作引导提供实时素材搜索能力
 */
@Service
public class BaiduSearchService {

    private static final Logger logger = LoggerFactory.getLogger(BaiduSearchService.class);
    private static final String API_URL = "https://qianfan.baidubce.com/v2/ai_search/web_search";

    private final RestTemplate restTemplate;
    private final String baiduApiKey;

    public BaiduSearchService(
        @Value("${baidu.api.key:}") String baiduApiKey,
        RestTemplate restTemplate
    ) {
        this.baiduApiKey = baiduApiKey;
        this.restTemplate = restTemplate;
    }

    /**
     * 搜索是否可用
     */
    public boolean isAvailable() {
        return baiduApiKey != null && !baiduApiKey.trim().isEmpty();
    }

    /**
     * 执行百度搜索
     *
     * @param query     搜索关键词
     * @param count     返回结果数量（1-50，默认10）
     * @param freshness 时间范围过滤：pd(24小时)/pw(7天)/pm(31天)/py(365天)/YYYY-MM-DDtoYYYY-MM-DD
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, Integer count, String freshness) {
        if (!isAvailable()) {
            logger.warn("百度搜索API密钥未配置，跳过搜索");
            return Collections.emptyList();
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();

            // 消息
            requestBody.put("messages", List.of(
                Map.of("content", query, "role", "user")
            ));

            // 搜索源
            requestBody.put("search_source", "baidu_search_v2");

            // 资源类型过滤
            List<Map<String, Object>> resourceFilter = new ArrayList<>();
            resourceFilter.add(Map.of(
                "type", "web",
                "top_k", count != null ? count : 10
            ));
            requestBody.put("resource_type_filter", resourceFilter);

            // 时间过滤
            if (freshness != null && !freshness.trim().isEmpty()) {
                Map<String, Object> filter = new LinkedHashMap<>();
                filter.put("freshness", freshness);
                requestBody.put("search_filter", filter);
            }

            // 构建请求头
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + baiduApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            // 调用API
            ResponseEntity<Map> response = restTemplate.postForEntity(
                API_URL,
                entity,
                Map.class
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                logger.warn("百度搜索返回空响应");
                return Collections.emptyList();
            }

            // 检查错误
            if (body.containsKey("code") && !"200".equals(String.valueOf(body.get("code")))) {
                logger.warn("百度搜索API错误: {} - {}", body.get("code"), body.get("message"));
                return Collections.emptyList();
            }

            // 解析结果
            Object refsObj = body.get("references");
            if (refsObj instanceof List) {
                List<Map<String, Object>> references = (List<Map<String, Object>>) refsObj;
                return references.stream()
                    .map(this::convertToSearchResult)
                    .toList();
            }

            return Collections.emptyList();

        } catch (Exception e) {
            logger.error("百度搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 简单搜索（仅关键词）
     */
    public List<SearchResult> search(String query) {
        return search(query, 10, null);
    }

    private SearchResult convertToSearchResult(Map<String, Object> ref) {
        SearchResult result = new SearchResult();
        result.setTitle(getString(ref, "title"));
        result.setUrl(getString(ref, "url"));
        result.setAbstractText(getString(ref, "abstract"));
        result.setEnclosureUrl(getString(ref, "enclosureUrl"));
        result.setSiteName(getString(ref, "siteName"));
        result.setPublishTime(getString(ref, "publishTime"));
        return result;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 搜索结果
     */
    public static class SearchResult {
        private String title;
        private String url;
        private String abstractText;
        private String enclosureUrl;
        private String siteName;
        private String publishTime;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getAbstractText() { return abstractText; }
        public void setAbstractText(String abstractText) { this.abstractText = abstractText; }

        public String getEnclosureUrl() { return enclosureUrl; }
        public void setEnclosureUrl(String enclosureUrl) { this.enclosureUrl = enclosureUrl; }

        public String getSiteName() { return siteName; }
        public void setSiteName(String siteName) { this.siteName = siteName; }

        public String getPublishTime() { return publishTime; }
        public void setPublishTime(String publishTime) { this.publishTime = publishTime; }
    }
}
