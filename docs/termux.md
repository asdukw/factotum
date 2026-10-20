# 手机上的 Linux：Termux 桥 + `run_command`

**什么时候读**：改 `tools/termux/`、`agent/TermuxBridgeStore|Launcher`、设置页
「执行层 → Linux 环境」、插件 `run_command` 之前。

agent 能在手机的**文件系统**上干活：Termux 里跑 proot Debian，桥把「执行一条命令、拿回
stdout / stderr / 退出码」暴露在 127.0.0.1 上，插件的 `run_command` 打过去。装配见
`tools/termux/setup.sh`（幂等，跑一次）。**这条路不经过无障碍** —— 它改的是文件，不是屏幕。

## 1. 令牌与授权

- **token 由 Tappet 生成，不是用户抄的**。Tappet 读不到 Termux 的私有目录（跨 uid），
  但反过来可以 —— 经 Termux 的 `RUN_COMMAND` 把 token 送过去（`TermuxBridgeLauncher`）。
  token 进 SecretVault、地址进 prefs；**故意不进 `runtime-config.json`**（那个文件是明文），
  代价是改 token 要重启 App（与换 API key 同一条规矩）。
- **`RUN_COMMAND` 是 `dangerous` 权限**，必须用户在弹窗点一次，ColorOS 拦 `pm grant`。
  **`adb install -r` 会把它清掉**，但 **`adb install -r -g` 会带上** —— 开发期重装一律加 `-g`，
  省掉每次重新授权。

## 2. 送进 Termux 的脚本：三条硬约束

每条都是实测踩出来的，单测钉住：

1. **不许用 `pkill -f`** —— 脚本是被 `bash -lc <脚本文本>` 拉起的，**脚本文本就在 argv 里**，
   正文里写的任何模式都会匹配到脚本自己，pkill 把脚本自己杀掉（token 写完了、后面的行一行没跑、
   日志也没被碰过，外面只看到「桥没起来」——这个坑查了三轮）。要杀旧桥就扫 `/proc` 比命令行，
   并跳过自己与父进程。
2. **自己 export `HOME` / `PATH`** —— RUN_COMMAND 是非交互 shell，环境里连 `PREFIX` 都是空的，
   不摆正的话 `python` 根本找不到。
3. **输出重定向到 `~/bridge.log`** —— RUN_COMMAND 的 stdout/stderr 没人接管，任何 traceback
   都会消失。

**验证这类脚本必须复现真实调用形态**：把脚本放在文件里跑（`bash x.sh`）脚本文本不在 argv 中，
pkill 自杀这类问题**复现不出来**。要么按 `bash -lc <文本> name token` 跑，要么直接在真机上验。

## 3. 生命周期

- **ColorOS 会冻结、也会直接杀掉后台的 Termux**，桥跟着一起没。冻结表现为「端口还在 `LISTEN`
  但连 SYN 都不回」（连接超时）；被杀表现为「连接被拒 + `ServiceRecord` 的 `app=null`」。
  前者要在系统设置里放开 Termux 的后台运行；后者要靠 startService 冷启动 Termux，
  所以轮询窗口给到 45 秒（实测 `am start` 拉起它就要 ~6 秒）。`TermuxBridgeLauncher` 把
  「连接超时」与「没人监听」分成 `FROZEN` / `FAILED` 两种状态 —— 前者重试无用，后者才是
  配置问题。
- **换掉旧桥**：先按 pidfile 杀（`bridge.py` 启动时写），再扫 `/proc` 比命令行兜底
  （早于 pidfile 那版启动的桥没有 pidfile）——**不要用 `pkill -f`**，理由见上。
- **`/health` 不鉴权、`/ping` 鉴权**：前者回答「端口上有没有人」，后者回答「是不是我这把
  token」。桥在跑但 token 不对时**必须重启它**，否则每条命令都 401，现象看起来像「桥坏了」。

## 4. 工具面与能力边界

- **`run_command` 只在注入了 `TAPPET_TERMUX_URL` / `_TOKEN` 时才注册**：没配这套的用户不该在
  工具表里看到一个必然失败的工具（每轮占 schema 预算，还会把模型引向死路）。
- **桥是无状态的**：每条命令一次独立的 `proot-distro login`（实测 200–430ms），cwd / 环境变量 /
  后台进程都不保留。要跑常驻服务得自己 `nohup ... > 日志 2>&1 &`（v1 的长驻会话还没做）。
- **没有 docker / systemd / iptables**（proot 不是真内核）。**AVF 那条路在这台机器上走不通**：
  OnePlus 15T 的 gunyah 只支持 protected VM，而 Android 16 的「终端」要 non-protected VM，
  启动即报 `Non-protected VMs are not supported on this device`。

## 5. 改动入口与验证

涉及四处：`tools/termux/`（桥 + 装配）、`agent/TermuxBridgeStore|Launcher`、设置页
「执行层 → Linux 环境」、插件 `run_command`（先在插件仓库提交再更新 submodule 指针）。

```powershell
# 无 UI 验证启动器（授权弹窗那一步仍要用户点）
adb shell am broadcast -n com.tappet.app/.node.NodeDebugReceiver `
  -a com.tappet.app.debug.NODE --es op TERMUX
# 结果看 logcat 的 TappetNode: termux bridge: <状态>
```

## 6. 排障

| 现象 | 原因 / 处理 |
|---|---|
| 设置页「Linux 环境」一直停在「正在启动…」 | Termux 被 ColorOS 冻了（端口在但连不上）→ 把 Termux 拉到前台，并在系统设置里放开它的后台运行 |
| 启动器报 `NO_PERMISSION`（明明授权过） | `adb install -r` 清掉了 RUN_COMMAND 授权 → **重装用 `adb install -r -g`**，或重新点一次「授权并启用」 |
| 桥的启动脚本「跑了但什么也没发生」（token 写了、日志没动、python 没起） | 多半是脚本被 `pkill -f` 自杀了（脚本文本在 argv 里）→ 别用 `pkill -f`，扫 `/proc` 并跳过自己 |
| 脚本里 `python: command not found` 之类看不见 | RUN_COMMAND 的 stdout/stderr 没人接管 → 输出重定向到 `~/bridge.log` 再看 |
| `run_command` 不出现在工具表里 | 没生成 token（未启用）或 env 没注入 → 设置页点「启用」后**重启 App** |
| 桥在跑但每条命令都 401 | 是别的 token 起的桥（比如手动跑过 setup.sh）→ 点「重新连接」，让它按 pidfile 换掉 |
| `run_command` 报连不上 | Termux 里的桥没在跑 → 打开 Termux 执行 `python ~/bridge.py`；日志在 `~/bridge.log` |
