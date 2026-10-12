# 墨阅

[![Android CI](https://github.com/Chenfyuan/novel-reader/actions/workflows/android.yml/badge.svg)](https://github.com/Chenfyuan/novel-reader/actions/workflows/android.yml)
[![最新版本](https://img.shields.io/github/v/release/Chenfyuan/novel-reader?label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC)](https://github.com/Chenfyuan/novel-reader/releases/latest)

墨阅是一款本地电子书阅读器，用 Jetpack Compose 和 Material 3 写成。它能读 EPUB、TXT、MOBI/AZW3、PDF，也能看 CBZ 漫画。

书放在手机本地，不需要注册账号，也不用依赖哪家书城。界面尽量贴近系统原生的样子，把注意力留给正文。

## 截图

| 书架（浅色） | 书架（深色） | 阅读页 | 阅读菜单 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/01_shelf_grid_light.png" width="200"> | <img src="docs/screenshots/01_shelf_grid_dark.png" width="200"> | <img src="docs/screenshots/03_reader_sepia_light.png" width="200"> | <img src="docs/screenshots/04_reader_menu_light.png" width="200"> |

| 设置 | 竹青主题色 | 主题色设置 | 阅读统计 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/07_settings_light.png" width="200"> | <img src="docs/screenshots/09_shelf_seed_green_light.png" width="200"> | <img src="docs/screenshots/08_settings_seed_green_light.png" width="200"> | <img src="docs/screenshots/06_stats_light.png" width="200"> |

| 富文本 EPUB | 选中文字 | 划线与笔记 | 笔记列表 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/11_rich_epub_light.png" width="200"> | <img src="docs/screenshots/12_selection_toolbar_light.png" width="200"> | <img src="docs/screenshots/13_highlights_light.png" width="200"> | <img src="docs/screenshots/14_notes_tab_light.png" width="200"> |

| 上下滚动 | 仿真翻页 | 分组与标签 | WebDAV 同步 |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/17_scroll_mode_light.png" width="200"> | <img src="docs/screenshots/24_page_curl_light.png" width="200"> | <img src="docs/screenshots/20_shelf_groups_light.png" width="200"> | <img src="docs/screenshots/23_webdav_light.png" width="200"> |

| PDF | 漫画（CBZ） |
| :---: | :---: |
| <img src="docs/screenshots/33_pdf_viewer_light.png" width="200"> | <img src="docs/screenshots/34_comic_viewer_dark.png" width="200"> |

平板 / 折叠屏（1280×800dp）：

<img src="docs/screenshots/31_tablet_shelf_light.png" width="640">

<img src="docs/screenshots/30_tablet_spread_light.png" width="640">

> 截图由 Roborazzi + Robolectric 截图测试生成，使用示例数据。

## 功能

### 支持的格式
- **EPUB**：支持 EPUB2 和 EPUB3 的目录。保留粗体、斜体、标题、引用、列表、表格、注音等排版，也支持常用的 CSS 子集，书中图片按页宽显示。
- **TXT**：自动识别 UTF-8、UTF-16、GBK 和 GB18030 编码。按「第 X 章 / 回 / 节 / 卷」分章，找不到章节标题时按篇幅分段。
- **MOBI / AZW3**：只支持无 DRM 的文件，用纯 Kotlin 解析，会读出封面、书名和作者。
- **PDF**：按页渲染，支持缩放和双击放大。有文字层的 PDF 还能选择文字、全文搜索、朗读。
- **CBZ / ZIP 漫画**：图片按自然顺序排列（page2 排在 page10 前面）。可以从右往左读。
- **大文件**：导入时流式解析一次并按章缓存，几十 MB 的 TXT 打开也很快。
- 通过系统文件选择器导入，不需要存储权限。不做 DRM 检查，能解析的文件直接打开。

### 阅读体验
- 排版：字号、行距、段间距、页边距、首行缩进都能调。
- 阅读主题：明亮、羊皮纸、护眼、夜间四种。也可以导入自己的 TTF/OTF 字体。
- 阅读方式：左右翻页或上下连续滚动。支持点按翻页、音量键翻页，章节之间无缝衔接。
- 繁体显示：基于 OpenCC 词库按词组转换，可选繁体、台湾正体、香港繁体。
- 目录、书签、全书搜索，阅读位置精确到字。
- 平板和折叠屏：宽屏显示双页，带侧边导航栏，书架改为双栏。双页间距会对齐折叠屏铰链，桌面姿态下正文只占上半屏。

### 翻页动画
- 平移、3D 立方、层叠，以及**仿真翻页**。
- 仿真翻页按圆柱卷曲来画：用手指拖起页角，纸张沿弧面卷起。正面随弯曲逐渐变暗，背面透出反向的文字，下面一页有投影。可以向前翻，也可以向后翻。

### 划线、笔记与查词
- 长按选中文字，拖动可以扩展选区，也能跨页选择。
- 选区工具栏：划线（5 种颜色，可选下划线样式）、写笔记、查词、复制、分享、搜索。
- 所有划线和笔记集中在「笔记」页查看，可以跳转原文，也能导出为 Markdown。
- **查词与翻译**：选中文字后点「查词」，在底部面板里看翻译。
  - 翻译在手机上离线完成，自动识别中文、英文、日文，可以在三种语言之间切换目标语言。
  - 要看词典释义，可以一键把文字交给手机上装的词典或翻译应用（欧路、有道、Google 翻译等）。

### PDF
- 「文字」面板显示当前页的文字，可以自由选择、复制、查词或翻译整页。
- 全文搜索：结果带页码和上下文，点一下就跳过去。
- 朗读：从当前页开始读，自动翻页，画面跟着朗读位置走。
- 扫描版 PDF 没有文字层，以上功能会自动停用并给出说明，照常按图片阅读。

### 漫画
- 看图阅读器，支持双指缩放、双击放大、页码滑块和书签。
- **从右往左阅读**：设置里有全局开关，每本书也可以单独设置。
  - 开启后翻页方向、双页顺序、进度条都会反过来。
  - 点按屏幕左右各三分之一处翻页，中间呼出菜单。

### 听书
- 使用系统 TextToSpeech 朗读，语速和音调可调，可以定时关闭。
- 朗读时自动翻页翻章，并高亮当前段落。
- 系统媒体通知、锁屏、耳机和蓝牙按键都能控制。来电或其他应用播放时自动暂停。

### 书架、统计与阅读目标
- 网格或列表视图，可以按最近阅读、最近添加或书名排序，支持搜索。
- 分组和标签：长按多选，批量移动、打标签、删除。
- 阅读统计：累计时长、最近 7 天的柱状图、每本书的阅读时长。
- **阅读目标**：设定每天读多少分钟，当天读够就自动打卡。
  - 显示连续达标天数和历史最长纪录。
  - 可以开启每日提醒：只在当天还没达标时提醒，到点才请求通知权限。
- **桌面小组件**：显示正在读的书，包括封面、书名、作者和进度，点一下就回到上次读到的地方。

### 同步与备份
- **WebDAV 同步**（坚果云、Nextcloud 等），会同步以下内容：
  - 阅读进度、书签、划线与笔记
  - 阅读统计和设置
  - 书架分组和标签
- 同步规则：
  - 每条记录按修改时间合并，后改的一方生效，删除也会同步。
  - 可以设置打开应用、退出阅读时自动同步，也可以选择上传书籍文件。
  - WebDAV 密码用 Android Keystore 加密保存。
- **本地备份**：导出为一个 JSON 文件，恢复时按书名、作者、格式匹配书架上的书。旧版本导出的备份可以直接恢复。

### 外观
- 按 Material 3 规范设计：配色角色、字体层级、圆角和动效都用规范里的设定。
- 配色：Android 12 及以上可以动态取色，也可以从 10 种主题色中挑一种，对比度分标准、中、高三档。
- 深色模式可以跟随系统、固定浅色或固定深色，另有纯黑模式。
- 支持预测性返回手势、全面屏沉浸显示和单色主题图标。

## 下载安装

到 [Releases](https://github.com/Chenfyuan/novel-reader/releases) 下载最新的 `moyue-x.y.z-release.apk`，在手机上安装即可。系统要求 Android 8.0 及以上。

正式版使用同一把签名密钥，新版本可以直接覆盖安装，书架和阅读数据都会保留。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 网络 | WebDAV 同步；首次翻译某种语言时下载离线翻译模型 |
| 通知 | 听书的媒体通知、阅读目标提醒（只在开启提醒时请求） |
| 前台服务（媒体播放） | 锁屏或切到后台时继续朗读 |
| 开机启动 | 重启后恢复每日阅读提醒；没开提醒时什么也不做 |

墨阅不读取存储空间，书籍都通过系统文件选择器导入。应用内没有统计、广告或追踪代码。

## 自己构建

需要 JDK 21、Android SDK Platform 37 和 Build-Tools 37.0.0。

```bash
export ANDROID_HOME=/path/to/android-sdk   # 也可以写在 local.properties 的 sdk.dir 里

./gradlew assembleDebug                     # Debug APK：app/build/outputs/apk/debug/
./gradlew testDebugUnitTest                 # 单元测试与截图测试
./gradlew testDebugUnitTest -Proborazzi.test.record=true   # 重新生成截图
```

### 正式版签名

release 构建会从以下任一位置读取签名配置：
- `keystore.properties`
- 环境变量 `MOYUE_SIGNING_PROPERTIES` 指向的文件
- 环境变量 `MOYUE_KEYSTORE_PATH`、`MOYUE_STORE_PASSWORD`、`MOYUE_KEY_ALIAS`、`MOYUE_KEY_PASSWORD`

都没有配置时，会产出未签名的 release。release 启用了 R8 混淆和资源压缩。

APK 只打包 arm64-v8a 和 armeabi-v7a 两种架构，以控制体积（离线翻译带有原生库）。

### 持续集成

每次推送到 main、提交 Pull Request 或推送标签时，GitHub Actions 会构建 Debug APK 并运行测试，产物可以在对应的 Actions 页面下载。正式版 APK 在本地签名后手动发布到 Releases。

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Kotlin / AGP / Gradle | 2.4.21 / 9.4.1 / 9.8.1 |
| Compose BOM（Material 3） | 2026.09.00 |
| Room / DataStore | 2.8.5 / 1.2.1 |
| Glance（桌面小组件） | 1.1.1 |
| media3（听书媒体会话） | 1.11.1 |
| ML Kit Translate / Language ID | 17.0.3 / 17.0.6 |
| pdfbox-android（PDF 文字层） | 2.0.27.0 |
| jsoup / MaterialKolor | 1.21.2 / 5.0.1 |
| Roborazzi / Robolectric（截图测试） | 1.76.0 / 4.17 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

## 已知限制

- **离线翻译**：每种语言第一次使用时，要从 Google 服务器下载约 30 MB 的模型。在无法访问 Google 服务的网络或设备上，可能下载不了，这时可以改用「用其他应用查询」。应用没有内置词典，释义靠手机上装的词典应用。
- **PDF 选字**：要在「文字」面板里选，不能直接在页面图像上拖选。多栏、表格等复杂排版提取出来的文字顺序可能不理想。
- **扫描版 PDF**：没有文字层，所以不能选择、搜索或朗读，也没做文字识别（OCR）。
- **EPUB 样式**：只支持常用的 CSS 子集，浮动、自定义字体、复杂选择器、SVG 图片不支持。
- **MOBI**：不支持 HUFF/CDIC 压缩的文件，Kindle 内部链接不能跳转。
- **仿真翻页**：只在单页模式下可用，双页并排时用平移翻页。
- **同步**：不会把书籍文件下载到新设备，要先在新设备上导入同一本书。同一条记录在两台设备上都改过时，以较晚的修改为准，所以依赖各设备的时钟大致准确。
- **听书**：效果取决于手机上的 TTS 引擎和中文语音包。
- **提醒时间**：为了不申请精确闹钟权限，每日提醒可能比设定时间晚几分钟。
- **桌面小组件**：在离开应用时刷新，不会在后台定时更新。

## 更新日志

**2.0.0**
- 新功能：
  - 划词翻译与查词：离线翻译中、英、日文，可以转到手机上的词典应用查释义。
  - PDF 文字层：选择文字、全文搜索、朗读，扫描版自动停用这些功能。
  - 桌面小组件「继续阅读」。
  - 阅读目标与连续打卡，可开启每日提醒。目标设置随同步和备份走，打卡天数由每日阅读时长算出。
- 改进：仿真翻页改为圆柱卷曲的曲面效果。

**1.9.0**
- WebDAV 同步加入书架分组和标签（包括分组顺序、重命名和删除），兼容旧版本的同步数据和备份文件。
- 漫画支持从右往左阅读：可全局设置，也可单本设置；点按区域和双页顺序会随方向调整。

**1.8.0**：正式签名、R8 混淆与资源压缩；修复 WebDAV 缺少网络权限的问题。

**1.7.x**：新增 MOBI/AZW3、PDF、CBZ 支持和平板、折叠屏适配；后来取消了 DRM 预检，文件直接打开。

**1.6**：仿真翻页、书架分组与标签、WebDAV 同步。

**1.5**：OpenCC 繁体转换、上下滚动阅读、听书接入系统媒体控制、EPUB 链接跳转、跨页选择。

**1.4**：EPUB 富文本排版、大文件流式导入与分章缓存、划线与笔记。

**1.1 – 1.3**：书签、搜索、听书、阅读统计、设置页、动态取色、字体导入、备份恢复，以及 Material 3 规范打磨。

## 许可证

[MIT License](LICENSE)
