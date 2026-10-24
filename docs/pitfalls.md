# 常见坑速查（完整版）

按主题分组。**提供商 / 更新 / Termux / 发版** 的坑在各自文档里（`providers.md`、
`app-updater.md`、`termux.md`、`releasing.md`），这里不重复。

## 构建 / 装配 / 环境

| 现象 | 原因 / 处理 |
|---|---|
| 全新安装 Gradle 挂 5 分钟后失败 | ColorOS 拦 `adb install` 通道 → 手动 `adb install -r` |
| `nativeOpen` UnsatisfiedLinkError | 忘加载 SQLCipher → `TappetDatabaseProvider` 显式 `loadLibrary` |
| `dlopen failed: libc++_shared.so not found` | 重跑 `tools/fetch-libnode.ps1`；确认文件**确实落在** `jniLibs/<abi>/`（脚本有自检，别只看它打印成功） |
| APK 里有 libnode.so 但没有 libc++_shared.so | AGP 不会自动打包 STL，jniLibs 是唯一来源 → 同上；`assemble.yml` 会拦住 |
| `Task '.abis=x86_64' not found` | PowerShell 把 `-Ptappet.abis=x86_64` 拆成两个参数 → 加引号：`"-Ptappet.abis=x86_64"` |
| 装到手机上 `dlopen libc++_shared.so` 失败（刚跑过模拟器） | jniLibs 停在 x86_64 → `tools/fetch-libnode.ps1` 切回 arm64 再出包 |
| 模拟器上 App 起不来 / 缺 libnode | 只装配了 arm64 → `fetch-libnode.ps1 -Abi x86_64` + `-Ptappet.abis=x86_64` |
| Gradle 代理不生效 | 行内 `-D` 被 gradlew.bat 拆坏 → 放 `GRADLE_OPTS` 环境变量 |
| 内嵌 Node 编译失败 `unknown type name 'concept'` | Node 24 头文件要 C++20 → `CMAKE_CXX_STANDARD 20` |
| 装完 App 起不来，报 `ERR_MODULE_NOT_FOUND: Cannot find package '@deepseek-ai/cordis'` | **装配脚本是用 Windows PowerShell 5.1 跑的**：它的 .NET Framework 把 zip 条目名写成 `\`，设备侧解不出目录树（解出来是 `node_modules\.bin\cordis` 这种扁平文件名）→ 用 **`pwsh` 7** 重跑 `bundle-dsh.ps1`（脚本末尾现在会自检并 throw） |
| `checkDebugAarMetadata` 报某依赖 `requires ... compileSdk N` | 该依赖要求的最低 compileSdk 高于本项目（`app/build.gradle.kts` 的 `compileSdk`）→ **不能单独升它**，得连 AGP + compileSdk 一起迁移：先升 AGP 到支持 N 的版本（日志里的 `maximum recommended compile SDK version for Android Gradle plugin X` 就是当前上限），再改 compileSdk，并同步 CI 里 `sdkmanager "platforms;android-N…"`。Dependabot 单独升这类依赖的 PR 会在这一步红 |

## 运行时 / dsh

| 现象 | 原因 / 处理 |
|---|---|
| 任务结束 App 崩溃（FORTIFY/SIGABRT） | 内嵌 Node 调了 `process.exit()`（含 SDK 的 `shutdown`）→ 只记录、不退出 |
| JSON-RPC 偶发不响应 | stdout 混入了日志 → phone profile 不得挂 stdout logger；诊断一律 stderr |
| dsh 启动报 `node-addon-require-builtin` | 用了 `boot()`/loader → 改用 `launcher.mjs` |
| dsh 启动报 `bad indentation of a mapping entry`，**App 直接崩** | profile 里 `!!js` 表达式写了对象字面量（YAML 的 plain scalar 不能含 `": "`）→ 整个表达式加双引号；`check-profile.mjs` 提前拦 |
| dsh 会话写文件报 flock / link 错误 | 未打补丁 → 跑 `patch-runtime.mjs`（flock 放行、jsonl 改 rename） |
| dsh 报 `session ... already exists`（-32603） | 进程重启后复用磁盘会话 id；dsh SDK 只有 create 无 resume → `NodeRuntime` 的 runtimeTag 后缀别去掉 |
| 助手回复整段蹦出来、没有逐字效果 | 运行时补丁 4 没生效（stdout 上没有 `session.stream` 帧）→ 重跑 `tools/bundle-dsh.ps1` 并重装 APK；Kotlin 侧另有 50ms 合并，纯聊天不涉及无障碍 |
| 装了 `rememberViewModelStoreNavEntryDecorator()` 后启动即崩 | Nav3 1.0.1 要求配套的 `SavedStateNavEntryDecorator`（该版本没有）→ 别装它；VM 用 Activity 作用域 + `viewModel(key = ...)`（提供商详情即如此，按 providerId 区分） |
| 输入框快速打字丢字符 | 每次击键同步 `commit()` 落盘（主线程 fsync）→ 频繁写的字段用 `apply()`，只有需要「写进去没有」答案的凭据才用 `commit` |
| 点了按钮看不到任何反馈 | 错误文案渲染在页面底部、落在屏幕外 → 反馈贴着触发点放 |
| 想知道「dsh 官方预置有什么、我们缺什么」 | 用 launcher 同一套 API 把组合打出来比：`loadProfileDirectory` + `readProfilePatches` + `composeEntries`，然后对比 `preset-standard` 与 `preset-phone` 的 `config.plugins`（现成脚本可照 `docs/memory.md` §3 写，10 行） |

## 真机 / adb / 广播

| 现象 | 原因 / 处理 |
|---|---|
| 装完读屏失效 / 启用无障碍又变 null | `install -r` 摘掉无障碍服务 → 等进程起来后 `settings put secure` 重新启用 |
| 广播 TASK 后 App 被 ANR 杀 | 别用 `goAsync` 等任务完成（Broadcast Timeout 60s）→ 广播立即返回，任务异步跑 |
| 广播 START/TASK 没反应 | ColorOS defer 后台广播 → 先 `monkey` 拉起 App 再广播 |
| 广播后卡在 `debug task start`、再没有下文 | **cached app freezer 把进程冻住了**（Android 11+，约 10 秒）。首次启动要解压 dsh 运行时（模拟器实测 **122 秒**），期间没有前台服务就必冻 → 先 `am start` 把 App 拉到前台再广播。广播**不在前台的 FGS 白名单里**，`startForegroundService` 会抛 `ForegroundServiceStartNotAllowedException`，所以接收器里那一步是尽力而为（AlarmManager 那条路有白名单，不受影响） |
| `adb shell am broadcast --es task "a b"` 丢参数 | 整条命令再包一层引号：`adb shell "am broadcast ... 'a b'"` |
| `op ASR` / `op VAD` 广播了但日志里什么都没有 | Git Bash 把设备路径 `/data/...` 翻成了 `Files/Git/data/...`（MSYS 路径转换）→ 前面加 `MSYS_NO_PATHCONV=1`；另见「先 monkey 再广播」那条 |
| 拉 dsh 会话文件解压失败 | `adb shell cat` 会把 `\n` 翻成 `\r\n` → 用 `adb exec-out run-as com.tappet.app cat ...`；会话是一帧一条记录，需按 RFC 8878 手工切帧后逐帧解 |

## 执行层 / 视觉 / 文本输入

| 现象 | 原因 / 处理 |
|---|---|
| 读屏没有一个可读节点（微信） | 该 App 对无障碍隐藏控件树（只在「触摸探索」为真时启动的进程里暴露，而任务期不再开探索）→ 走视觉路径，由 `EMPTY_TREE_HINT` 引导 |
| 用户开着 TalkBack 时点击/滑动全部无效 | 全局触摸探索恒为真，探索模式吞掉 `dispatchGesture` 的手势 → 走 `performAction` 语义动作（服务里已自动切换，这些分支不能删） |
| 任务期间用户点屏幕变成「双击才生效」 | 触摸探索被开着才会 → 任务路径不再自动开；只有 debug 广播手动开了才可能出现 |
| 按图点击总是偏左偏上约 12.6% | 截图缩到 2048 长边、`tap_point` 收屏幕像素，两套坐标系没换算 → 模型侧一律用截图的图像像素（`tap_target`），换算在 `ScreenFrame.toScreen` |
| `tap_target` 报 `STALE_SCREENSHOT` | 截图是有状态的：界面变了旧截图作废 → 重新 `capture_screenshot` 再给坐标（不是 bug） |
| `tap_target` 报 `OUT_OF_SCREENSHOT` | bbox 越出图像范围，**故意不 clamp** → 让模型重给坐标，别改成夹取 |
| `tap_target` 回「界面似乎没有变化」 | 8×8 指纹的启发式：小面积变化（勾选框、行高亮）它看不出来 → 只能读成「可能没点中」，不是「点击失败」 |
| 长视觉任务请求报 `IMAGE_OFFLOAD_REQUIRED` | 请求图像预算 20MiB 吃穿 → profile 里 `image-offload` 已启用（丢最老的图重试）；它**永久丢帧**，模型之后拿不到那张图 |
| 树不可读时 `type_text` 失败 | 控件树不可读，文本注入通道也没拿到连接 → **先看码**：`text_no_channel` = 输入框没聚焦（先 `tap_target` 点它一下），`text_flag_missing` = 升级后没重新启用过无障碍，`text_unsupported_os` = 设备在 Android 13 以下。见 `docs/android-accessibility.md` §10 |
| 截图报 `Could not load the "sharp" module` | 生效的是 dsh 自带的 `attachment-local`（依赖 sharp，无 Android 产物）→ profile 里禁用它、挂 `dsh-android-attachments` |
| 模型侧 `tools: []`、只能对着 persona 猜工具名 | 工具 schema 编译失败 → `defineTool` 抛错会**整个插件的工具全丢**；用插件仓库 `npm test` 里的 `real-schema.mjs` 在 PC 侧拦住 |
| `set_alarm` 报 SecurityException | AOSP / Pixel 的 DeskClock 把 `HandleSetApiCalls` 声明成 `android:permission=com.android.alarm.permission.SET_ALARM`（protectionLevel `normal`）→ 清单里必须声明这条权限；厂商时钟不校验它，所以真机上不声明也「碰巧」能用。`SystemIntentsManifestTest` 钉住 |
| 新建日程时 logcat 出现 `Permission Denial: opening provider ...CalendarProvider2` | `ACTION_INSERT` 只给 `data` 不给 type 时，AMS 会拿**我们进程的身份**去问 provider 要 MIME（我们没有 `READ_CALENDAR`）→ intent 里显式 `type = vnd.android.cursor.dir/event` 就不查了 |

## 连接器 / 联网搜索

| 现象 | 原因 / 处理 |
|---|---|
| 授权报 `client_secret is missing` | Google 文档把这项标「Optional」而令牌端点实际要求 → 必须发（桌面应用类型与 Android/iOS 不同） |
| 同意页写着「读取、撰写和发送你的邮件」 | 正常：申请的是 `gmail.modify`（一次要齐，避免以后重授权），但工具面目前只有只读三个 |
| 没开代理时邮件工具报「未授权」 | 已经修了：令牌请求的网络失败现在报 `NETWORK`（连不上 Google 的授权服务），不再被 `.orEmpty()` 吞成空响应、当成授权失效。见 `GmailAuth.postForm` 的注释 |
| 邮件工具回「还没有连接 Gmail」 | 连接器未授权，或 refresh token 已失效被清 → 设置 → 连接器 → Gmail 里重新连接 |
| 邮件工具回「还没有配置 Gmail」 | 没填客户端 ID → 填 Google Cloud 的 OAuth 客户端 ID（类型必须是**桌面应用**）；步骤见 `docs/email-connector.md` §4.2 |
| 授权报 `redirect_uri_mismatch` | OAuth 客户端类型不是「桌面应用」（回环地址只对它开放）→ 重建凭据 |
| 浏览器点完同意后 App 没反应 | 授权全流程真机已跑通；遇到时先看 stderr 里回环监听器有没有收到请求（部分浏览器拦 `127.0.0.1`）。另：授权期间 App 进程被杀就得重来（最长 10 分钟持有 socket） |
| 连上后过一阵又变成未连接 | refresh token 失效（改密码 / 用户在 Google 账号页撤销 / 测试模式下超过 7 天）→ 重连；`invalid_grant` 时插件会主动清凭据 |
| 邮件工具整个不见了 / dsh 起不来 | 插件名三处不一致（profile 的 insert / `bundle-dsh.ps1` 的 Package / 插件 `package.json`）→ 有单测钉住；改完重跑 `bundle-dsh.ps1` |
| 想确认邮件正文没进日志 | `adb logcat -s TappetConnector TappetAudit` —— 只应有动作名与失败码，不该有主题 / 发件人 / 正文 |
| `web_search` 回 `WEB_PROVIDER_UNAVAILABLE` | 当前提供商没有搜索端点（`LlmPreset.Endpoint.search == null`）→ 换 OpenCode Go，或在 `Endpoint.search` 里补一个**实测过**的 |
| `web_search` 报 400 `MissingSessionID` | zen 的会话头没补上 → 确认 `zen-session-fetch.mjs` 进了运行时（重跑 `bundle-dsh.ps1`；它和 `launcher.mjs` 必须一起拷进 cache） |
| 装完启动报 `Cannot find module .../zen-session-fetch.mjs` | 同上一类：launcher 的兄弟模块漏拷 → 重跑 `tools/bundle-dsh.ps1` |
| 搜索回了答案但一个来源都没有 | **真搜门**：模型收下了 `web_search` 但一次都没搜（网关不校验）→ 换实测会搜的模型（zen 上是 claude 系 / gpt-6-luna / grok-4.x） |
| `web_search` 报 404 且路径里有两个 `/messages` | 搜索基址写成带 `/messages` 的了 —— provider 自己会追加，基址只给到 `/v1` |
| 模型说「没有 web_search 这个工具」 | profile 里的 `tool-web` 行被 `dsh-web-app` 禁用了 → 必须在**宿主层** `- id: tool-web` + `disabled: false`；放进 preset 的 `plugins:` 里不管用 |
| 模型调了 `ask_user_question` 之后整轮卡住、会话一直 running | 没有 answerer 接手（或 answerer 排在 `dsh-api-remotes` 后面被它吃掉 —— 它把请求推进没人消费的 remote 队列）。我们的 answerer 用 `{ prepend: true }` 注册；见 `docs/user-questions.md` §2.2 |

## 语音

| 现象 | 原因 / 处理 |
|---|---|
| 点「朗读」没反应、提示几秒就消失 | 清单缺 `TTS_SERVICE` 查询意图 → Android 11+ 包可见性把 TTS 引擎判成 BLOCKED（`adb logcat \| grep AppsFilter`）；修法见 `docs/voice.md` §3 |
| 朗读报「没有可用的文字转语音引擎」 | 设备上真没有可用引擎（或引擎装了但没被选为默认）→ 系统设置 → 无障碍 → 文字转语音里选一个 |
| 朗读念出「星号星号」「横杠」 | `SpeakableText` 没摊平那种 Markdown 结构 → 在 `SpeakableTextTest` 里补一条再改 |
| 点「停止」要等好几秒才停 | 切段太长（一段读完才检查取消）→ 调小 `TtsEngine.maxChunk` |
| 本地引擎一朗读就闪退，堆栈里只有 sherpa 与协程的帧 | 采样回调写成了 lambda 字面量 → JNI 找不到 `invoke([F)Ljava/lang/Integer;`（见 `docs/voice.md` §4.1）→ 改回匿名对象 |
| 本地引擎读完第一段就停，日志报「朗读中断」 | `destroyIfIdle()` 少了 `if (!released) return` → 每次合成结束都把引擎释放了 |
| 点麦克风提示「语音识别模型正在下载」 | 识别模型（paraformer + 标点，约 144MB）随安装自动下载；没下完就是这样。识别**没有**系统引擎那一档，理由见 `docs/voice.md` §6 |
| 识别结果是一整句没有标点的中文 | 正常：paraformer 本身不产标点（`AsrModel.companions` 里跟着装标点模型）；设置页里没有单独入口，`op MODELS` 看标点装没装。见 `docs/voice.md` §8.1 |
| 标点模型装了但输出没变 | `op PUNCT --es text '...'` 单独验引擎；补出来为空时原样返回 —— 标点是改善，不是前置条件 |
| 点麦克风什么都没说，输入框却多出一句话 | 手写的能量法端点判定把环境噪声送进了模型（静音 5 秒 → 7~11 个字）→ 已换成 silero VAD，噪声根本不成段；**别再回到阈值判定** |
| 说完立刻按停，话没了 | `Vad.flush()` 漏了：段要等 0.8 秒静音才闭合 → 收尾顺序必须是 `stop()` → `finish()` → `release()` |
| 语音输入报「端点检测没起来」 | `assets/silero_vad.onnx` 不在包里 → 跑 `tools/fetch-sherpa.ps1`（629KB，从 `asr-models` release 拉） |
| `op ASR` 报 `Failed to read wave file` | WAV 不在 App 私有目录：`/data/local/tmp` 与 `/sdcard/Android/data/<包名>/` 下 adb 推的文件 App 都读不到 → 用 `adb shell "cat /data/local/tmp/x.wav \| run-as com.tappet.app sh -c 'cat > files/asr/x.wav'"`（别从宿主侧直接 pipe，会截断） |
| `voice/` 下的 VAD 改动想验 | `op VAD --es wav <模型归档里的 test_wavs/*.wav>` —— 打的是段数 / 样本数（**没有正文**），不用真人开口就能验 |
| 想给 `OfflineRecognizerConfig` 加 `decodingMethod` / `blankPenalty` | **别加**：paraformer 只认 `greedy_search`，填别的直接 `SHERPA_ONNX_EXIT(-1)`（App 崩）；`blankPenalty` 只有 transducer 读。要 ITN / 同音字替换用 `ruleFsts` / `ruleFars` / `hr` |
| 下载模型报「连不上 github.com」 | 手机没开 VPN。**不做自动重试**（会让人以为已经在下了）→ 点那一条上的**重试**，从半截文件接着下。建连超时 15 秒、读超时 15 秒；失败原因由 `UpdateHttp.failReason` 翻译，别再退回「异常原文直接显示」 |
| 模型解包报「归档坏了」/ 半截文件被当成下完了 | 先看是不是下载中途断线：`read` 对「读完」与「服务器关了连接」给的都是 -1 → `pump` 里按 `Content-Length` 比对，不够就抛 `TRANSFER_TRUNCATED`（半截文件留着续传） |

## 其他

| 现象 | 原因 / 处理 |
|---|---|
| llama.cpp 在骁龙上报 `HTP0 failed to open session ... 0x80000406` | 没设 `ADSP_LIBRARY_PATH=<lib 目录>`。HTP 的 skel URI 是硬编码的 `file:///libggml-htp-v81.so`，由 DSP 侧 fastrpc 按这个变量搜索；官方 README 与 `run.py` 都没写。见 `docs/on-device-inference.md` |
| `tool-fs-search`（`glob` / `grep`）报 `ripgrep provider failure` | 三个坑叠在一起：① `@vscode/ripgrep` **没有 android 产物**（12 个 optional dep 只有 darwin/win32/linux）→ 我们交叉编译一份并用 `ripgrep-stub` 顶掉它（`.github/workflows/rg.yml` + `tools/fetch-rg.ps1`）；② 该插件 inject `subprocess`，而 `dsh-subprocess-local` 被禁了（它的 koffi/node-pty 是懒加载的，Android 上**可以**开）；③ **必须 `useLegacyPackaging = true`** —— Android 10+ 的 W^X 不许应用执行自己可写目录里的文件，而 AGP 默认把原生库留在 APK 里 mmap，`nativeLibraryDir` 是空的。别被 `run-as …/rg --version` 骗了：那能跑是因为 run-as 的 SELinux 域还是 shell |
| Push 被 `GH013` 拒 | 先分清 PR 分支保护与 push protection；后者说明 GitHub 在提交里看到了密钥 → 按 AGENTS.md §6.1 处理，别硬推 |
