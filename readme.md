# MemeManager (表情包管理器)

**MemeManager** 是一款专为 Android 平台设计的高效表情包管理工具，采用 Jetpack Compose (Material3) 构建。本项目致力于通过现代化的架构与算法优化，解决海量表情包的存储、检索与分发效率问题，提供稳定、流畅且高度可定制的用户体验。

## 核心功能 (Core Features)

### 1. 高性能渲染与预览系统
*   **异构渲染策略**：针对列表视图采用软解码与静态帧渲染策略，有效降低 GPU 负载与内存占用，确保在加载数千张图片时的滑动流畅性；针对预览视图启用全功能硬解码，支持高保真回放。
*   **全格式支持**：集成 Coil 图像加载引擎，原生支持 **JPG、PNG、GIF、Animated WebP** 及 **WebM** 视频格式贴纸的解码与播放。
*   **智能预览交互**：长按触发沉浸式预览窗口（适配 60% 屏幕宽度），支持自适应高度布局与动图实时播放。

### 2. 文件管理与I/O优化
*   **多源导入机制**：
    *   支持 HTTP/HTTPS 协议的 ZIP 直链下载，内置文件名智能解析算法。
    *   支持通过 Intent Action 从系统文件管理器或第三方应用（如 Telegram）直接导入 ZIP 归档。
*   **智能预检与清理**：导入前对 ZIP 文件头进行流式采样检测，仅当归档包含有效图像数据时执行解压，有效防止无效文件写入。内置缓存自动回收机制，定期清理临时文件。
*   **数据持久化与迁移**：支持将分类配置与下载记录导出为 JSON 格式，并提供基于协程并发机制的批量恢复下载功能。支持将特定表情包打包导出为 ZIP 文件进行二次分发。

### 3. 分类与元数据管理
*   **自定义排序算法**：实现基于“置顶 (Pin)”与“手动排序”的混合排序逻辑，支持文件夹重命名与批量删除。
*   **生命周期感知**：应用在 `ON_RESUME` 生命周期自动同步文件系统状态，确保外部文件变更即时反映在 UI 层。

### 4. 辅助功能与扩展
*   **悬浮窗服务**：实现系统级悬浮窗（System Alert Window），支持全局位置记忆与拖拽交互。集成防误触算法，长按 1.5 秒可暂时挂起服务。
*   **自适应分享适配器**：针对微信 (WeChat) 与 QQ 实现 Intent 类名定向投递，绕过系统级选择器直接调起目标应用的联系人选择界面。支持用户自定义常用分享目标列表。

## 技术架构 (Technical Architecture)

*   **User Interface**: Jetpack Compose (Material Design 3)
*   **Image Loading**: Coil (ImageDecoder, GifDecoder, VideoFrameDecoder)
*   **Asynchronous Processing**: Kotlin Coroutines (Dispatchers.IO, Semaphore)
*   **Networking**: OkHttp
*   **Data Persistence**: SharedPreferences, JSON (Gson), Scoped Storage
*   **System Integration**: Service, BroadcastReceiver, Intent Filter configuration

---

## 安装与使用

1.  **导入资源**：通过主界面右上角“导入”按钮选择本地文件，或输入 ZIP 链接进行下载。亦支持从外部应用分享 ZIP 文件至 MemeManager。
2.  **资源管理**：在管理面板中对表情包进行重命名、置顶、排序或导出操作。
3.  **快捷交互**：在设置中开启悬浮窗权限，通过悬浮球快速访问表情库。

