// dsh plugin: expose a device UI automation bridge as agent tools.
//
// Transport: POST {ANDROID_BRIDGE_URL}/action with an x-android-bridge-token
// header. The tools are app-agnostic atomic actions composed by the model;
// chat/business semantics stay in the caller; this plugin only exposes generic primitives.
import { defineTool } from '@deepseek-ai/dsh-tools'

export const name = 'dsh-android-tools'
// systemPrompt 用于把设备当前时间作为变量注入系统提示（见 registerDeviceClock）。
export const inject = ['tools', 'systemPrompt']

/**
 * 写工具：会在用户设备上留下真实数据（日历事件、闹钟），必须经 `tools/pre-execute` 判成
 * `ask` —— 手机确认面板是唯一闸门，因为这两条现在**都是静默的**，系统界面不兜底了。
 */
const WRITE_TOOLS = new Set([
  'add_calendar_event',
  'update_calendar_event',
  'delete_calendar_event',
  'set_alarm',
  'schedule_task',
  'update_scheduled_task',
  'delete_scheduled_task',
  // 真 shell 能写文件、装包、删东西 —— 按「写工具」处理，走同一条审批缝。
  // 逐条问还是全放行由 App 侧「设置 → 执行层 → 写操作审批」决定（answerer 回 allowed-once）。
  'run_command',
])

function scheduleLabel(args, update = false) {
  const clock = Number.isInteger(args?.hour) && Number.isInteger(args?.minute)
    ? `${String(args.hour).padStart(2, '0')}:${String(args.minute).padStart(2, '0')}` : '时间不变'
  const type = args?.schedule_type ?? (update ? null : 'daily')
  if (type === 'daily') return `每天 ${clock}`
  if (type === 'weekly' && Number.isInteger(args?.weekday)) return `每周 ${args.weekday} ${clock}`
  if (type === 'yearly' && Number.isInteger(args?.month) && Number.isInteger(args?.day_of_month)) return `每年 ${args.month} 月 ${args.day_of_month} 日 ${clock}`
  if (type === 'monthly' && Number.isInteger(args?.day_of_month)) return `每月 ${args.day_of_month} 号 ${clock}`
  if (type === 'once' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(args?.date ?? '')) return `${args.date} ${clock}（仅一次）`
  return update ? '更新排期（未提供的字段保持不变）' : '待校验排期'
}

const SCHEDULE_FIELDS = {
  schedule_type: { type: 'string', enum: ['daily', 'weekly', 'monthly', 'yearly', 'once'], description: 'daily (default on create), weekly, monthly, yearly or once; omit on update to keep' },
  weekday: { type: 'integer', description: 'weekly only: ISO weekday, Monday=1 through Sunday=7' },
  day_of_month: { type: 'integer', description: 'monthly/yearly: 1-31; months without this day are skipped, never clamped' },
  month: { type: 'integer', description: 'yearly only: month 1-12; also supply day_of_month; Feb 29 skips non-leap years' },
  date: { type: 'string', description: 'once only: future local calendar date YYYY-MM-DD, runs only once at hour:minute' },
}

