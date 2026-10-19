#!/usr/bin/env node
/**
 * 时延拆解探针：把一步的开销拆成 prefill / decode 两段，并验证「要不要写 Thought」的代价。
 *
 * 端侧 GUI 任务里，用户对时延的感知是**按「停在某一页几秒」**来的 —— 所以要知道
 * 每一毫秒花在哪。两个可直接调的旋钮：
 *   - 图像 token 数（服务端 `--image-max-tokens`）→ 决定 prefill
 *   - 要不要输出 Action/Thought 那句 → 决定 decode
 *
 * 用法：node tools/uitars/probe-latency.mjs [图片路径]
 */

import { readFileSync } from "node:fs";
import { GUI_OWL_SYSTEM_PROMPT, GUI_OWL_TOOLS } from "./adapt.mjs";

const IMAGE = process.argv[2] ?? "C:/Users/asdukw/AppData/Local/Temp/vlm/now.png";
const URL = "http://127.0.0.1:18080/v1/chat/completions";
const TASK = process.argv[3] ?? "打开计算器应用";

const image = readFileSync(IMAGE).toString("base64");

async function run(label, systemContent) {
  const body = {
    model: "guiowl",
    temperature: 0,
    max_tokens: 128,
    tools: GUI_OWL_TOOLS,
    messages: [
      { role: "system", content: systemContent },
      {
        role: "user",
        content: [
          { type: "text", text: `Instruction: ${TASK}` },
          { type: "image_url", image_url: { url: `data:image/png;base64,${image}` } },
        ],
      },
    ],
  };
  const started = Date.now();
  const response = await fetch(URL, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const wall = Date.now() - started;
  const data = await response.json();
  const t = data.timings ?? {};
  const action = data.choices[0].message.tool_calls?.[0]?.function?.arguments ?? data.choices[0].message.content;
  const pad = (v) => String(v).padStart(5);
  console.log(
    `${label.padEnd(20)} prefill ${pad(t.prompt_n)} tok / ${pad(Math.round(t.prompt_ms ?? 0))} ms` +
      `   decode ${pad(t.predicted_n)} tok / ${pad(Math.round(t.predicted_ms ?? 0))} ms` +
      `   wall ${pad(wall)} ms`,
  );
  console.log(`   -> ${String(action).slice(0, 130)}`);
  return t;
}

await run("Action 行 (官方格式)", GUI_OWL_SYSTEM_PROMPT);
await run(
  "只输出 tool_call",
  GUI_OWL_SYSTEM_PROMPT.replace(
    "1) Action: a short imperative describing what to do in the UI.\n2) A single <tool_call>...</tool_call> block containing only the JSON.",
    "1) A single <tool_call>...</tool_call> block containing only the JSON.",
  ).replace("- Be brief: one sentence for Action.\n", "- Do NOT write an Action line.\n"),
);
