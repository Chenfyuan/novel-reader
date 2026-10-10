# 墨阅

一款基于 Jetpack Compose 与 Material 3 的 Android 本地小说阅读器，支持 EPUB 与 TXT。

## 截图

| 书架（浅色） | 书架（深色） | 阅读页 | 阅读菜单 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/01_shelf_grid_light.png" width="200"> | <img src="docs/screenshots/01_shelf_grid_dark.png" width="200"> | <img src="docs/screenshots/03_reader_sepia_light.png" width="200"> | <img src="docs/screenshots/04_reader_menu_light.png" width="200"> |

| 设置 | 竹青主题色 | 主题色设置 | 阅读统计 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/07_settings_light.png" width="200"> | <img src="docs/screenshots/09_shelf_seed_green_light.png" width="200"> | <img src="docs/screenshots/08_settings_seed_green_light.png" width="200"> | <img src="docs/screenshots/06_stats_light.png" width="200"> |

> 截图由 Roborazzi + Robolectric 截图测试生成，使用示例数据。

## 功能

### 阅读
- 按屏幕自动分页，左右滑动翻页，可跨章节
- 三种翻页动画：平移、3D 立方、层叠翻页
- 点按翻页（左右分区或全屏下一页），音量键翻页
- 字号、行距、段间距、页边距、首行缩进可调
- 四种阅读主题：明亮、羊皮纸、护眼、夜间
- 字体：系统默认、衬线，或导入 TTF/OTF 字体
- 繁体显示（Android 10+，逐字转换）
- 目录、书签（带章节与摘要）、全书搜索（关键词高亮）
- 精确到字符的全书阅读进度，自动记住阅读位置
- 沉浸式全屏，可调亮度，屏幕常亮，页脚显示时间与电量

### 格式
- EPUB：解析 container.xml / OPF / spine，目录支持 EPUB3 nav 与 NCX，提取封面，正文内嵌图片按页宽显示
- TXT：自动识别 UTF-8 / UTF-16（BOM）/ GBK / GB18030；按「第X章/回/节/卷」等标题分章，无标题时按约 6000 字分块
- 通过系统文件选择器（SAF）导入，无需存储权限

### 听书
- 使用系统 TextToSpeech（简体中文），播放 / 暂停 / 停止
- 语速、音调可调，自动翻页翻章并高亮当前朗读段落
- 定时关闭（15–90 分钟）
- 前台服务通知（mediaPlayback 类型），可在通知中暂停 / 停止

### 书架
- Room 数据库保存书籍、封面、进度
- 网格 / 列表视图，网格列数可设（自动 / 2 / 3 / 4）
- 按书名或作者搜索，按最近阅读 / 最近添加 / 书名排序
- 顶部「继续阅读」卡片，封面进度条
- 阅读统计：累计时长、最近 7 天柱状图、各书阅读时长

### 外观与主题
- 严格遵循 Material 3：配色角色、字体层级、圆角层级、4/8dp 间距
- 动态取色（Android 12+），或从 10 种预设主题色生成配色（支持标准 / 中 / 高对比度）
- 深色模式（跟随系统 / 浅色 / 深色），纯黑模式
- M3 动效：书架 → 阅读页容器变换、页面间共享轴、标签页淡入淡出、预测性返回手势
- Edge-to-edge，自适应图标（支持单色主题图标）

### 数据
- 备份到 JSON 文件：阅读进度、书签、阅读统计、设置
- 从文件恢复（匹配书架上已有的书）
- 清除缓存、恢复默认设置
- 设置使用 DataStore 持久化

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin | 2.4.21 |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.8.1 |
| Compose BOM | 2026.09.00 |
| Material 3 | 随 Compose BOM |
| Room（KSP 2.3.12） | 2.8.5 |
| DataStore Preferences | 1.2.1 |
| jsoup | 1.21.2 |
| MaterialKolor | 5.0.1 |
| Roborazzi / Robolectric（截图测试） | 1.76.0 / 4.17 |
| compileSdk / targetSdk | 37 |
| minSdk | 26（Android 8.0） |

## 构建

环境要求：JDK 21，Android SDK Platform 37 与 Build-Tools 37.0.0。

```bash
# 指定 SDK 位置（或在 local.properties 中写入 sdk.dir=...）
export ANDROID_HOME=/path/to/android-sdk

# 构建 Debug APK，产物位于 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug

# 生成截图（Roborazzi），默认输出到 app/build/screenshots
./gradlew testDebugUnitTest -Proborazzi.test.record=true
```

## 项目结构

```
app/src/main/java/com/tavi/novelreader/
├── MainActivity.kt          # 入口、页面路由与转场、音量键
├── AppViewModel.kt          # 导入、进度、书签、搜索、备份恢复、清缓存、字体导入
├── data/
│   ├── Db.kt                # Room：书籍、书签、每日阅读时长
│   ├── Settings.kt          # 设置模型与 DataStore 仓库
│   └── Backup.kt            # JSON 备份 / 恢复
├── parser/Parsers.kt        # EPUB / TXT 解析、编码识别、繁体转换
├── tts/ReadAloud.kt         # 朗读控制器与前台服务
└── ui/
    ├── Theme.kt             # M3 主题、动态取色 / 种子色、动效令牌
    ├── Bookshelf.kt         # 书架
    ├── Reader.kt            # 阅读页、分页、翻页动画、菜单、面板
    ├── SettingsScreen.kt    # 设置页、开源许可
    └── Stats.kt             # 阅读统计
app/src/test/.../ScreenshotTest.kt   # 截图测试
```

## 已知限制

- 尚未在真机或模拟器上进行完整测试，以上截图来自截图测试
- EPUB 正文渲染为段落文本 + 图片，不支持表格、CSS 样式、内联 data: 图片和 SVG 图片
- 非 EPUB 文件一律按纯文本处理；超大 TXT 打开时整体读入内存
- 导入大 EPUB、首次打开旧版本导入的书时需要遍历所有章节，速度较慢
- 听书依赖设备上的 TTS 引擎与中文语音包；通知为普通前台通知，不是 MediaSession 媒体卡片，不响应耳机按键
- 繁体显示为逐字转换，非词组级；搜索与进度字数仍基于原文
- 备份不含书籍文件，恢复时按「书名 + 作者 + 格式」匹配已有书籍
- 使用动态取色时，对比度设置跟随系统
- 「层叠翻页」是旋转 + 阴影的近似效果，不是真实的卷页

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
