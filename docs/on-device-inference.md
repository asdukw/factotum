# 端侧推理调研与实测（2026-10-19）

**什么时候读这份**：要动「端侧模型 + 推理引擎」这条线之前。这里只有**实测数字与踩到的坑**，
路线判断写在末尾且明确标成「未验证的假设」。

原文动机：无障碍模式操控手机时，每一步都要把截图发给云端模型，**网速延迟太大**。
端侧能砍掉的是往返 + 上传 + 排队，砍不掉的是 prefill 一张大图。所以「端侧是否更快」
完全取决于**视觉编码 + prefill 能不能跑在 NPU 上**。

---

## 1. 设备画像（`adb shell` 实测，一加 15T / PLZ110）

| 项 | 值 | 来源 |
|---|---|---|
| SoC | **SM8850 = 骁龙 8 Elite Gen 5**（`ro.soc.model`） | `getprop` |
| 屏幕上也写着 | 「第五代骁龙®8至尊版移动平台」（系统设置 → 关于本机） | 截图 |
| NPU | **Hexagon HTP v81** | llama.cpp Verbose 日志 |
| GPU | **Adreno 840** v2，OpenGL ES 3.2，`libOpenCL_adreno.so` 在 | `dumpsys SurfaceFlinger` |
| 内存 | 15.4 GB 总量 | `/proc/meminfo` |
| 屏幕 | 1216×2640 px | `screencap` |
| 系统 | **Android 17.0**（`PLZ110_17.0.0.107(CN01B110P02)`），**API 37** | `getprop` |
| 存储 | 512 GB，已用 227 GB | `df` |

> **注意 API 37**：仓库 `compileSdk`/`targetSdk` 还是 36，设备已经超了一级。
> 这与 `AGENTS.md` 里那条「某依赖 requires compileSdk 37」是同一个方向的债。

## 2. 内存底线（真机实测，`dumpsys meminfo`）

| 阶段 | PSS | RSS |
|---|---|---|
| 只有 Compose UI | **119 MB** | 262 MB |
| + 内嵌运行时（libnode + dsh） | 327 MB（刚起来时） | 477 MB |
| 稳态（运行时 + 截图路径） | 268 MB | 363 MB |
| 截图瞬间峰值（连拍 12 张） | 294 MB | 388 MB |

结构比总量重要：

- **Private Dirty 只有 114 MB** —— 这才是「这个应用真正占住的」。
- RSS 里 **188 MB 是 file-backed 的 code**（`libnode.so` 64MB + dsh + 应用代码），**可回收**。
- 截图路径的边际开销只有约 25 MB（位图 + PNG + base64），不是大头。

系统侧：`MemAvailable` 约 3.5 GB，但 **swap 12.6 GB 已用掉 10.5 GB** —— 这台机器负载很重。

**推论**：mmap 的模型权重是 file-backed、可回收的，**真正吃紧的是 KV cache 与视觉编码器的
匿名内存**。按这个算，2B 级（量化后 ~1.6 GB）能塞下，4B 级（~3 GB）悬。

## 3. llama.cpp 的 Snapdragon 构建：可用，但有个没写在文档里的坑

