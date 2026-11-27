# 阅读S · 档案式立体书架（sssdex/legado-archive-shelf）

本仓库是 **[refgd/legado](https://github.com/refgd/legado)（阅读R）** 的一个改版（独立仓库，不是 GitHub fork），
在书架页新增了「档案式书架」：上方是 3D 立体书架，下方是 `SHELF / CATALOGUE` 档案式书目，分组收进左侧抽屉。
设置里留了开关，关掉就完全回到改动前的原版书架，两者共用同一套书籍数据。

书架这套观感来自 [Wenku8_Flutter](https://github.com/P5UEDGK/Wenku8_Flutter)（[P5UEDGK](https://github.com/P5UEDGK)）的立体书架，
几何与绘制顺序照 [AzukiPann/AzukiReader](https://github.com/AzukiPann/AzukiReader)（[AzukiPann](https://github.com/AzukiPann)）的 MD3 / Compose 实现移植，
致谢与「这个改版到底改了多少」见第七节。


- 基线：`refgd/legado` 分支 `own`，commit `accef33`
- 分支：`archive-shelf`
- 包名：`com.legado.archive`（独立共存，不覆盖官方阅读R / 阅读）
- 桌面名：**阅读S**
- 当前版本：`3.26.112723`（versionCode 10010，在 `3.26.112523` 基础上）
- 安装包：见 [Releases](../../releases)（APK 不进仓库）

---

## 一、这个版本做了什么

### 1. 档案式书架（主体功能）

| 部分 | 说明 |
| --- | --- |
| 立体书架 | 平行投影（0.66 / -0.30 斜投影）复刻 AzukiReader 的几何：书脊挨着书脊，焦点书被「抽出」并上浮、封面朝右上铺开，远近书脊下坠成一条导轨。封面 107dp、书脊 14dp，整条书架约 365dp 高 |
| 循环滑动 | 横滑到头会无缝接回另一端，同方向一直滑不会停 |
| 触感 | 每滑过一本轻振一次，`Step=15ms`、振幅 150（约 0.59） |
| 内置宋体 | 书名/作者/书脊竖排字全部用内置的**思源宋体 CN Medium**（`assets/font/archive_serif.ttf`，SIL OFL 1.1）：下方书名 10.6sp、作者 8.4sp、书脊 8.2sp。Android 自带 `Typeface.SERIF` 对中文会回落黑体，所以必须自带字库 |
| 不透明纸面 | 主题背景色可能半透明（壁纸主题），纸面/书脊/顶面会先合成到「浅色白纸 / 深色黑纸」上再绘制，壁纸不会透进书页、相邻书的字也不会互相叠印 |
| 档案编号 | 封面上的 `NO.001` 与下方 `档案 / 001` 只在**当前抽出的那本书**上显示（缩进 26dp，非焦点书被右邻书脊压住） |
| 数量 1–500 | 立体书架展示前 N 本（默认 12），封面按焦点窗口按需加载 + LRU 缓存，N 调到 500 也不会把内存吃满；书目列表始终是全部书 |
| 书目 | `SHELF / CATALOGUE` 标题 + 排序按钮，56×76 封面缩略 + `档案 / 001` + 书名 + 作者 + 阅读进度 |
| 分组抽屉 | 点左上角分组图标（或标题里的「全部 ▾」）从左侧滑出，按 `01 全部 / 02 分组名 …` 列出，底部「分组管理」。抽屉面板是不透明实心纸色 |
| 可折叠 | 「收起书架」只留书目，按钮变「展开书架」 |
| 随主题 | 纸色、墨色、分隔线、网格底纹全部取当前主题色，夜间模式自动跟随 |

### 2. 默认图标与「切换图标」

- 默认桌面图标换成深灰底 + 白色打开的书（自适应图标：`@color/launcher_s_background` + `@drawable/ic_launcher_s_foreground`，前景已缩进安全区，圆/方遮罩都不会裁到书角；同时生成了 5 个密度的传统 `ic_launcher.png`）。
- 原有的「设置 → 界面设置 → 切换图标」保留，并把新图标作为第 8 项 `icon8` 加进列表（`Launcher8` activity + `mipmap-anydpi-v26/launcher8.xml`）。
- `launcherIcon` 的默认值仍是 `ic_launcher`，即「回到默认图标」，而默认图标本身就是新图。

### 3. 应用名

`app_name_archive` 与基础 `app_name` 都改为 **阅读S**（桌面、应用信息、最近任务、通知栏一致）。

### 4. 第六版的三个修复

1. **分组抽屉在浅色+壁纸主题下透明**：抽屉面板原来直接用 `ThemeStore.backgroundColor()`（带 alpha），现在与书架纸面共用 `ArchivePalette.paper(context)`，合成到白/黑底后再上色，浅色/深色都是实心。
2. **默认图标**：见上。
3. **应用名**：见上。

### 5. 3.26.112723（在 3.26.112523 基础上）

只改一处：`app/src/main/java/io/legado/app/model/webBook/WebBook.kt` 的 `getChapterListAwait`。

- 并发去重表 `chapterListJobs` 原来只在发起方 await 正常返回后才 `remove`；发起方被取消（退出详情页、关书源调试页、
  后台更新目录被打断）时异常抛出，清理被跳过，表里留下一个已取消的任务。之后同一本书每次加载目录都会 await 这个死任务，
  立刻抛 `Job was cancelled`，同一进程内永不恢复。
- 现在 `invokeOnCompletion` 时无论成功/失败/取消都摘掉；复用到的任务若已取消，清表并重试一次。
- 上一版（第七版）的全应用格子背景、封面飞行、`com.legado.archiveshelf` 包名**不再发布**，代码仍在 git 历史里（commit `fc5a1dd`）。

---

## 二、开关在哪

**我的 → 界面设置 → 档案式书架**

- 默认开启；关掉立即回到原版书架（列表/网格、分组标签栏、原菜单全部不动）。
- 同一页的 **书架数量** 可调 1–500，默认 12，改完返回书架立即生效。
- 写入 `archiveShelfEnabled` / `archiveShelfVisibleCount`，不影响任何既有书架设置。

---

## 三、构建方式（本机已验证）

```bash
# JDK 17 + Gradle 8.14.4（AGP 8.13.2 / Kotlin 2.3.10）
export JAVA_HOME=D:/android-build/jdk
export GRADLE_USER_HOME=D:/gradle-home        # 必须与工程同盘，见下面第 1 个坑
cd legado

# 调试包（com.legado.archive.debug）
gradle :app:assembleDebug

# 签名 release（com.legado.archive）
gradle :app:assembleRelease -PskipVitalLint=true \
  -PRELEASE_STORE_FILE=/path/to/legado-archive.jks \
  -PRELEASE_STORE_PASSWORD=legado123 \
  -PRELEASE_KEY_ALIAS=legado \
  -PRELEASE_KEY_PASSWORD=legado123
# 产物：app/build/outputs/apk/app/release/legado_app_<versionName>_<versionCode>.apk
```

`local.properties` 里 `sdk.dir=<SDK 路径>`，用正斜杠。

两个换机器必踩的坑：

1. **KSP2 跨盘崩溃**（`this and base files have different roots`）：Glide 的 `GlideIndexer` 会让 KSP2 对
   `C:\Users\...\.gradle` 与工程盘做 `relativeTo`，Windows 跨盘无解。把 `GRADLE_USER_HOME` 指到与工程同盘即可。
2. **工程路径不能含中文**：AGP 会直接拒绝，例如 `D:\zcode文件\...` 不行，放到 `D:\legado-work\legado` 这类纯 ASCII 路径。

版本号默认 `3.<yy.MMddHH>`、versionCode `10000 + git 提交数`，可用 `-PVERSION_NAME` / `-PVERSION_CODE` 覆盖。

---

## 四、签名

- 自签 keystore（alias `legado`，口令 `legado123`）。**只能覆盖安装本改版的包**，不能覆盖官方阅读R（签名不同）。
- 升级本改版请沿用同一 keystore，否则会签名冲突。

---

## 五、字体授权

`app/src/main/assets/font/archive_serif.ttf` 是 **Source Han Serif CN Medium**（思源宋体），
采用 **SIL Open Font License 1.1**，允许随应用一起分发；授权原文见同目录 `archive_serif_OFL.txt`。
如需瘦身，可用 `fonttools subset` 裁到 GBK/GB2312（生僻字会回落系统中文字体）。

---

## 六、已知限制

- 自左向右绘制时，右邻书脊会压住焦点封面右边缘约 20dp（AzukiReader 原版也如此）。
- 书目行只有阅读进度，没有未读角标 / 书籍锁；行内书名固定 15sp，不跟随书架标题缩放。
- 档案页不跟随原书架的「列表/网格/边距/书名显示方式」设置；关掉开关后这些设置照常生效。
- 切换开关会重建书架页，列表会回到顶部。
- 抽屉只列「有书的分组」。
- 左边缘 20dp 内横滑会被 DrawerLayout 当作开抽屉手势。
- 循环书架在数量很小时会看到重复书影（可视窗口约 30 lane，12 本时左右远端各重复一次），是循环的固有表现。
- 内置宋体让 APK 从 32.0 MB 涨到约 39.6 MB。

---

## 七、与上游的关系、定位与致谢

**范围**

- 只动书架首页（新增 `ui/main/bookshelf/archive/` 与对应布局/字符串/设置项），外加应用名与默认图标；
  阅读器、书源、订阅、发现页、备份格式、数据库结构（Room 版本不变）均未改。
- 上游原版功能与本改版可以同时装在手机上，数据各自独立（`com.legado.archive` vs `io.legado.app`）。
- 未向上游提 PR；本分支仅用于发布改版。

**仓库与同步上游**

- 本仓库是独立仓库 `sssdex/legado-archive-shelf`，**不是** GitHub fork（fork 不会被 GitHub 默认搜索索引，所以当初从 fork 迁了出来）。
- 同步上游：`git remote add upstream https://github.com/refgd/legado.git`（只需一次），之后 `git fetch upstream && git merge upstream/own`（上游基线分支是 `own`）。

**定位**

- 这一版**不是** [AzukiReader](https://github.com/AzukiPann/AzukiReader) 那样的大规模改动。AzukiReader 是基于 MD3 重做的
  整包、面向作者自己的使用需求开发；这里只是把它的立体书架搬进阅读R 的书架页，其余保持阅读R 原样。
- 出发点很简单：喜欢阅读R 的风格，功能也够用，不想为了一个书架换掉整个 App。
  所以它适合「想要 Wenku8_Flutter 那套立体书架、又已经习惯阅读R」的人。
- **维护**：受限于个人能力，未来很可能不再维护，阅读R 上游改动带来的适配问题不一定会跟。
  代码、patch 和思路都在仓库里，欢迎自行修改与打包。

**致谢**

| 项目 | 作者 | 这里用到了什么 |
| --- | --- | --- |
| [refgd/legado](https://github.com/refgd/legado)（阅读R） | [refgd](https://github.com/refgd) / Rimchars | 整个 App 的基线；本改版只在它的书架页上做加法 |
| [Wenku8_Flutter](https://github.com/P5UEDGK/Wenku8_Flutter) | [P5UEDGK](https://github.com/P5UEDGK) | 立体书架这套设计的源头：书脊并排、焦点书抽出上浮、封面朝右上铺开、书目与分组形态 |
| [AzukiReader](https://github.com/AzukiPann/AzukiReader) | [AzukiPann](https://github.com/AzukiPann) | 把 Wenku8 的立体书架做成 MD3 / Compose 版本；本改版的投影常数（0.66 / -0.30）、绘制顺序（自左向右）、循环与抽出权重均照它移植到 Views + Canvas |

感谢三位作者开源。这个改版若有做得不好的地方，是移植的问题，不是这些上游项目的问题。

