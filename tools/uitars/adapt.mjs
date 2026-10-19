/**
 * UI-TARS 原生动作 → Tappet 执行层动作的映射。
 *
 * **为什么需要这一层**：UI-TARS 是「动作策略」模型，不是会调工具的对话模型。它按字节跳动
 * 官方 MOBILE_USE 模板输出自己的格式：
 *
 *   Thought: ...
 *   Action: click(point='<point>500 900</point>')
 *
 * 而 Tappet 的执行层认的是 `DeviceAction`（`TAP_POINT` / `PRESS` / `TYPE_TEXT` / …）。
 * 两者之间没有交集，必须翻译 —— 这就是「适配」的实质。把这一层写成**纯函数**，
 * 是为了能离开设备验证：解析错一个正则，在设备上表现为「点了屏幕中间」，看着像模型不准。
 *
 * 动作空间取自 https://github.com/bytedance/UI-TARS 的 `codes/ui_tars/prompt.py`
 * 里 `MOBILE_USE_DOUBAO` 那一段。
 */

/** UI-TARS 的坐标是**归一化 0–1000**，不是像素。 */
export const NORMALIZED_MAX = 1000;

/**
 * 解析模型输出。返回 `{ thought, action }`，`action` 为 `null` 表示没解析出来。
 *
 * 只认 `Action:` 那一行之后的**第一个**动作调用 —— 模型的 Thought 里也常常提到动作名
 * （「I will click the ...」），不锚在 `Action:` 上会误把思考当成动作。
 *
 * @param {string} text 模型的原始输出
 */
export function parseAction(text) {
  if (typeof text !== "string") return { thought: "", action: null };
  const thought = (/Thought:\s*([\s\S]*?)(?=\n\s*Action:|$)/i.exec(text)?.[1] ?? "").trim();
  const actionLine = /Action:\s*([\s\S]*?)$/i.exec(text)?.[1] ?? "";
  return { thought, action: parseActionLine(actionLine) };
}

/**
 * 解析一行动作。导出成独立函数是为了能单测每一个动作类型。
 *
 * @param {string} line 例如 `click(point='<point>500 900</point>')`
 * @returns {{type: string, [key: string]: unknown} | null}
 */
