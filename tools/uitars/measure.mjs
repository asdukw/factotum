#!/usr/bin/env node
/**
 * UI-TARS 端侧 GUI 任务驱动 + 计时。
 *
 * 一次循环：截图 → 发给设备上的 llama-server → 解析它的原生动作 → 通过 Tappet 的
 * `DeviceActionReceiver` 执行 → 记时间。**它测的是「端侧模型 + 现有执行层」的速度**，
 * 不是完整产品链路（没有经过 dsh 的工具循环，那一步要另做）。
 *
 * 前置：
 *   1. 设备上 llama-server 已起：`adb shell "IMAGE_MAX_TOKENS=1024 sh /data/local/tmp/uitars/server.sh"`
 *   2. 端口转发：`adb forward tcp:18080 tcp:8080`
 *   3. App 的无障碍服务开着（执行层动作走它）
 *
 * 用法：
 *   node tools/uitars/measure.mjs --task "打开设置，进入关于手机，读出系统版本号" --max-steps 8
 */

import { execFileSync } from "node:child_process";
import {
  GUI_OWL_TOOLS,
  normalizeGuiOwl,
  parseAction,
  toDeviceAction,
} from "./adapt.mjs";

// 字节跳动官方 MOBILE_USE 模板（codes/ui_tars/prompt.py 的 MOBILE_USE_DOUBAO）。
// **不要随手改**：UI-TARS-2B-SFT 是在这个格式上微调的，换了措辞它会退化成泛泛的描述。
const PROMPT = `You are a GUI agent. You are given a task and your action history, with screenshots. You need to perform the next action to complete the task.
## Output Format
\`\`\`
Thought: ...
Action: ...
\`\`\`
## Action Space

click(point='<point>x1 y1</point>')
long_press(point='<point>x1 y1</point>')
type(content='') #If you want to submit your input, use "\\n" at the end of \`content\`.
scroll(point='<point>x1 y1</point>', direction='down or up or right or left')
open_app(app_name='\\'\\'')
drag(start_point='<point>x1 y1</point>', end_point='<point>x2 y2</point>')
press_home()
press_back()
finished(content='xxx') # Use escape characters \\', \\", and \\n in content part to ensure we can parse the content in normal python string format.


## Note
- Use Chinese in \`Thought\` part.
- Write a small plan and finally summarize your next action (with its target element) in one sentence in \`Thought\` part.

## User Instruction
{instruction}
`;

const args = parseArgs(process.argv.slice(2));
const serial = args.serial ?? process.env.ANDROID_SERIAL ?? "";
const url = args.url ?? "http://127.0.0.1:18080";
const maxSteps = Number(args["max-steps"] ?? 8);
const task = args.task;
const component = args.component ?? "com.tappet.app/.accessibility.DeviceActionReceiver";
// uitars：自由文本动作（`Action: click(point=...)`）
// guiowl：标准 tool call（`<tool_call>{"name":"mobile_use",...}</tool_call>`）
const protocol = args.protocol ?? "uitars";
if (!task) fail("缺少 --task");

const adb = (adbArgs, opts = {}) =>
  execFileSync("adb", serial ? ["-s", serial, ...adbArgs] : adbArgs, {
    maxBuffer: 256 * 1024 * 1024,
    ...opts,
  });

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith("--")) continue;
    const key = argv[i].slice(2);
    const value = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[++i] : "true";
    out[key] = value;
  }
  return out;
}

function fail(message) {
  console.error(`measure: ${message}`);
  process.exit(1);
}

/** 屏幕像素尺寸。每次任务读一次 —— 分屏 / 旋转都会变，写死会系统性点偏。 */
function screenSize() {
  const out = adb(["shell", "wm", "size"]).toString();
  const m = /(\d+)x(\d+)/.exec(out);
  if (!m) fail(`读不到屏幕尺寸: ${out}`);
  return { width: Number(m[1]), height: Number(m[2]) };
}

function screenshot() {
  // exec-out：`adb shell cat` 会把 \n 翻成 \r\n，二进制就毁了（AGENTS.md 记过）。
  return adb(["exec-out", "screencap", "-p"]);
}