/** 写操作在确认面板上显示什么（`reason` 会进审计日志，只留动作与时间，不带屏幕内容）。 */
function approvalFor(name, args) {
  if (name === 'set_alarm') {
    const hour = String(args?.hour ?? '').padStart(2, '0')
    const minute = String(args?.minute ?? '').padStart(2, '0')
    const label = String(args?.label ?? '').trim()
    return {
      reason: `设置闹钟 ${hour}:${minute}`,
      displayReason: { en: `Set alarm ${hour}:${minute}`, zh: `闹钟 ${hour}:${minute} ${label}`.trim() },
    }
  }
  if (name === 'run_command') {
    const cmd = String(args?.cmd ?? '').trim()
    const shown = cmd.length > 120 ? `${cmd.slice(0, 117)}...` : cmd
    return {
      // reason 会进会话审计：「只留动作，不留命令原文」—— 命令里可能带着路径、口令或文件内容。
      reason: '执行 shell 命令',
      // 面板上必须看得见要跑什么，否则「允许」没有意义。displayReason 只进 UI，不落盘。
      displayReason: { en: `Run shell command: ${shown}`, zh: `执行命令：${shown}` },
    }
  }
  if (name === 'schedule_task') {
    const label = scheduleLabel(args)
    const title = String(args?.title ?? '').trim() || '（无标题）'
    return {
      reason: `创建定时任务 ${label}`,
      displayReason: { en: `Create task "${title}": ${label}`, zh: `${label}「${title}」` },
    }
  }
  if (name === 'update_scheduled_task') {
    const label = scheduleLabel(args, true)
    return {
      reason: `修改定时任务 ${label}`,
      displayReason: { en: `Edit scheduled task #${args?.id ?? '?'}: ${label}`, zh: `修改定时任务 #${args?.id ?? '?'}：${label}` },
    }
  }
  if (name === 'delete_scheduled_task') {
    return {
      reason: `删除定时任务 #${args?.id ?? '?'}`,
      displayReason: { en: `Delete scheduled task #${args?.id ?? '?'}`, zh: `删除定时任务 #${args?.id ?? '?'}` },
    }
  }
  const title = String(args?.title ?? '').trim() || '（无标题）'
  if (name === 'delete_calendar_event') {
    return {
      reason: `删除日程 #${args?.event_id ?? '?'}`,
      displayReason: { en: `Delete event #${args?.event_id ?? '?'}`, zh: `删除日程 #${args?.event_id ?? '?'}` },
    }
  }
  if (name === 'update_calendar_event') {
    return {
      reason: `修改日程 #${args?.event_id ?? '?'}`,
      displayReason: { en: `Edit event #${args?.event_id ?? '?'}`, zh: `修改日程 #${args?.event_id ?? '?'}` },
    }
  }
  return {
    reason: `创建日程「${title}」`,
    displayReason: { en: `Create event "${title}"`, zh: `日程「${title}」` },
  }
}

/** 调桥并返回整个响应体（截图要拿 summary 之外的 payload）。 */
async function callResult(action, args = {}) {
  const baseUrl = process.env.ANDROID_BRIDGE_URL
  const token = process.env.ANDROID_BRIDGE_TOKEN
  if (!baseUrl || !token) throw new Error('ANDROID_BRIDGE_URL/ANDROID_BRIDGE_TOKEN not set')
  const response = await fetch(`${baseUrl}/action`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-android-bridge-token': token },
    body: JSON.stringify({ action, ...args }),
  })
  const body = await response.json()
  if (body.ok !== true) throw new Error(body.summary ?? `device action failed: ${action}`)
  return body
}

async function call(action, args = {}) {
  const body = await callResult(action, args)
  return body.payload ?? body.summary ?? 'ok'
}

/** 解析截图 payload（capture_screenshot 的产物）；不是契约形状就回 undefined。 */
function parseFrame(payload) {
  if (typeof payload !== 'string') return undefined
  let parsed
  try {
    parsed = JSON.parse(payload)
  } catch {
    return undefined
  }
  if (typeof parsed?.base64 !== 'string' || typeof parsed?.mediaType !== 'string') return undefined
  // screenshotId 是坐标的锚点：没有它，图能用但 tap_target 无从下手，按「不是契约形状」处理。
  if (typeof parsed?.screenshotId !== 'string' || parsed.screenshotId === '') return undefined
  return parsed
}

/**
 * 给模型看的截图图例。
 *
 * **模型只看得见工具结果里的文本** —— payload 里的尺寸它读不到。所以「图多大、屏多大、
 * 坐标该用哪一套」必须写进这段文字：少了它，模型只能拿图像像素当屏幕像素用（实测会偏 12.6%）。
 */
function frameLegend(frame) {
  const { screenshotId, width, height, screenWidth, screenHeight } = frame
  const screen = screenWidth && screenHeight ? ` | screen ${screenWidth}x${screenHeight} px` : ''
  return (
    `screenshot ${screenshotId} | image ${width}x${height} px${screen}\n`
    + `Coordinates always use **this screenshot's image pixels**: top-left (0,0), bottom-right (${width},${height}). `
    + `tap_target / long_press_target must pass screenshot_id="${screenshotId}". `
    + 'This screenshot becomes stale when the screen changes — call capture_screenshot again.'
  )
}