export function parseActionLine(line) {
  const text = line.trim();
  const head = /^([a-z_]+)\s*\(/i.exec(text);
  if (!head) return null;
  const type = head[1].toLowerCase();
  const params = parseParams(text);

  switch (type) {
    case "click":
    case "long_press": {
      // **实际 checkpoint 用的不是文档模板的写法**。实测 UI-TARS-2B-SFT 会输出
      // `click(start_box='(611,899)')` 或 `click(start_box='<|box_start|>(x,y)<|box_end|>')`,
      // 而不是官方 prompt.py 里的 `click(point='<point>x y</point>')`。
      // 两种都要认 —— 只按文档写会在真机上表现为「解析不出动作」。
      const point = parsePoint(params.point) ?? parsePoint(params.start_box);
      return point ? { type, ...point } : null;
    }
    case "type": {
      // content 允许为空字符串（先聚焦输入框再输入）。
      return { type, content: unescape(params.content ?? "") };
    }
    case "scroll": {
      const point = parsePoint(params.point) ?? parsePoint(params.start_box);
      const direction = (params.direction ?? "").toLowerCase();
      if (!point || !["down", "up", "left", "right"].includes(direction)) return null;
      return { type, ...point, direction };
    }
    case "open_app": {
      const appName = unescape(params.app_name ?? "").trim();
      return appName ? { type, appName } : null;
    }
    case "drag": {
      const start = parsePoint(params.start_point) ?? parsePoint(params.start_box);
      const end = parsePoint(params.end_point) ?? parsePoint(params.end_box);
      return start && end ? { type, start, end } : null;
    }
    case "press_home":
    case "press_back":
      return { type };
    case "finished":
      return { type, content: unescape(params.content ?? "") };
    case "wait":
      return { type };
    default:
      return null;
  }
}

/** 抓 `key='value'` / `key=value`，值里允许出现括号与逗号（`finished` 的 content 很自由）。 */
function parseParams(text) {
  const out = {};
  const re = /([a-z_]+)\s*=\s*(?:'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"|([^,)\s]+))/gi;
  for (const m of text.matchAll(re)) {
    out[m[1].toLowerCase()] = m[2] ?? m[3] ?? m[4] ?? "";
  }
  return out;
}

/**
 * 把各种坐标写法统一成 `{ x, y }`（归一化 0–1000）。实际见过三种：
 *
 *   `<point>500 900</point>`                     官方 prompt.py 的写法
 *   `(611,899)`                                  实测 checkpoint 最常见的写法
 *   `<|box_start|>(x,y)<|box_end|>`              grounding 微调用的框格式
 *   `<|box_start|>(x1,y1,x2,y2)<|box_end|>`      四元组 → 取中心
 *
 * 认不出来就返回 null，**不要猜**：猜错的坐标会点到界面上的别处。
 */
function parsePoint(raw) {
  const text = (raw ?? "").replace(/<\|?box_start\|?>/gi, " ").replace(/<\|?box_end\|?>/gi, " ");
  const point = /<point>\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*<\/point>/i.exec(text);
  if (point) return { x: Number(point[1]), y: Number(point[2]) };
  const numbers = (text.match(/-?\d+(?:\.\d+)?/g) ?? []).map(Number);
  if (numbers.length === 2) return { x: numbers[0], y: numbers[1] };
  if (numbers.length === 4) {
    // 四元组是 (x1,y1,x2,y2)：取中心
    return { x: (numbers[0] + numbers[2]) / 2, y: (numbers[1] + numbers[3]) / 2 };
  }
  return null;
}

function unescape(value) {
  return value.replace(/\\n/g, "\n").replace(/\\'/g, "'").replace(/\\"/g, '"');
}

/**
 * 归一化坐标 → 屏幕像素。**夹到边界内**：模型偶尔吐 1001 或负数，
 * 点在屏幕外不会报错，只会「什么都没发生」，比夹取更难查。
 */
export function toScreenPoint(point, screen) {
  const clamp = (v, hi) => Math.min(Math.max(Math.round((v / NORMALIZED_MAX) * hi), 0), hi - 1);
  return { x: clamp(point.x, screen.width), y: clamp(point.y, screen.height) };
}

/**
 * UI-TARS 动作 → 要发往 `DeviceActionReceiver` 的广播参数。
 *
 * 不支持的动作如实返回 `{ unsupported: true }`，**不要静默映射成别的东西** ——
 * `drag` 尤其危险：Tappet 只有四向 `SWIPE`，把任意方向拖拽近似成某个方向会做出
 * 用户没要求的操作。
 *
 * @returns {{action: string, extras: Record<string,string|number>} | {unsupported: string}}
 */
export function toDeviceAction(action, screen) {
  switch (action?.type) {
    case "click": {
      const p = toScreenPoint(action, screen);
      return { action: "TAP_POINT", extras: { x: p.x, y: p.y } };
    }
    case "long_press": {
      const p = toScreenPoint(action, screen);
      return { action: "LONG_PRESS", extras: { x: p.x, y: p.y, duration_ms: 700 } };
    }
    case "type":
      return { action: "TYPE_TEXT", extras: { text: action.content } };
    case "scroll":
      return { action: "SWIPE", extras: { direction: action.direction.toUpperCase() } };
    case "press_home":
      return { action: "PRESS", extras: { key: "HOME" } };
    case "press_back":
      return { action: "PRESS", extras: { key: "BACK" } };
    case "open_app":
      return { action: "LAUNCH_APP", extras: { query: action.appName } };
    default:
      return { unsupported: action?.type ?? "unknown" };
  }
}

// ---------------------------------------------------------------------------
// GUI-Owl 1.5 的协议：标准 tool call，不是自由文本。
//
//   Action: <一句话>
//   <tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[x,y]}}</tool_call>
//
// llama-server 会把它解析成 OpenAI 的 `tool_calls`（实测 `--jinja` 下可用），
// 所以这一层只负责把 `arguments` 归一成和 UI-TARS 相同的内部形状 ——
// 之后 `toDeviceAction` 两条协议共用一份映射，不会各自漂移。
// ---------------------------------------------------------------------------

/** GUI-Owl 的系统提示词（官方 SYSTEM_PROMPT 的尾部）。2B 模型靠这段硬约束稳住输出格式。 */
export const GUI_OWL_SYSTEM_PROMPT = [
  "# Tools",
  "",
  "You may call one or more functions to assist with the user query.",
  "",
  "For each function call, return a json object with function name and arguments within",
  "<tool_call></tool_call> XML tags:",
  "<tool_call>",
  '{"name": <function-name>, "arguments": <args-json-object>}',
  "</tool_call>",
  "",
  "# Response format",
  "Response format for every step:",
  "1) Action: a short imperative describing what to do in the UI.",
  "2) A single <tool_call>...</tool_call> block containing only the JSON.",
  "Rules:",
  "- Output exactly in the order: Action, <tool_call>.",
  "- Be brief: one sentence for Action.",
  "- Do not output anything else outside those two parts.",
  "- If finishing, use action=terminate in the tool call.",
].join("\n");

/** GUI-Owl 的 `mobile_use` 工具定义（坐标是 1000x1000 网格）。 */
export const GUI_OWL_TOOLS = [
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
          coordinate: { type: "array", description: "(x, y) in the 1000x1000 grid." },
          coordinate2: { type: "array", description: "(x2, y2) for swipe." },
          text: { type: "string" },
          time: { type: "number" },
          button: { type: "string", enum: ["Back", "Home", "Menu", "Enter"] },
          status: { type: "string", enum: ["success", "failure"] },
        },
      },
    },
  },
];