async function ask(imageB64, prompt) {
  const started = Date.now();
  // **GUI-Owl 不要手写 system prompt**：GGUF 里的 chat template 在 `--jinja` 下
  // 自己渲染 <tools> 与 <tool_call> 的格式说明，再手写一遍是重复注入（实测过）。
  const messages =
    protocol === "guiowl"
      ? [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: `data:image/png;base64,${imageB64}` } },
            ],
          },
        ]
      : [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: `data:image/png;base64,${imageB64}` } },
            ],
          },
        ];

  const response = await fetch(`${url}/v1/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      model: protocol === "guiowl" ? "guiowl" : "uitars",
      // GUI-Owl 官方 generation_config.json：do_sample=true / temperature 0.6 /
      // top_p 0.95 / top_k 40。**不要用温度 0** —— 贪心解码是这类模型「循环、
      // 复读同一动作」的典型诱因（实测过：temp=0 时反复 press_home）。
      // UI-TARS 没有这份配置，保持贪心。
      ...(protocol === "guiowl"
        ? { temperature: 0.6, top_p: 0.95, top_k: 40, tools: GUI_OWL_TOOLS }
        : { temperature: 0 }),
      max_tokens: 256,
      messages,
    }),
  });
  if (!response.ok) fail(`推理失败 HTTP ${response.status}: ${await response.text()}`);
  const body = await response.json();
  const message = body.choices?.[0]?.message ?? {};
  return {
    content: message.content ?? "",
    toolCalls: message.tool_calls ?? null,
    timings: body.timings ?? {},
    wallMs: Date.now() - started,
  };
}

/** 执行层动作。返回耗时（含广播往返）。 */
function execute(deviceAction) {
  const started = Date.now();
  const extras = Object.entries(deviceAction.extras).flatMap(([k, v]) => {
    if (typeof v === "number") return ["--ei", k, String(v)];
    if (k === "duration_ms") return ["--el", k, String(v)];
    return ["--es", k, String(v)];
  });
  adb([
    "shell", "am", "broadcast", "-n", component,
    "-a", "com.tappet.app.debug.ACTION",
    "--es", "action", deviceAction.action,
    ...extras,
  ]);
  return Date.now() - started;
}

async function main() {
  const screen = screenSize();
  console.log(`任务: ${task}`);
  console.log(`屏幕: ${screen.width}x${screen.height}  端点: ${url}  最大步数: ${maxSteps}\n`);

  const history = [];
  const steps = [];
  for (let step = 1; step <= maxSteps; step++) {
    const image = screenshot();
    // 两条协议的提示词格式不同 —— 这是 2B SFT 模型最敏感的地方，不要混用。
    const prompt =
      protocol === "guiowl"
        ? "Please generate the next move according to the UI screenshot, instruction and previous actions.\n\n" +
          `Instruction: ${task}\n\n` +
          "Previous actions:\n" +
          (history.length ? history.map((h, i) => `Step${i + 1}: ${h}`).join("\n") : "No previous action.")
        : PROMPT.replace("{instruction}", task) +
          (history.length ? `\n\n## Action History\n${history.map((h, i) => `${i + 1}. ${h}`).join("\n")}` : "");

    const { content, toolCalls, timings, wallMs } = await ask(image.toString("base64"), prompt);
    // 两条协议在这里汇合：都归一成同一个内部动作形状，后面共用一套映射。
    const { thought, action } =
      protocol === "guiowl"
        ? { thought: content.trim(), action: normalizeGuiOwl(toolCalls?.[0]?.function?.arguments) }
        : parseAction(content);

    if (!action) {
      console.log(`[${step}] 解析不出动作，停止。原始输出:\n${content.slice(0, 400)}\n`);
      break;
    }
    if (action.type === "finished") {
      steps.push({ step, type: "finished", timings, wallMs, execMs: 0 });
      console.log(`[${step}] finished: ${action.content}`);
      break;
    }

    const device = toDeviceAction(action, screen);
    let execMs = 0;
    if (device.unsupported) {
      console.log(`[${step}] ⚠ 不支持的动作 ${device.unsupported}，跳过`);
    } else {
      execMs = execute(device);
    }
    steps.push({ step, type: action.type, timings, wallMs, execMs });
    history.push(device.unsupported ? `${action.type} (unsupported)` : `${device.action} ${JSON.stringify(device.extras)}`);

    console.log(
      `[${step}] ${action.type.padEnd(11)} ` +
        `prefill ${fmt(timings.prompt_ms)} (${timings.prompt_n ?? "?"} tok)  ` +
        `decode ${fmt(timings.predicted_ms)} (${timings.predicted_n ?? "?"} tok)  ` +
        `exec ${String(execMs).padStart(4)}ms  ` +
        `端侧合计 ${fmt(wallMs)}` +
        (thought ? `\n     ${thought.slice(0, 100)}` : ""),
    );
    // **动作后要等界面落定再截图**。1.2s 实测不够：`LAUNCH_APP` 广播是「发出启动请求」
    // 就返回（几百毫秒），而 App 冷启动要好几秒 —— 下一帧会拍到启动过程中的过渡画面，
    // 模型拿着它做决策，看起来像「模型瞎点」。官方 agent 用 `wait` 动作表达同一件事。
    // 启动类动作给更长的等待。
    const settleMs = device.unsupported ? 0 : device.action === "LAUNCH_APP" ? 4000 : 1500;
    await new Promise((r) => setTimeout(r, settleMs));
  }

  summarize(steps);
}

function summarize(steps) {
  const real = steps.filter((s) => s.type !== "finished");
  if (!real.length) return console.log("\n没有执行任何动作。");
  const sum = (key) => real.reduce((a, s) => a + (s.timings[key] ?? 0), 0);
  const total = real.reduce((a, s) => a + s.wallMs + s.execMs, 0);
  console.log("\n=== 汇总 ===");
  console.log(`步数            ${real.length}`);
  console.log(`视觉+prefill     ${fmt(sum("prompt_ms"))}  (${sum("prompt_n")} tok)`);
  console.log(`decode           ${fmt(sum("predicted_ms"))}  (${sum("predicted_n")} tok)`);
  console.log(`动作执行         ${fmt(real.reduce((a, s) => a + s.execMs, 0))}`);
  console.log(`每步平均         ${fmt(total / real.length)}`);
  console.log(`整任务           ${fmt(total)}`);
}

const fmt = (ms) => (ms == null ? "?" : `${(ms / 1000).toFixed(2)}s`);

main().catch((error) => fail(error.stack ?? String(error)));