function deviceTool(options) {
  return defineTool({
    name: options.name,
    description: options.description,
    parameters: options.parameters,
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      return call(options.action, args)
    },
  })
}

/**
 * 把桥的返回渲染成给模型看的文本。
 *
 * 退出码一定要显式写出来：模型很容易把「有输出」当成「成功了」，而 `grep` 无匹配、
 * `git diff` 有差异、编译失败都可能带输出 —— 不写退出码它就只能猜。
 */
/**
 * 手机上的 Linux shell（**内嵌 proot + Debian**，与其它设备动作走同一个本地桥）。
 *
 * 与其它工具的根本区别：那些是「操作手机界面」，可逆、看得见；这个是**真 shell** ——
 * 它改的是文件系统，没有撤销，也不在屏幕上。所以描述里必须把边界和纪律写清楚：
 * 模型看不到 shell 的现状，只能靠这段文字知道「这里能做什么、什么会留下后果」。
 */
function shellTool(baseUrl, token) {
  return defineTool({
    name: 'run_command',
    description:
      'Run a shell command inside the phone\'s Linux (Debian, built into the app) and return stdout / stderr / exit code. '
      + 'This is a **real shell on a real filesystem**, not a UI action: git / python / apt / npm / compilers all work, '
      + 'but a wrong write cannot be undone and there is no on-screen warning. '
      + 'Boundaries: every command is its own session — cd, environment variables and background processes are not kept; '
      + 'for a long-running service, start it yourself with `nohup ... > log 2>&1 &` and remember the log path. '
      + 'No docker / systemd / iptables (proot is not a real kernel). Output is capped at 16 KiB per stream (head and tail are kept). '
      + 'Discipline: read-only commands (ls / cat / grep / git status / --version) may run directly; '
      + 'for anything that writes, deletes, moves, installs or changes configuration, state what you are about to do and its impact first.',
    parameters: {
      cmd: { type: 'string', required: true, description: 'Full shell command to run (pipes and redirections allowed)' },
      timeout_ms: { type: 'integer', description: 'Timeout in milliseconds (default 60000, max 600000); on timeout the whole process group is killed' },
    },
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute(args) {
      // 客户端也要有超时：桥那边超时会杀命令，但万一桥本身卡住（进程被冻结、端口没人应答），
      // 没有 abort 就是这一轮对话永远不返回。
      const timeoutMs = Math.min(Number(args.timeout_ms) || 60000, 600000)
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), timeoutMs + 15000)
      let response
      try {
        response = await fetch(`${baseUrl}/action`, {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-android-bridge-token': token },
          body: JSON.stringify({ action: 'run_command', cmd: args.cmd, timeout_ms: timeoutMs }),
          signal: controller.signal,
        })
      } catch (error) {
        throw new Error(
          `Cannot reach the device bridge (${baseUrl}): ${error.message}. `
          + 'The app is probably not running — do not retry blindly; tell the user instead.',
        )
      } finally {
        clearTimeout(timer)
      }
      const body = await response.json().catch(() => null)
      if (!body) throw new Error(`device bridge returned ${response.status}`)
      // 命令退出码非 0 也是 `ok:false`，但**不能当工具错误吞掉** —— summary 里排好了
      // `$ cmd` / exit / stdout / stderr，模型正是靠那个退出码判断下一步。
      // 只有「Linux 环境根本没起来」才抛：重试同一条命令不会成功。
      if (body.ok === true) return body.payload ?? body.summary ?? 'ok'
      if (body.code === 'SHELL_UNAVAILABLE') throw new Error(body.summary)
      return body.summary ?? `exit=${body.code ?? 'unknown'}`
    },
  })
}