/**
 * GUI-Owl 的 `arguments` → 与 UI-TARS 共用的内部动作形状。
 *
 * 认不出来或 Tappet 执行层做不了的，如实标 `unsupported` —— 与 `toDeviceAction` 同一条原则：
 * **不近似**。`key`（音量/电源等 keyevent）与 `interact`（要人介入）都没有对应的执行层动作。
 */
export function normalizeGuiOwl(argumentsJson) {
  let args = argumentsJson;
  if (typeof args === "string") {
    try {
      args = JSON.parse(args);
    } catch {
      return null;
    }
  }
  if (!args || typeof args !== "object") return null;

  const point = Array.isArray(args.coordinate)
    ? { x: Number(args.coordinate[0]), y: Number(args.coordinate[1]) }
    : null;

  switch (args.action) {
    case "click":
      return point && Number.isFinite(point.x) ? { type: "click", ...point } : null;
    case "long_press":
      return point && Number.isFinite(point.x) ? { type: "long_press", ...point } : null;
    case "type":
      return { type: "type", content: String(args.text ?? "") };
    case "open":
      return { type: "open_app", appName: String(args.text ?? "").trim() || null };
    case "swipe": {
      const to = Array.isArray(args.coordinate2)
        ? { x: Number(args.coordinate2[0]), y: Number(args.coordinate2[1]) }
        : null;
      return point && to ? { type: "drag", start: point, end: to } : null;
    }
    case "system_button": {
      const map = { Back: "press_back", Home: "press_home" };
      const mapped = map[args.button];
      return mapped ? { type: mapped } : { type: "unsupported", reason: `system_button:${args.button}` };
    }
    case "wait":
      return { type: "wait", seconds: Number(args.time ?? 1) };
    case "terminate":
    case "answer":
      return { type: "finished", content: String(args.text ?? args.status ?? "") };
    default:
      return { type: "unsupported", reason: `action:${args.action}` };
  }
}
