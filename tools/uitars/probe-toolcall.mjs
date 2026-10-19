#!/usr/bin/env node
/**
 * 一次性探针：llama-server 会不会把 GUI-Owl 的 `<tool_call>` 解析成 OpenAI 的 `tool_calls`。
 *
 * 这件事决定 **dsh 能不能直接沿用** —— 解析得了，dsh 就有资格上桌和官方 agent loop 比；
 * 解析不了，才需要自建循环。UI-TARS 那次是自由文本，dsh 完全套不进去；GUI-Owl 不一样。
 *
 * 用法：node tools/uitars/probe-toolcall.mjs [图片路径]
 */

import { readFileSync } from "node:fs";

const IMAGE = process.argv[2] ?? "C:/Users/asdukw/AppData/Local/Temp/vlm/settings_1024.png";
const URL = "http://127.0.0.1:18080/v1/chat/completions";

const TOOLS = [
  {
    type: "function",
    function: {
      name: "mobile_use",
      description:
        "Use a touchscreen to interact with a mobile device, and take screenshots. " +
        "The screen's resolution is 1000x1000.",
      parameters: {
        type: "object",
        required: ["action"],
        properties: {
          action: {
            type: "string",
            enum: [
              "key", "click", "long_press", "swipe", "type",
              "system_button", "open", "wait", "answer", "interact", "terminate",
            ],
            description: "The action to perform.",
          },
          coordinate: {
            type: "array",
            description: "(x, y) in the 1000x1000 grid. Required by click/long_press/swipe.",
          },
          text: { type: "string", description: "Required by key/type/open/answer/interact." },
          button: { type: "string", enum: ["Back", "Home", "Menu", "Enter"] },
          status: { type: "string", enum: ["success", "failure"] },
        },
      },
    },
  },
];

// 官方 SYSTEM_PROMPT 的尾部 —— 格式约束那几行是最关键的（2B 模型靠它稳住输出格式）
const SYSTEM =
  "# Tools\n\nYou may call one or more functions to assist with the user query.\n\n" +
  "For each function call, return a json object with function name and arguments within " +
  "<tool_call></tool_call> XML tags:\n" +
  '<tool_call>\n{"name": <function-name>, "arguments": <args-json-object>}\n</tool_call>\n\n' +
  "# Response format\nResponse format for every step:\n" +
  "1) Action: a short imperative describing what to do in the UI.\n" +
  "2) A single <tool_call>...</tool_call> block containing only the JSON.\n" +
  "Rules:\n- Output exactly in the order: Action, <tool_call>.\n" +
  "- Be brief: one sentence for Action.\n" +
  "- Do not output anything else outside those two parts.\n" +
  "- If finishing, use action=terminate in the tool call.";

const image = readFileSync(IMAGE).toString("base64");
const body = {
  model: "guiowl",
  temperature: 0,
  max_tokens: 256,
  tools: TOOLS,
  messages: [
    { role: "system", content: SYSTEM },
    {
      role: "user",
      content: [
        { type: "text", text: "Instruction: 打开关于本机页面" },
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
const elapsed = ((Date.now() - started) / 1000).toFixed(2);
if (!response.ok) {
  console.error(`HTTP ${response.status}: ${await response.text()}`);
  process.exit(1);
}
const data = await response.json();
const message = data.choices[0].message;
const t = data.timings ?? {};

console.log(`wall            ${elapsed}s`);
console.log(`finish_reason   ${data.choices[0].finish_reason}`);
console.log(`tool_calls      ${JSON.stringify(message.tool_calls ?? null)}`);
console.log(`content         ${(message.content ?? "").slice(0, 400)}`);
console.log(
  `timings         prompt_n=${t.prompt_n} prompt_ms=${Math.round(t.prompt_ms ?? 0)} ` +
    `predicted_n=${t.predicted_n} predicted_ms=${Math.round(t.predicted_ms ?? 0)}`,
);