/**
 * 把设备当前时间变成系统提示里的变量 `device_now`。
 *
 * 模型没有时钟：`add_calendar_event` / `set_alarm` 要绝对时间，而「明天下午三点」得先知道今天
 * 几号。实测没有时间源时它会先 read_ui_tree、再 launch_app(Clock)、点进世界时钟去读日期 ——
 * 四次工具调用全花在找一个宿主本来就该给的事实上。
 *
 * 为什么是**变量**而不是 section：手机 preset 的 persona 是 `complete: true`，组装时只有它能
 * 进系统提示，额外的 section 会被丢掉；变量是在渲染阶段插值的，不受这条限制。
 */
function registerDeviceClock(ctx) {
  // 没有 systemPrompt 服务的宿主（单测、只挂 tools 的部署）跳过：少一行时间比整个插件挂不上好。
  if (typeof ctx.systemPrompt?.variable !== 'function') return
  ctx.systemPrompt.variable('device_now', () => formatDeviceNow(new Date()))
}

/**
 * `2026-10-25 02:17 周日（UTC+08:00）`。
 *
 * 偏移直接由 `getTimezoneOffset()` 算，不读 `Intl` 的时区数据库 —— Android 上的 Node 未必带
 * 完整 ICU，读错时区比没有时区更糟。星期也写出来：模型自己推星期几推错过。
 */
