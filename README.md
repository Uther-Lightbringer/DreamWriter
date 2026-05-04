# 梦境文阁 | Dream Library

<p align="center">
  <img src="docs/images/logo.png" alt="梦境文阁 Logo" width="200">
</p>

<p align="center">
  <strong>一个基于 Spring Boot 的智能小说管理平台</strong>
</p>

<p align="center">
  <a href="#功能特性">功能特性</a> •
  <a href="#技术栈">技术栈</a> •
  <a href="#快速开始">快速开始</a> •
  <a href="#配置">配置</a> •
  <a href="#api-文档">API 文档</a> •
  <a href="#贡献">贡献</a>
</p>

<p align="center">
  <a href="https://www.apache.org/licenses/LICENSE-2.0">
    <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  </a>
  <a href="https://spring.io/projects/spring-boot">
    <img src="https://img.shields.io/badge/Spring%20Boot-3.0.2-brightgreen.svg" alt="Spring Boot">
  </a>
  <a href="https://www.java.com/">
    <img src="https://img.shields.io/badge/Java-17-orange.svg" alt="Java 17">
  </a>
</p>

---

## 功能特性

### 📚 小说管理
- 创建、查看、删除小说
- 世界观设置与角色卡管理
- 小说封面图片上传

### 📖 章节管理
- 章节创建、编辑、删除
- 智能导航：上一章/下一章快速切换
- 章节标签自动分析

### 🎨 AI 功能
- **AI 配图**：自动分析章节内容，调用 AI 生成配图
- **AI 聊天**：集成 DeepSeek AI 对话功能
- **创作助手**：AI 辅助小说创作，创意引导
- **小说生成器**：自动生成长篇小说

### 📱 多平台支持
- Web 应用（响应式设计）
- Android 移动应用（Capacitor）

### 🔧 REST API
- 完整的 HTTP API 供外部调用
- 支持第三方集成

---

## 技术栈

| 类别 | 技术 |
|------|------|
| 后端 | Java 17 + Spring Boot 3.0.2 |
| 前端 | HTML + JavaScript + CSS + Thymeleaf |
| 移动端 | Capacitor (Android) |
| 数据库 | H2 (文件数据库) |
| AI 服务 | DeepSeek、EvoLink、火山引擎 |
| 构建工具 | Maven |

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+
- Node.js 16+ (可选，用于移动端构建)

### 安装与运行

```bash
# 克隆项目
git clone https://github.com/zenithon/dreamwriter.git
cd dream-library

# 编译项目
mvn clean compile

# 运行项目
mvn spring-boot:run

# 或者打包后运行
mvn clean package
java -jar target/articleCollect-0.0.1-SNAPSHOT.jar
```

### 访问

- **主应用**: http://localhost:38081
- **H2 数据库控制台**: http://localhost:38081/h2-console
  - JDBC URL: `jdbc:h2:file:./data/article_collect`
  - 用户名: `sa`
  - 密码: (留空)

---

## 配置

### 环境变量配置

创建 `.env` 文件或设置以下环境变量：

| 环境变量 | 说明 | 必需 |
|---------|------|------|
| `DEEPSEEK_API_KEY` | DeepSeek AI 聊天 API 密钥 | ✅ AI 聊天功能 |
| `DEEPSEEK_API_URL` | DeepSeek API 地址 | ❌ 有默认值 |
| `EVOLINK_API_TOKEN` | EvoLink 图片生成 API 令牌 | ✅ AI 绘图功能 |
| `VOLCENGINE_ACCESS_KEY` | 火山引擎 Access Key | ❌ 备用图片服务 |
| `VOLCENGINE_SECRET_KEY` | 火山引擎 Secret Key | ❌ 备用图片服务 |

### 配置文件

复制示例配置文件并根据需要修改：

```bash
cp src/main/resources/application.properties.example src/main/resources/application.properties
```

---

## 截图

| 截图 | 说明 |
|------|------|
| ![首页](img/index.jpg) | 首页 |
| ![视觉叙事](img/视觉叙事.jpg) | 视觉叙事 |
| ![视觉叙事创作引导](img/视觉叙事创作引导.jpg) | 视觉叙事创作引导 |
| ![视觉叙事创作引导2](img/视觉叙事创作引导2.jpg) | 视觉叙事创作引导2 |
| ![选择文字](img/选择文字.jpg) | 选择文字 |
| ![选择文字生成图片](img/选择文字生成图片.jpg) | 选择文字生成图片 |
| ![对AI生成的内容进行批注](img/对AI生成的内容进行批注.jpg) | 对AI生成的内容进行批注 |
| ![异能校园-第一章 猎物的选定](img/异能校园-第一章_猎物的选定-6747.44116227872.png) | 异能校园-第一章 猎物的选定 |

---

## API 文档

### 小说 API

```http
# 创建小说
POST /api/novels
Content-Type: application/json
{"title": "小说标题"}

# 获取所有小说
GET /api/novels

# 获取特定小说
GET /api/novels/{novelId}

# 删除小说
DELETE /api/novels/{novelId}
```

### 章节 API

```http
# 创建章节
POST /api/novels/{novelId}/chapters
Content-Type: application/json
{"title": "章节标题", "content": "章节内容"}

# 获取章节列表
GET /api/novels/{novelId}/chapters

# 获取章节详情（含导航信息）
GET /api/chapters/detail/{novelId}/{chapterId}

# 更新章节
PUT /api/novels/{novelId}/chapters/{chapterId}

# 删除章节
DELETE /api/novels/{novelId}/chapters/{chapterId}
```

### AI API

```http
# AI 聊天 (SSE 流式)
POST /api/ai/chat

# 生成 AI 图片
POST /api/image/generate

# 查询图片生成状态
GET /api/image/status/{taskId}

# 获取图片生成历史
GET /api/image/history
```

更多 API 详情请参考 [CLAUDE.md](CLAUDE.md)。

---

## 项目结构

```
src/
├── main/
│   ├── java/org/zenithon/articlecollect/
│   │   ├── controller/          # Web 和 REST API 控制器
│   │   ├── service/             # 业务逻辑实现
│   │   ├── repository/          # 数据访问层
│   │   ├── entity/              # JPA 实体类
│   │   ├── dto/                 # 数据传输对象
│   │   ├── config/              # 配置类
│   │   └── util/                # 工具类
│   └── resources/
│       ├── templates/           # Thymeleaf 模板
│       ├── static/              # 静态资源
│       └── application.properties
└── test/                        # 测试代码

mobile/                          # Android 移动应用
├── src/                         # Web 应用源码
├── android/                     # 原生 Android 项目
└── capacitor.config.json        # Capacitor 配置
```

---

## 移动端构建

```bash
cd mobile

# 安装依赖
npm install

# 构建 Web 资源
npm run build

# 同步到 Android 项目
npx cap sync

# 打开 Android Studio
npx cap open android
```

---

## 贡献

欢迎贡献代码！请查看 [贡献指南](CONTRIBUTING.md)。

1. Fork 本仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

---

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。

---

## 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [DeepSeek AI](https://www.deepseek.com/)
- [Capacitor](https://capacitorjs.com/)
- 所有贡献者

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/zenithon">Zenithon</a>
</p>