官方 release 有 `llama-b<build>-bin-android-arm64-snapdragon.tar.gz`（含 v73/v75/v79/**v81**
四套 HTP kernel、`libggml-opencl.so`、`libmtmd.so`）。**不需要 Qualcomm 的交叉编译工具链**，
推上去就能跑：

```powershell
adb push <解压后的 llama-b11729> /data/local/tmp/
adb shell "chmod 755 /data/local/tmp/llama-b11729/bin/*"
adb shell "cd /data/local/tmp/llama-b11729 && LD_LIBRARY_PATH=lib ./bin/llama-cli --list-devices"
# → GPUOpenCL: QUALCOMM Adreno(TM) 840 (7537 MiB, 6513 MiB free)
#   HTP0: Hexagon (0 MiB, 0 MiB free)
```

> **坑：HTP 必须设 `ADSP_LIBRARY_PATH`。**
> 不设时报 `HTP0 failed to open session : uri file:///libggml-htp-v81.so?... error 0x80000406`。
> URI 是硬编码的 `file:///libggml-htp-v81.so`，由 DSP 侧的 fastrpc 加载器按 `ADSP_LIBRARY_PATH`
> 搜索。官方 README 与 `scripts/snapdragon/run.py` **都没有提这一条**（`run.py` 只设 `LD_LIBRARY_PATH`），
> 照着文档做必然卡在这里。
>
> ```powershell
> adb shell "cd /data/local/tmp/llama-b11729 && \
>   LD_LIBRARY_PATH=lib ADSP_LIBRARY_PATH=/data/local/tmp/llama-b11729/lib ./bin/llama-cli ..."
> ```

### 实测吞吐（Llama-3.2-1B-Instruct Q4_0，`llama-bench -p 128 -n 64`）

| 后端 | pp128 | tg64 |
|---|---|---|
| **HTP0（Hexagon NPU）** | **3411.6 ± 103.7 t/s** | **74.7 ± 0.8 t/s** |
| GPUOpenCL（Adreno 840） | 986.4 ± 23.5 t/s | 61.4 ± 0.6 t/s |

对照官方 README 里老设备的 HTP 数字（1B Q4_0：pp128 **169** / tg64 51.5）—— **这台机器快约 20 倍**。
对我们的场景（长 prefill、短输出）来说 prefill 非常宽裕。

> **纯 CPU 基线没测出来**：`-ngl 0` 仍会在 context 初始化时加载 hex 后端并失败；
> 这版构建的 `--list-devices` 里也没有 CPU 设备。要 CPU 基线得另找办法。

## 4. VLM：跑得通，但**视觉编码是瓶颈，且随图像 token 数线性增长**

模型：`Qwen3-VL-2B-Instruct-Q4_K_M.gguf`（1.11 GB）+ `mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf`（0.45 GB），
`llama-mtmd-cli -dev HTP0 -ngl 99`，输入是**真机截图**缩放而来。

| 输入图像 | 视觉编码耗时 |
|---|---|
| 1216×2640（原生屏幕） | **6385 ms** |
| 943×2047（**Tappet 现在 `capture_frame` 产出的帧**） | **2672 ms** |
| 长边 1024 | 484 ms |
| 长边 768 | 290 ms |
| 长边 512 | 146 ms |

**把截图缩到长边 1024 能把单步感知从 6.4 秒降到 0.5 秒** —— 这是这条路上最大的一个旋钮。

另外两条实测：

- **视觉编码器放 GPU 反而慢 8 倍**（`-dev GPUOpenCL`：50049 ms vs HTP 6385 ms）。必须留在 HTP。
- HTP 跑 CLIP 图时有未支持算子（`UPSCALE: f32`）回退，日志会警告 `performance will be suboptimal`。
- Qwen-VL 做 grounding 时模型自己会警告需要 `--image-min-tokens 1024`，**与上面的「缩图提速」
  正好是一对矛盾**：缩得越小越快，但可能越不准。这个取舍必须实测。
- 模型有 **MTP（多 token 预测）** 的独立 drafter 权重可下（`mtp-*.gguf`），官方称可提速约 3 倍。

## 5. 但 grounding 与读字都不合格（**这是最重要的一条**）

真值用 `uiautomator dump` 取（不依赖 Tappet 的无障碍服务），输入是系统设置「关于本机」截图。
提示词用 Qwen3-VL 的原生格式：输出归一化到 0-1000 的 `[x1,y1,x2,y2]`。

| 目标 | 真值中心（归一化） | 模型输出中心 | 结果 |
|---|---|---|---|
| 存储空间 | (184, 696) | (274, 704) | y 准（差 8），x 偏右 90 |
| 处理器 | (152, 917) | (308, 803) | x 偏 156、y 偏 114 |
| 运行内存 | (173, 996) | (400, 803) | 严重偏离 |
| 手机名称 | (229, 631) | `[0,0,1000,1000]` | **整屏，完全失败** |

读文字更直接：问「这屏上的处理器与存储是多少」，输出退化成
`处理器是: 100000000000000...`（一长串 0）。同一模型对「这是哪个 App」这类**粗粒度**问题
回答是正常的。

> **一条可能解释一切的线索**：llama.cpp 有一个**至今未关闭**的 issue ——
> *「Qwen3-VL: mtmd vision embeddings carry ~5× HF precision-floor noise → reproducible accuracy
> drop vs transformers (quant/backend/version-independent)」*。
> 也就是说这个失败**可能出在 llama.cpp 的 `mtmd` 视觉路径，而不是 2B 模型本身**。
> 在排除它之前，不要把结论下成「2B 不够用」。

**但先别下结论** —— 上面这个失败有个更可能的解释：llama.cpp 有一个**至今未关闭**的 issue，
*「Qwen3-VL: mtmd vision embeddings carry ~5× HF precision-floor noise → reproducible accuracy
drop vs transformers（quant/backend/version-independent）」*。也就是说问题**可能出在引擎的视觉路径**。
下面第 6 节就是为排除它而做的实验。

## 6. 模型对比：同一引擎换三个模型

设计：**同设备、同引擎（llama.cpp `mtmd` + HTP0）、同截图、同提示词**，只换模型。
三个模型分别是 Qwen3-VL-2B、Gemma 4 E2B、UI-TARS-2B-SFT（GUI 专用微调）。
目的不是选型，是**把「引擎坏了」与「模型不行」分开** —— 上一节那个失败不排除引擎之前，
不能得出「2B 级别不够」的结论。

### 读屏文字（决定性）

问「这屏上的处理器与存储是多少」，与 `uiautomator dump` 的真值比对：

| 模型 | 输出 | 判定 |
|---|---|---|
| Qwen3-VL-2B Q4_K_M | `处理器是: 100000000000000...`（一长串 0） | **失败** |
| **Gemma 4 E2B Q4_0** | 「处理器是第五代骁龙®8至尊版移动平台，存储空间 270 GB / 512 GB」 | ✅ **逐字正确** |

Gemma 4 甚至把整屏元素**完整列了出来**：`手机名称/一加15T`、`存储空间/270 GB / 512 GB`、
`一加15T/PLZ110_17.0.0.107(CN01B110P02)`、`处理器/第五代骁龙®8至尊版移动平台` —— 四项文字全对。

**结论：视觉路径本身没坏（Gemma 4 读中文 UI 文字完全准确），问题在 Qwen3-VL + mtmd 这个组合。**
那条 upstream issue 的描述与这里的现象一致。

### grounding（归一化 0-1000，真值来自 uiautomator）

| 目标 | 真值 y 区间 | Qwen3-VL-2B | Gemma 4 E2B |
|---|---|---|---|
| 手机名称 | 616–645 | `[0,0,1000,1000]` ❌ | `[30,600,970,660]` ✅ |
| 存储空间 | 682–711 | `[248,688,300,720]` ⚠️ 偏右 | `[30,600,970,750]` ✅ |
| 处理器 | 904–931 | `[288,788,328,818]` ❌ | `[30,880,970,960]` ✅ |
| 运行内存 | 992–1000 | `[300,785,500,820]` ❌ | `[30,600,970,740]` ❌ |

Gemma **3/4 命中**（区间覆盖真值行），Qwen3-VL 约 **1/4**。
但要注意 Gemma 给的是**整行**的粗框（`[30, y1, 970, y2]`），不是标签本身；
而且「手机名称」与「存储空间」它给到了几乎同一区域（都从 y=600 起），**说明它也在猜行号而不是精确指认**。
按「能不能点中」的标准算可用，按「grounding 精度」的标准还不合格。

### 视觉编码耗时（同一张 1216×2640 截图）

| 模型 | 全分辨率 | 长边 1024 |
|---|---|---|
| Qwen3-VL-2B（mmproj Q8） | 6385 ms | 484 ms |
| **Gemma 4 E2B（mmproj Q8）** | **2746 ms** | **324 ms** |

Gemma 在两个分辨率上都更快。

### 第三个模型：UI-TARS-2B-SFT（GUI 专用微调）

基座已确认：`config.json` 里 `architectures = Qwen2VLForConditionalGeneration`、
`model_type = qwen2_vl`、28 层、hidden 1536 —— **是 Qwen2-VL 系**，llama.cpp 原生支持。
用 `lmstudio-community/UI-TARS-2B-SFT-GGUF` 的 `Q4_K_M`（1.12 GB）+ `mmproj-model-f16.gguf`（1.33 GB）。

**读字：✅ 两项都正确**（「第五代骁龙®8至尊版移动平台」「270 GB / 512 GB」）。

**grounding：4/4 全中**，而且是三个模型里唯一做到的。但它输出的是**自己的原生格式**
`<|box_start|>(x,y)` —— 一个**点**，不是框。按「行中心」这个真值算：

| 目标 | 真值行中心 y | UI-TARS y | 误差 |
|---|---|---|---|
| 手机名称 | 631 | 613 | 18 |
| 存储空间 | 696 | 668 | 28 |
| 处理器 | 917 | **919** | **2** |
| 运行内存 | 992 | 983 | 9 |

x 恒定落在 ~490–700（**整行的水平中心**）。按「点中这一行」的标准算它是对的，
按「精确定位标签」的标准算它只给了行级。

### 三个模型放一起

| 模型 | 读屏文字 | grounding（4 目标） | 视觉编码 全分辨率 / 长边 1024 |
|---|---|---|---|
| Qwen3-VL-2B Q4_K_M（mmproj Q8 0.45GB） | ❌ 退化成一串 0 | ~1/4 | 6385 / 484 ms |
| Gemma 4 E2B Q4_0（mmproj Q8 0.56GB） | ✅ | 3/4（粗框，两项撞到同一区域） | 2746 / 324 ms |
| **UI-TARS-2B-SFT Q4_K_M（mmproj **F16** 1.33GB）** | ✅ | **4/4** | **18296 / 1019 ms** |

**结论**：

1. **引擎没坏** —— 另外两个模型都能正确读出中文 UI 文字。问题在 **Qwen3-VL + mtmd** 这个组合。
2. **GUI 专用微调确实有用** —— UI-TARS 是唯一 4/4 全中的，而且它输出的是**动作**而不是描述，
   与 Tappet 的工具调用形态天然更近。代价是它只给到**行级**精度（x 恒为行中心）。
3. **UI-TARS 目前慢 6–7 倍**，最大嫌疑是它的 mmproj 只有 **f16**（1.33GB，另两个是 Q8）。
   用 `llama-quantize` 把 mmproj 压到 Q8 应该能显著改善 —— **这一步还没做，是下一个最便宜的实验**。
4. 缩图对三者都有效（全分辨率 → 1024 长边，编码时间降一个数量级），所以「先缩图再推理」
   应当作为默认策略，而不是优化项。

### mmproj 量化实验：**否定了「权重带宽是瓶颈」这个假设**

动机：UI-TARS 编码慢 6–7 倍，而它唯一明显的差异是 mmproj 只有 **f16**（1.33 GB，另两个是 Q8）。
视觉编码是小 batch 矩阵乘、通常 memory-bound，所以「压成 Q8 就快了」是当时最合理的猜测。

做法（本机量化，`llama-b11729-bin-win-cpu-x64` 里的 `llama-quantize`）：

```
llama-quantize mmproj-model-f16.gguf mmproj-model-q8_0.gguf Q8_0
  model size  = 1269.94 MiB (16.01 BPW)
  quant size  =  676.97 MiB (8.54 BPW)      # 1.33 GB -> 0.71 GB，减半
```

**结果：没有任何改善。** 同一会话里背靠背跑两轮（全分辨率 1216×2640 截图）：

| 轮次 | mmproj f16 | mmproj Q8_0 |
|---|---|---|
| 1 | 16727 ms | 17871 ms |
| 2 | 17842 ms | 17958 ms |

→ **瓶颈不是权重带宽**。视觉编码的时间与 mmproj 的大小基本无关。

**精度没有损失**（4/4 仍然全中，坐标几乎逐字相同）：

| 目标 | f16 | Q8_0 | 真值行中心 y |
|---|---|---|---|
| 手机名称 | (496,613) | (500,613) | 631 |
| 存储空间 | (698,668) | (698,668) | 696 |
| 处理器 | (499,919) | (499,919) | 917 |
| 运行内存 | (492,983) | (499,983) | 992 |

**所以 Q8 是纯内存收益**（省一半磁盘/内存），不是速度收益。在内存吃紧的机器上仍值得用。

**那瓶颈在哪？** 两条线索：

1. 日志里 HTP 明确报过 CLIP 图有未支持算子：
   `WARNING: the CLIP graph uses unsupported operators by the backend (backend=HTP0): UPSCALE: type = f32`
   → 有算子回退，那部分不走 NPU。
2. 编码耗时随图像 token 数**超线性**：全分辨率 4042 个视觉 token（Patch14/Merge2 下约
   43×94）要 17.9 s，长边 1024 约 576 个 token 只要 1.0 s —— token 数只差 7 倍，时间差 18 倍。
   这个形状更像 **vision encoder 里注意力的 $O(n^2)$**，而不是权重量。

两条都指向「**图执行效率**」而不是「模型大小」—— 这正好解释了为什么换更小的 mmproj 没用。

### 三个操作上的坑

- **Gemma 4 必须加 `--jinja`**：不加直接 `libc++abi: terminating ... this custom template is not
  supported, try using --jinja` 然后 Aborted。
- **Gemma 4 是推理模型**：默认会先吐一大段 thinking，`-n 64` 会让它**还没给出答案就截断**。
  问坐标时要给到 `-n 2000` 左右，并在提示词里明确「No explanation / Output only ...」。
  实际使用时要考虑这部分 token 成本。
- **默认上下文不够**：`llama-mtmd-cli` 不给 `-c` 时，UI-TARS 会在解码阶段报
  `decode: failed to find a memory slot for batch of size 1` 并把回答截断。加 `-c 8192` 解决。
  （高分辨率截图本身就要吃掉几千个视觉 token，默认上下文很容易不够。）

## 7. UI-TARS 适配与端侧 GUI 任务实测

代码在 `tools/uitars/`（纯测量工具，不是产品代码），细节与坑见那里的 `README.md`。
这一节只记结论。

### 适配的实质

UI-TARS 是**动作策略**模型，不是会调工具的对话模型。它输出自己的格式，与 Tappet 的
`DeviceAction` 没有交集，中间必须有一层翻译：

```
模型:  Action: click(start_box='(611,899)')      # 归一化 0–1000
  ↓ adapt.mjs
执行层: TAP_POINT x=743 y=2373                    # 屏幕像素
```

**实测发现的第一个坑：实际 checkpoint 的动作格式与官方模板不一致。**
官方 `codes/ui_tars/prompt.py` 的 `MOBILE_USE_DOUBAO` 写的是
`click(point='<point>x y</point>')`，而 UI-TARS-2B-SFT 实际输出
`click(start_box='(611,899)')` 或 `click(start_box='<|box_start|>(x,y)<|box_end|>')`。
只按文档写解析器，真机上表现为「解析不出动作」—— 第一次跑就是这么挂的。

### 速度（一加 15T，同一任务只改 `IMAGE_MAX_TOKENS`）

| `IMAGE_MAX_TOKENS` | 每步平均 | 5 步任务 |
|---|---|---|
| 1024 | **4.5 s** | ~22 s |
| 4096（≈原图） | **24.3 s** | ~97 s |

单步拆解（1024 档）：视觉+prefill **2.2 s**、decode **1.8 s**、动作执行 **0.1 s**。

两点：

- **执行层几乎不花时间**（0.1 s）—— 瓶颈 100% 在模型侧，这也意味着**优化执行层没有意义**。
- 速度随图像 token 数**超线性**（token 数 4.3×、时间 5.4×），与第 6 节一致。

### 但任务级行为不合格

两个任务（「打开设置」「打开计算器」）在 1024 与 4096 两档下**都完不成**：

- 它会在 `press_home` 上循环：一边说「当前屏幕显示的是设置应用的主界面」，
  一边按 home 键说要「回到主屏幕再重新打开」。
- 会把别的 App 图标当成通往目标的第一步（把「Clash」当成打开计算器的第一步）。
- 找不到目标时不去搜索，而是反复点屏幕底部同一片区域。

**关键对照**：把分辨率提到 4096（≈原图）**行为并没有变好**。所以「行为差是因为缩图」
这个解释被排除了 —— 它就是模型本身在这个任务形态上不行。

### 还没排除的一个混淆因素

UI-TARS 的训练格式里，history 是**带截图**的（官方 prompt 原话：
「given a task and your action history, with screenshots」）。而 `measure.mjs` 只给了
**文字历史**。这可能是它多步行为差的原因之一，**未排除**。要排除得把历史截图也发进去，
但那会让每步的 prefill 成倍增长（每张图 ~1000 token），是个明确的成本取舍。

### 和单图 grounding 的对照

同一个模型：单张静态截图上的 grounding **4/4 全中**（第 6 节），多步任务**零完成**。
**「能指对位置」和「能完成任务」是两件事** —— 前面几节的 grounding 成绩不能外推成
「这个模型能用」。

## 8. LiteRT-LM 的现状（查证，未实测）

- Kotlin API 标为 **stable**，Android 是一等公民；原生 vision + audio；带约束解码的 function calling；
  自带 OpenAI 兼容的 `litert-lm serve`。
- **但 NPU 加速要申请 Early Access Program**（官方文档原话）。公开可用的只有 GPU 路径。
- **没有现成的 Android 二进制**：release 只发 macOS/iOS 的 xcframework，
  Android 要自己 `bazel build --config=android_arm64 //runtime/engine:litert_lm_main`，
  需要 Bazel 7.6.1 + NDK r28b。文档里 Android 只写了 Linux/macOS 宿主的步骤（Windows 宿主未验证）。

## 9. 复现步骤

```powershell
# 1. 拿官方 Snapdragon 构建（不需要 Qualcomm 工具链）
gh release download <最新 b 版本> -R ggml-org/llama.cpp `
  -p "llama-b*-bin-android-arm64-snapdragon.tar.gz"
tar -xzf llama-b*-bin-android-arm64-snapdragon.tar.gz

# 2. 推上设备
$D = "<serial>"
adb -s $D push .\llama-b11729 /data/local/tmp/
adb -s $D shell "chmod 755 /data/local/tmp/llama-b11729/bin/*"

# 3. 确认两个后端都在
adb -s $D shell "cd /data/local/tmp/llama-b11729 && LD_LIBRARY_PATH=lib ./bin/llama-cli --list-devices"

# 4. 纯文本吞吐
adb -s $D shell "cd /data/local/tmp/llama-b11729 && LD_LIBRARY_PATH=lib ADSP_LIBRARY_PATH=/data/local/tmp/llama-b11729/lib ./bin/llama-bench -m /data/local/tmp/gguf/model.gguf -p 128 -n 64 -dev HTP0 -ngl 99"

# 5. 视觉
adb -s $D exec-out screencap -p > screen.png
adb -s $D push screen.png /data/local/tmp/gguf/
adb -s $D shell "cd /data/local/tmp/llama-b11729 && LD_LIBRARY_PATH=lib ADSP_LIBRARY_PATH=/data/local/tmp/llama-b11729/lib ./bin/llama-mtmd-cli -m <model.gguf> --mmproj <mmproj.gguf> --image /data/local/tmp/gguf/screen.png -p '<prompt>' -dev HTP0 -ngl 99 -n 48 --temp 0"
```

取 grounding 真值：

```powershell
adb -s $D shell "am start -a android.settings.DEVICE_INFO_SETTINGS"
adb -s $D shell "uiautomator dump /sdcard/ui.xml"
adb -s $D exec-out cat /sdcard/ui.xml   # 注意：必须 exec-out，adb shell cat 会把 \n 翻成 \r\n
```

> `uiautomator dump` 会把 Tappet 的无障碍服务顶掉约 1 秒（`AGENTS.md` 已记），
> 所以**先读屏、再截图**的顺序要固定，否则两次取到的不是同一屏。

## 10. 下一步（按性价比排序）

**当前位置**：速度已经不是问题（1024 档 4.5 s/步，执行层只占 0.1 s），
**拦路虎是任务级行为** —— UI-TARS-2B-SFT 在两个任务、两档分辨率下都完不成。

1. **排除 history 截图这个混淆**（唯一还没排除的解释）。UI-TARS 训练时 history 是带截图的，
   `measure.mjs` 只给了文字。改成把历史截图也发进去重跑 —— 若行为变好，说明是这个；
   若没变，就可以确定是模型能力问题，直接进 2。**成本明确**：每张历史图 ~1000 token。
2. **换模型**：UI-TARS-2B-SFT 之外，候选是 ShowUI-2B（视觉 token 剪枝，打在编码瓶颈上）
   与 UI-TARS-1.5-7B（ScreenSpot-Pro 61.6 / Android World 64.2，但 ~4.5 GB 装不下）。
3. **若 2B 档普遍不行**，接受混合方案：端侧只做**粗筛 / 定位**（这一档它确实行，4/4），
   多步规划仍走云端。这需要在 Tappet 里把「感知」和「决策」拆成两段 ——
   现在的架构是合在一起的。
4. **动作空间映射**仍然要做（UI-TARS 输出 `click(start_box=…)`，与现有 10 个工具不对齐）——
   `tools/uitars/adapt.mjs` 是这一层的最小实现，正式接进产品时可以直接搬。
5. 边缘方案：LiteRT-LM（NPU 走 EAP、Android 要自建 Bazel+NDK）。