export function formatDeviceNow(now) {
  const pad = (n) => String(n).padStart(2, '0')
  const offsetMin = -now.getTimezoneOffset()
  const abs = Math.abs(offsetMin)
  const offset = `UTC${offsetMin >= 0 ? '+' : '-'}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`
  const weekday = '日一二三四五六'[now.getDay()]
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} `
    + `${pad(now.getHours())}:${pad(now.getMinutes())} 周${weekday}（${offset}）`
}

export function apply(ctx) {
  registerDeviceClock(ctx)
  // **写操作的唯一闸门**：在插件层（不是模型侧）把写工具判成 `ask`。
  // dsh 的 ApprovalService 把它交给手机 answerer → 确认面板；只有 allowed-once 才执行。
  // 读工具与界面动作走 next()，不打扰用户。
  ctx.on('tools/pre-execute', (exec, next) => {
    if (!WRITE_TOOLS.has(exec?.name)) return next()
    return { kind: 'ask', ...approvalFor(exec.name, exec.arguments) }
  })
  ctx.tools.register(
    deviceTool({
      name: 'read_ui_tree',
      action: 'read_ui_tree',
      description:
        'Read the current screen\'s accessibility tree. Returns lines like "[12] TextView \\"Send\\" clickable @(900,2200)"; '
        + 'the leading [n] is the node ref used by tap_node. Read before acting. '
        + '**You do not need to re-read after every action on the same screen** — after tapping a button you may tap the next one directly '
        + '(refs stay valid until the screen changes). Read again only when the screen navigates, or to confirm the final result. '
        + 'Consecutive reads on the same screen return only the diff: [+n] added, [~n] changed (text/position/state), [-n] gone; '
        + 'unchanged nodes are not resent and keep their refs; if nothing changed you get a single "unchanged" line. '
        + 'If you can no longer see the previous full output (context truncated), pass full=true to force a full dump. '
        + '**A default read is enough to confirm a result** — the diff marks the changed lines; do not pass full=true just to confirm. '
        + 'If the tree has no readable nodes (WeChat 8.0.78 hides it from accessibility), this path is blocked: '
        + 'use capture_screenshot and tap_target with image-pixel coordinates instead — do not keep retrying tap_node.',
      parameters: {
        full: { type: 'boolean', description: 'Force a full dump (default false = incremental)' },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'tap_node',
      action: 'tap_node',
      description: 'Tap node [ref] from the last read_ui_tree output (gesture at the node center).',
      parameters: {
        ref: { type: 'integer', required: true, description: 'Node ref shown at the start of a read_ui_tree line' },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'tap_target',
      action: 'tap_target',
      description:
        'Tap a target you saw in the latest capture_screenshot. Give its bounding box (x1,y1)-(x2,y2) in '
        + '**that screenshot\'s image pixels**; the executor takes the center and converts it to screen pixels. '
        + 'Do not convert coordinates yourself. The result reports whether the screen changed: '
        + 'no change usually means you missed the target — take a new screenshot and pass the box again.',
      parameters: {
        screenshot_id: { type: 'string', required: true, description: 'screenshot_id returned by capture_screenshot' },
        x1: { type: 'integer', required: true, description: 'Left edge of the box (image pixels)' },
        y1: { type: 'integer', required: true, description: 'Top edge of the box (image pixels)' },
        x2: { type: 'integer', required: true, description: 'Right edge of the box (image pixels)' },
        y2: { type: 'integer', required: true, description: 'Bottom edge of the box (image pixels)' },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'long_press_target',
      action: 'long_press_target',
      description:
        'Long-press a target from the latest capture_screenshot. Same coordinate rules as tap_target '
        + '(bbox in that screenshot\'s image pixels). Default duration 700 ms (300-2000 ms).',
      parameters: {
        screenshot_id: { type: 'string', required: true, description: 'screenshot_id returned by capture_screenshot' },
        x1: { type: 'integer', required: true, description: 'Left edge of the box (image pixels)' },
        y1: { type: 'integer', required: true, description: 'Top edge of the box (image pixels)' },
        x2: { type: 'integer', required: true, description: 'Right edge of the box (image pixels)' },
        y2: { type: 'integer', required: true, description: 'Bottom edge of the box (image pixels)' },
        duration_ms: { type: 'integer', description: 'Long-press duration in milliseconds (300-2000, default 700)' },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'type_text',
      action: 'type_text',
      description:
        'Write text into the currently focused (or first visible) input field. It only types — '
        + 'to send a message, tap the Send button afterwards.',
      parameters: {
        text: { type: 'string', required: true, description: 'Full text to enter' },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'long_press',
      action: 'long_press',
      description:
        'Long-press node [ref] from the last read_ui_tree output (gesture at the node center). '
        + 'Default 700 ms (300-2000 ms). Read the screen afterwards to confirm a menu or selection state. '
        + 'Use long_press_target when the tree is unreadable.',
      parameters: {
        ref: { type: 'integer', required: true, description: 'Node ref shown at the start of a read_ui_tree line' },
        duration_ms: { type: 'integer', description: 'Long-press duration in milliseconds (300-2000, default 700)' },
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'capture_screenshot',
      description:
        'Capture the current screen and hand the model an image plus its frame info '
        + '(image size / screen size / screenshot_id). Use it when read_ui_tree returns 0-1 lines, '
        + 'or when you need to see icons, images or layout detail. Find the target on the image and pass its bbox '
        + '**in that screenshot\'s image pixels** to tap_target — do not convert coordinates yourself. '
        + 'Images consume the request budget: prefer read_ui_tree and capture only when the tree has nothing useful.',
      parameters: {},
      output: {
        // dsh 的 value schema DSL 要求对象根显式写死 additionalProperties 的开闭，
        // 漏了会直接抛错 —— 而 defineTool 抛错会让整个插件的工具都注册不上。
        schema: { type: 'object', additionalProperties: true },
        render: (_args, value) => {
          const blocks = [{ type: 'text', text: value.summary }]
          if (value.attachment) blocks.push({ type: 'image', attachment: value.attachment })
          return blocks
        },
      },
      async execute() {
        const body = await callResult('capture_screenshot')
        // 宿主没按契约回截图字段时退回纯文字：能截屏但交不了尺寸信息的宿主（如 mock）
        // 不该让这个工具直接抛错 —— 只是模型拿不到图，也就无从给坐标。
        const frame = parseFrame(body.payload)
        if (!frame) {
          return { summary: `${body.summary ?? 'Screenshot taken'} (the host did not return frame data, so the image could not be given to the model)` }
        }
        const legend = frameLegend(frame)
        // 附件服务由运行时挂载（dsh-android-attachments）；没有它就只能退回文字。
        const attachments = typeof ctx.get === 'function' ? ctx.get('attachments') : undefined
        if (!attachments) {
          return { summary: `${legend}
