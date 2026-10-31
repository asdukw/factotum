# 端侧推理：结论与约束

端侧模型不是当前运行时的一部分：zoneu 的 agent 使用用户配置的云端提供商。本页只记录
「为什么没采用」和重新尝试前必须知道的硬约束，不保留实验过程。

## 1. 结论

在测试设备（一加 15T / 骁龙 8 Elite Gen 5 / 16GB）上，llama.cpp 的 Snapdragon 构建可以跑
1B–2B 的文本 / 视觉模型，但**不具备替代云端模型的条件**：

| 项 | 结果 |
|---|---|
| 单步时延 | 2.2–3.7 s；履行「点一下、等一下」的用户预期不够 |
| 视觉编码 | 随图像 token 数线性增长，是主要成本；降分辨率能省时间但行为不变好 |
| 读屏文字 | 小模型明显不如云端模型 |
| grounding | 坐标误差大，首点命中率不可用 |
| 任务完成判定 | UI-TARS / GUI-Owl 都判定不了「做完了没有」，容易在同一个动作上循环 |
| 内存 | 9B 级模型在 Q4 下装不下（AutoGLM 约 6.17 GB） |

协议层是可接的：dsh 的 provider 形状与这些模型的动作格式能对上。问题在模型能力，不在接线。

## 2. 重新尝试前的约束

- **时延是唯一卖点**，必须逐项拆：视觉编码、prefill、decode、动作执行。只测端到端会掩盖瓶颈。
- **限制输出 token** 是最便宜的优化：用 grammar 把输出钉成 tool_call JSON，decode 可降一档。
- **树可读时不要走视觉**：纯文本 prefill 比图像便宜一个数量级，视觉只作为兜底。
- **停止条件外置**：端侧模型只做单步执行，由外层（云端 planner / 状态机 / 步数上限）
  决定何时停。
- **不要用「答案看起来对」判断可用**：grounding 与读字都要有可核对的真值。

## 3. 平台坑

- llama.cpp 在骁龙上报 `HTP0 failed to open session ... 0x80000406`：没设
  `ADSP_LIBRARY_PATH=<lib 目录>`。HTP 的 skel URI 是硬编码的 `file:///libggml-htp-v81.so`，
  由 DSP 侧 fastrpc 按这个变量搜索；官方 README 与 `run.py` 都没写。
- 官方 Snapdragon 构建不需要 Qualcomm 工具链；`--list-devices` 应同时看到 GPUOpenCL 与 HTP0。
- `uiautomator dump` 会顶掉无障碍约 1 秒；取 grounding 真值时要固定「先读屏、再截图」的顺序。
- `adb shell cat` 会把 `\n` 翻成 `\r\n`，读 XML 用 `adb exec-out cat`。

## 4. 相关工具

`tools/uitars/` 保留 UI-TARS 的适配与实测脚本，仅用于端侧实验，不在默认运行时里。
