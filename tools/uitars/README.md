# tools/uitars — UI-TARS 端侧 GUI 驱动与计时

**这是什么**：把 UI-TARS-2B-SFT 跑在手机上，驱动它执行真实的 GUI 任务并计时。
它不是产品代码，是**测量工具** —— 用于回答「端侧模型执行 GUI 任务到底多快、能不能用」。

**为什么需要「适配」这一层**：UI-TARS 是**动作策略**模型，不是会调工具的对话模型。
它输出自己的动作格式：

```
Thought: ...
Action: click(start_box='(611,899)')
```

而 Tappet 的执行层认的是 `DeviceAction`（`TAP_POINT` / `PRESS` / `TYPE_TEXT` / …）。
两者没有交集，必须翻译 —— 这就是适配的实质，也是 `adapt.mjs` 的全部内容。

## 三个文件

| 文件 | 职责 |
|---|---|
| `adapt.mjs` | **纯函数**：解析 UI-TARS 输出 → 归一化成动作对象 → 映射成执行层动作。可离线验证 |
| `measure.mjs` | 驱动循环：截图 → 请求 `llama-server` → 解析 → 执行 → 计时 |
| `test/adapt.test.mjs` | `adapt.mjs` 的行为测试（CI 的「离线自检」跑） |

## 怎么跑

```powershell
$D = "<设备序列号>"

# 1. 推二进制与模型（约 2 GB）
adb -s $D push .\llama-b11729 /data/local/tmp/
adb -s $D shell "chmod 755 /data/local/tmp/llama-b11729/bin/*"
adb -s $D push .\UI-TARS-2B-SFT-Q4_K_M.gguf /data/local/tmp/gguf/
adb -s $D push .\mmproj-model-q8_0.gguf   /data/local/tmp/gguf/

# 2. 起服务（幂等，脚本里用 pidfile 换旧进程）
adb -s $D push tools/uitars/server.sh /data/local/tmp/uitars/
adb -s $D shell "IMAGE_MAX_TOKENS=1024 sh /data/local/tmp/uitars/server.sh"

# 3. 端口转发 + 探活
adb -s $D forward tcp:18080 tcp:8080
curl http://127.0.0.1:18080/health          # -> {"status":"ok"}

# 4. 确保无障碍开着（执行层动作走它），然后跑任务
node tools/uitars/measure.mjs --serial $D --task "打开计算器应用" --max-steps 5
```

`IMAGE_MAX_TOKENS` 是这条链路上**最重要的旋钮**，见下面的实测。

## 实测（一加 15T，2026-10-19）

同一个任务「打开计算器应用」，只改 `IMAGE_MAX_TOKENS`：

| `IMAGE_MAX_TOKENS` | 每步平均 | 5 步任务 | 行为 |
|---|---|---|---|
| 1024 | **4.5 s** | ~22 s | 完不成 |
| 4096（≈原图） | **24.3 s** | ~97 s | 完不成 |

单步拆解（1024）：视觉+prefill 2.2 s、decode 1.8 s、动作执行 0.1 s。

- **速度随图像 token 数超线性增长**（token 数 4.3×，时间 5.4×）—— 与
  `docs/on-device-inference.md` 第 6 节的观察一致。
- **但降分辨率并没有让行为变好**：4096 下它照样在 `press_home` 上循环、把别的 App 图标
  当成通往计算器的第一步。所以行为差**不是** 1024 这个旋钮造成的。

## 已知的坑

- **实际 checkpoint 的动作格式与官方模板不一致**。官方 `codes/ui_tars/prompt.py` 的
  `MOBILE_USE_DOUBAO` 写的是 `click(point='<point>x y</point>')`，而 UI-TARS-2B-SFT 实际
  输出 `click(start_box='(611,899)')` 或 `click(start_box='<|box_start|>(x,y)<|box_end|>')`。
  `adapt.mjs` 三种都认 —— **只按文档写会在真机上表现为「解析不出动作」**，第一次真机跑就是这么挂的。
- **换服务不要用 `pkill -f llama-server`**：命令文本里的 `llama-server` 会匹配到执行它的那个
  shell 自己，把它杀掉，后面的行一行不跑。用 pidfile（`server.sh` 就是这么做的）。
- **必须设 `ADSP_LIBRARY_PATH`**，否则 HTP 打不开 session（`0x80000406`）。
- 截图要用 `adb exec-out screencap -p`：`adb shell cat` 会把 `\n` 翻成 `\r\n`，二进制就毁了。

## 这份测量**没有**覆盖的

- **不是完整产品链路**：没有经过 dsh 的工具循环，是「端侧模型 + 现有执行层」的直连测速。
- **没有历史截图**：UI-TARS 的训练格式里 history 是**带截图**的，这里只给了文字历史。
  这可能是它多步行为差的一个原因，**未排除**。
- **没有和云端对比**：同任务的云端耗时要用 `tools/audit/` 那套跑，两边数据还没放在一起比过。
- **样本很小**：两个任务各跑一到两遍，不是统计。