(the attachments service is not mounted in this runtime, so the image could not be given to the model)` }
        }
        const attachment = await attachments.saveImage({
          data: Buffer.from(frame.base64, 'base64'),
          mediaType: frame.mediaType,
          name: `frame-${frame.width}x${frame.height}`,
        })
        return { summary: legend, attachment, screenshot_id: frame.screenshotId }
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'press',
      action: 'press',
      description:
        'Press a system key: back = go back / dismiss the keyboard / cancel; home = go to the launcher; '
        + 'recents = recent tasks; enter = trigger send or search in the focused field. '
        + 'Android has no separate Esc key — use back to cancel or exit.',
      parameters: {
        key: {
          type: 'string',
          required: true,
          enum: ['back', 'home', 'recents', 'enter'],
          description: 'System key to press',
        },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'launch_app',
      action: 'launch_app',
      description: 'Launch an app by fuzzy name or package name and bring it to the foreground, e.g. launch_app("Clock").',
      parameters: {
        query: { type: 'string', required: true, description: 'App name or package name, e.g. "Clock" / "com.tencent.mobileqq"' },
      },
    }),
  )
  ctx.tools.register(
    deviceTool({
      name: 'swipe',
      action: 'swipe',
      description:
        'Swipe about half a screen in the given direction, for scrolling lists or paging. '
        + 'The result says whether the screen changed; "seems unchanged" usually means the list is already at the end '
        + '(or the gesture was swallowed by the system). You do not need to read the screen after every swipe — '
        + 'read when you need the new content or the final position.',
      parameters: {
        direction: {
          type: 'string',
          required: true,
          enum: ['up', 'down', 'left', 'right'],
          description: 'Swipe direction: up = scroll content up (finger moves up), down = scroll content down, same for left/right',
        },
      },
    }),
  )

  // --- 定时任务：语义化创建 ---
  // 设置页不再有定时任务入口 —— 模型从对话里拆出时间与指令，直接落库并排 AlarmManager。
  // 写操作（增 / 改 / 删）都由上面的 tools/pre-execute 闸门判成 ask。
  ctx.tools.register(
    defineTool({
      name: 'schedule_task',
      description:
        'Schedule an agent task in the device local timezone: daily (default), weekly, monthly, yearly or once. '
        + 'Parse natural language into hour/minute and schedule_type. "每周三9点" → weekly, weekday=3, hour=9, minute=0; '
        + '"每月15号18点" → monthly, day_of_month=15, hour=18, minute=0; '
        + '"2027年1月5日10点" → once, date="2027-01-05", hour=10, minute=0. '
        + '"每年6月1日9点" → yearly, month=6, day_of_month=1, hour=9, minute=0. '
        + 'Only provide weekday for weekly, day_of_month for monthly, month+day_of_month for yearly, date for once; daily takes none. '
        + 'Yearly February 29 skips non-leap years. '
        + 'Monthly missing days are skipped (31 never becomes the last day). A once task disables itself when claimed, '
        + 'even if execution fails; past dates are rejected, missed once tasks are not caught up after restart. '
        + 'Use device_now for relative dates, ask when a required date/time is ambiguous; never silently create daily instead. '
        + 'Exact-alarm permission and ROM restrictions affect punctuality. The same tool permissions and write approvals apply. '
        + 'Keep the returned task id for update/delete.',
      parameters: {
        title: { type: 'string', required: true, description: 'Short title, shown when listing tasks' },
        prompt: { type: 'string', required: true, description: 'Instruction the agent runs at that time' },
        hour: { type: 'integer', required: true, description: 'Hour, 0-23 (24-hour clock)' },
        minute: { type: 'integer', required: true, description: 'Minute, 0-59' },
        ...SCHEDULE_FIELDS,
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        return call('schedule_task', args)
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'list_scheduled_tasks',
      description:
        'List scheduled tasks (read-only): id, title, prompt, hour, minute, schedule_type, weekday/day_of_month/date, schedule, timezone, enabled, next_trigger (null when disabled/completed), '
        + 'last_run_at, last_error. **Always call this before update/delete** to get the real id — never guess by title. '
        + 'Task output goes to the conversation archive, not into this list; last_error is a code such as TURN_FAILED.',
      parameters: {},
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute() {
        return call('list_scheduled_tasks', {})
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'update_scheduled_task',
      description:
        'Change or pause/resume a scheduled task in place. Only the fields you pass change; pass enabled=false to pause '
        + '(it stops firing) and enabled=true to resume. id comes from list_scheduled_tasks. '
        + 'Changing schedule_type clears previous rule fields: supply the new rule parameters. Omitted fields otherwise stay unchanged. '
        + 'A completed once task needs a future date/time before resuming; past dates are rejected.',
      parameters: {
        id: { type: 'string', required: true, description: 'Task id from list_scheduled_tasks' },
        title: { type: 'string', description: 'New title (omit to keep)' },
        prompt: { type: 'string', description: 'New instruction (omit to keep)' },
        hour: { type: 'integer', description: 'New hour, 0-23 (omit to keep)' },
        minute: { type: 'integer', description: 'New minute, 0-59 (omit to keep)' },
        enabled: { type: 'boolean', description: 'false = pause, true = resume (omit to keep)' },
        ...SCHEDULE_FIELDS,
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        const payload = { id: args.id }
        for (const key of ['title', 'prompt', 'hour', 'minute', 'enabled', 'schedule_type', 'weekday', 'day_of_month', 'date', 'month']) {
          if (args[key] !== undefined) payload[key] = args[key]
        }
        return call('update_scheduled_task', payload)
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'delete_scheduled_task',
      description:
        'Delete a scheduled task by id — it stops firing and is removed. Get the id from list_scheduled_tasks and '
        + 'confirm with the user before deleting when the request is not explicit.',
      parameters: {
        id: { type: 'string', required: true, description: 'Task id from list_scheduled_tasks' },
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        return call('delete_scheduled_task', { id: args.id })
      },
    }),
  )

  // --- 内嵌 Linux（proot + Debian）的 shell ---
  // 宿主注入 FACTOTUM_PROOT=1 才注册：没有运行时的 App 版本注册了也只会每轮白占 schema。
  if (process.env.FACTOTUM_PROOT === '1') {
    ctx.tools.register(shellTool(process.env.ANDROID_BRIDGE_URL, process.env.ANDROID_BRIDGE_TOKEN))
  }
  // --- 系统 App 动作：不经过无障碍 ---
  // 这两个由 App 侧直接交给系统 App（SystemIntents），**不依赖无障碍** ——
  // 确认留在时钟 / 日历里，插件只负责把参数递过去。
  ctx.tools.register(
    defineTool({
      name: 'set_alarm',
      description:
        'Silently set an alarm in the system Clock app (no confirmation screen, no user tap). '
        + 'The result also posts a notification / live-activity card on the device. '
        + 'If the device clock ignores the silent flag it may fall back to a confirmation screen — '
        + 'read the clock screen afterwards only when the user needs certainty.',
      parameters: {
        hour: { type: 'integer', required: true, description: 'Hour, 0-23 (24-hour clock)' },
        minute: { type: 'integer', required: true, description: 'Minute, 0-59' },
        label: { type: 'string', description: 'Alarm label, e.g. "Wake up"' },
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        return call('set_alarm', args)
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'add_calendar_event',
      description:
        'Create a calendar event **directly in the system calendar** — no editor, no user tap, no app switch. '
        + 'The result also posts a notification / live-activity card on the device. '
        + 'Times use ISO 8601 (e.g. 2026-10-20T15:00); without a timezone the device local timezone is used. '
        + 'A reminder is set `reminder_minutes` before the start (default 15; pass 0 for none). '
        + 'Needs the calendar permission: when it is missing the call returns CALENDAR_PERMISSION_REQUIRED and the '
        + 'app shows a one-tap grant prompt — tell the user, then call again after they grant. Do not retry before that.',
      parameters: {
        title: { type: 'string', required: true, description: 'Event title' },
        begin: { type: 'string', required: true, description: 'Start time, ISO 8601, e.g. 2026-10-20T15:00' },
        end: { type: 'string', required: true, description: 'End time, ISO 8601' },
        location: { type: 'string', description: 'Location' },
        description: { type: 'string', description: 'Notes' },
        reminder_minutes: {
          type: 'integer',
          description: 'Minutes before the start to remind (default 15, 0 = no reminder)',
        },
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        // 模型给的是人读的时间，桥要的是 epoch 毫秒 —— 转换放在这里，别让模型自己算。
        const begin = Date.parse(args.begin)
        const end = Date.parse(args.end)
        if (Number.isNaN(begin) || Number.isNaN(end)) {
          throw new Error('Invalid time format: use ISO 8601, e.g. 2026-10-20T15:00')
        }
        if (end <= begin) throw new Error('End time must be after the start time')
        const reminder = args.reminder_minutes ?? 15
        return call('add_calendar_event', {
          title: args.title,
          begin,
          end,
          location: args.location,
          description: args.description,
          // 0 = 不要提醒。桥把缺省当成「不设」，所以这里只在 > 0 时带这个字段。
          ...(reminder > 0 ? { reminder_minutes: reminder } : {}),
        })
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'list_calendar_events',
      description:
        'List calendar events in a time window (read-only). One line per event, e.g. '
        + '"[123] 理发 · 2026-10-26 15:00–16:00 · 楼下"; the leading [n] is the event_id used by '
        + 'update_calendar_event / delete_calendar_event. '
        + '**Always use this before editing or deleting** — never hunt for the event in the Calendar UI: '
        + 'UI edits are slow, ambiguous and were measured at 27 taps for a single change. '
        + 'Window times are ISO 8601; at most 50 events are returned.',
      parameters: {
        begin: { type: 'string', required: true, description: 'Window start, ISO 8601' },
        end: { type: 'string', required: true, description: 'Window end, ISO 8601' },
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        const begin = Date.parse(args.begin)
        const end = Date.parse(args.end)
        if (Number.isNaN(begin) || Number.isNaN(end)) {
          throw new Error('Invalid time format: use ISO 8601, e.g. 2026-10-26T00:00')
        }
        if (end <= begin) throw new Error('Window end must be after the start')
        return call('list_calendar_events', { begin, end })
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'update_calendar_event',
      description:
        'Update an existing calendar event **in place** — no editor, no user tap, no app switch. '
        + 'Only the fields you pass change; omit a field to keep its current value. '
        + 'Pass reminder_minutes to replace the reminder (0 removes it). '
        + 'event_id comes from list_calendar_events. **Do not edit events through the Calendar UI.**',
      parameters: {
        event_id: { type: 'integer', required: true, description: 'event_id from list_calendar_events' },
        title: { type: 'string', description: 'New title (omit to keep)' },
        begin: { type: 'string', description: 'New start, ISO 8601 (omit to keep)' },
        end: { type: 'string', description: 'New end, ISO 8601 (omit to keep)' },
        location: { type: 'string', description: 'New location (omit to keep)' },
        description: { type: 'string', description: 'New notes (omit to keep)' },
        reminder_minutes: {
          type: 'integer',
          description: 'Replace the reminder with this many minutes before the start (0 = none)',
        },
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        const payload = { event_id: args.event_id }
        for (const [key, value] of [
          ['title', args.title],
          ['location', args.location],
          ['description', args.description],
        ]) {
          if (value !== undefined) payload[key] = value
        }
        for (const key of ['begin', 'end']) {
          if (args[key] === undefined) continue
          const parsed = Date.parse(args[key])
          if (Number.isNaN(parsed)) throw new Error(`Invalid ${key} time: use ISO 8601, e.g. 2026-10-26T15:00`)
          payload[key] = parsed
        }
        if (args.reminder_minutes !== undefined) payload.reminder_minutes = args.reminder_minutes
        return call('update_calendar_event', payload)
      },
    }),
  )
  ctx.tools.register(
    defineTool({
      name: 'delete_calendar_event',
      description:
        'Delete a calendar event by event_id — no UI, no confirmation screen. Get the id from '
        + 'list_calendar_events and confirm the title with the user before calling when the request is not explicit. '
        + 'The event is removed, not archived.',
      parameters: {
        event_id: { type: 'integer', required: true, description: 'event_id from list_calendar_events' },
      },
      output: { schema: { type: 'string' }, render: (_args, value) => [{ type: 'text', text: value }] },
      async execute(args) {
        return call('delete_calendar_event', { event_id: args.event_id })
      },
    }),
  )
}
