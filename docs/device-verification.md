# 真机安装、验证与调试入口

只讲**正常路径**。按现象排查看 [pitfalls.md](pitfalls.md)「真机 / adb / 广播」一节；
检查要验证什么、执行层内部为什么这么设计，看 [android-accessibility.md](android-accessibility.md)。

## 1. 安装

不要用 Gradle 的自动安装：ColorOS 会先拦下第一次安装，约 5 分钟后报
`Starting 0 tests` + `Failed to install split APK(s)`。

```powershell
.\tools\install-debug.ps1   # assembleDebug → adb install -r -g → 重开无障碍 → 打印版本
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain   # 从 android/ 跑，设备测试
```

- **日常机只装 debug 包。** `run-as`、debug 广播、审计回路都要求可调试 APK；release 包签名不同，
  切回 debug 得先卸载，而卸载会永久丢 SQLCipher 数据库与密钥。release 验证放 CI 或另一台
  可以随便清的设备/模拟器；debug 构建里应用内更新也关着，升级只用 `adb install -r`。
- **新包签名必须和设备上那份一致**，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，唯一出路是卸载
  （数据库 + 密钥一起没）。`local.properties` 里有 release keystore 时 debug 包默认用它签；
  如果设备上那份是默认 debug 密钥签的，就在 `local.properties` 写 `zoneu.debugSigning=debug`。
- `adb install -r` 会摘掉无障碍服务，装完要重新启用。
- ColorOS 挡 `pm grant` / `appops set`；本 App 只需要 `INTERNET`。
- 无障碍只有用户能开。**只在本轮真机动作确实失败之后**提示，判断依据是 `BridgeHealth` 的码，
  不是中文界面文案。

## 2. 并行 worktree

多个 worktree 共享 `applicationId`：另一会话的安装会顶掉你的包（versionCode 相同时），
而 ColorOS 不一定杀进程，整条轨迹可能悄悄来自别人的构建。两个做法：各用独立 debug 身份，
或者同一时间只允许一个会话安装。

共存办法：在 `android/local.properties`（gitignored）写 `zoneu.debugSuffix=.foamy`，
debug 包变成 `com.zoneu.app.foamy`，拥有自己的数据、密钥和无障碍授权；`run-suite` 读同一个文件。

带后缀时，广播要写**完整类名**，无障碍也要为带后缀的包启用：

```
-n com.zoneu.app.foamy/com.zoneu.app.node.NodeDebugReceiver
   com.zoneu.app.foamy/com.zoneu.app.accessibility.ZoneuAccessibilityService
```

相对写法（`.node.X`）会按带后缀的包展开，指向一个不存在的类。

装完先确认设备上跑的是你的代码——把已安装的 `base.apk` 拉下来 grep 一个标记，或看一条你刚改过的
日志格式——并在测试前 `am force-stop`。

## 3. 调试广播（仅 debug 包）

```powershell
# 拉起常驻运行时
adb shell am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op START
# 提交任务；加 --ez logPayload true 才打印助手文本
adb shell "am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op TASK --es task 'task text'"
# 驱动执行层
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action READ_SCREEN
# 视觉路径：先截帧，再按图像像素点击
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action CAPTURE_FRAME
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action TAP_TARGET --es screenshot_id f1 --ei x1 400 --ei y1 1000 --ei x2 543 --ei y2 1047
# 回执通知（重要级 / 声音 / 流体云），不跑任务
adb shell am broadcast -n com.zoneu.app/.accessibility.DeviceActionReceiver -a com.zoneu.app.debug.ACTION --es action NOTICE
# 内嵌 proot 冒烟（先把 App 拉到前台，ColorOS 会推迟后台广播）；--es cmd 可换命令
adb shell "am broadcast -n com.zoneu.app/.node.NodeDebugReceiver -a com.zoneu.app.debug.NODE --es op PROOT --ez logPayload true"
```

参数含空格时把整条 `am broadcast` 包在 `adb shell "..."` 里。

其他入口（`TEXT_CHANNEL`、`IME_TEXT`、`IME_ENTER`、语音 `ASR` / `VAD`）分别见
[android-accessibility.md](android-accessibility.md) §10、[linux-on-phone.md](linux-on-phone.md)、
[voice.md](voice.md) §9。

ColorOS 在启用/重装后会弹一个 3 秒的「保持运行」对话框，并推迟后台广播——先把 App 拉到前台
（`monkey`）再广播。`uiautomator dump` 会把无障碍服务顶掉约 1 秒，自动化期间不要用。

## 4. 日志与审计

| tag | 内容 |
|---|---|
| `ZoneuDevice` | 执行层 |
| `ZoneuNode` | 运行时 |
| `ZoneuAudit` | 审计标记，`key=value`（用 `AUDIT_TAG` 改名） |
| `NODEJS-MOBILE` | Node stderr（stdout 是协议，不是日志） |

任务审计回路：

```bash
node tools/audit/selftest.mjs                  # 离线自检
node tools/audit/run-suite.mjs                 # 跑固定任务集并逐个采集
node tools/audit/collect.mjs --label <name>    # 只采集最新会话
```

`.claude/agents/zoneu-auditor.md` 负责判定。规则（`runs/` 是敏感目录、`suite.json` 的 id 要稳定、
保留 `runs/index.json`、不要写死包名）见 [tools/audit/README.md](../tools/audit/README.md)。
耐久产物放 `tools/` 或 `docs/`，不要放 `tmp/`。

**CI 绿 ≠ 能用。** 执行层、内嵌 Node 启动和真实任务都必须在真机上过一遍。
