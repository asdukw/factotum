// UI-TARS 动作解析与映射的行为测试。
//
// 这一层是**唯一**把 UI-TARS 的原生动作翻译成 Tappet 执行层动作的地方。它错的后果很隐蔽：
// 正则少匹配一个字符，模型照样输出得好好的，执行层却「点了屏幕中间」或者「什么都没点」——
// 在设备上看起来像模型不准，实际上是翻译坏了。所以每个动作类型都要钉住。
//
// 另外钉住两条刻意的取舍：
//   - 坐标归一化 → 像素要**夹取**（模型偶尔吐 1001 或负数，点屏幕外不会报错）；
//   - 不支持的动作**如实报 unsupported**，绝不近似成别的动作（`drag` 近似成 `SWIPE`
//     会做出用户没要求的操作）。
import assert from 'node:assert/strict'
import { parseAction, parseActionLine, toScreenPoint, toDeviceAction } from '../adapt.mjs'

const screen = { width: 1216, height: 2640 }

// --- 解析 ---

{
  const { thought, action } = parseAction(
    "Thought: 需要点开设置。\nAction: click(point='<point>500 900</point>')",
  )
  assert.equal(thought, '需要点开设置。')
  assert.deepEqual(action, { type: 'click', x: 500, y: 900 })
}

{
  // Thought 里提到动作名是常态，解析必须锚在 `Action:` 上
  const { action } = parseAction('Thought: I will click the settings icon\nAction: press_back()')
  assert.deepEqual(action, { type: 'press_back' })
}

{
  assert.deepEqual(parseActionLine("long_press(point='<point>10 20</point>')"), {
    type: 'long_press',
    x: 10,
    y: 20,
  })
  assert.deepEqual(parseActionLine("scroll(point='<point>500 500</point>', direction='down')"), {
    type: 'scroll',
    x: 500,
    y: 500,
    direction: 'down',
  })
  assert.deepEqual(parseActionLine("open_app(app_name='设置')"), { type: 'open_app', appName: '设置' })
  assert.deepEqual(parseActionLine("type(content='hello')"), { type: 'type', content: 'hello' })
  assert.deepEqual(parseActionLine("type(content='')"), { type: 'type', content: '' })
  assert.deepEqual(parseActionLine("finished(content='done')"), { type: 'finished', content: 'done' })
  assert.deepEqual(parseActionLine('press_home()'), { type: 'press_home' })
  assert.deepEqual(parseActionLine('press_back()'), { type: 'press_back' })
}

{
  // **实测 checkpoint 的写法与官方模板不一致**，三种都要认。
  // 只按文档写会在真机上表现为「解析不出动作」—— 第一次跑真任务就是这么失败的。
  assert.deepEqual(parseActionLine("click(start_box='(611,899)')"), { type: 'click', x: 611, y: 899 })
  assert.deepEqual(parseActionLine("click(start_box='<|box_start|>(611,899)<|box_end|>')"), {
    type: 'click',
    x: 611,
    y: 899,
  })
  // 四元组取中心
  assert.deepEqual(parseActionLine("click(start_box='<|box_start|>(100,200,300,400)<|box_end|>')"), {
    type: 'click',
    x: 200,
    y: 300,
  })
  // 文档那套 `<point>` 写法仍然要认
  assert.deepEqual(parseActionLine("click(point='<point>500 900</point>')"), {
    type: 'click',
    x: 500,
    y: 900,
  })
}

{
  // drag 能解析出来（要报给调用方），但**不会被映射成任何执行层动作**，见下面的用例
  assert.deepEqual(
    parseActionLine("drag(start_point='<point>100 200</point>', end_point='<point>300 400</point>')"),
    { type: 'drag', start: { x: 100, y: 200 }, end: { x: 300, y: 400 } },
  )
}

{
  // 转义：官方模板要求 content 里用 \\n、\\'、\\"
  assert.deepEqual(parseActionLine("type(content='a\\nb')"), { type: 'type', content: 'a\nb' })
}

{
  // 不成形的输出一律 null，不要猜
  assert.equal(parseActionLine(''), null)
  assert.equal(parseActionLine('I think we should click it'), null)
  assert.equal(parseActionLine("click(point='<point>not_a_number</point>')"), null)
  assert.equal(parseActionLine("scroll(point='<point>1 2</point>', direction='sideways')"), null)
  assert.deepEqual(parseAction(null), { thought: '', action: null })
  assert.equal(parseAction('随便一段话').action, null)
}

// --- 坐标 ---

{
  // 归一化 0–1000 → 像素
  assert.deepEqual(toScreenPoint({ x: 0, y: 0 }, screen), { x: 0, y: 0 })
  assert.deepEqual(toScreenPoint({ x: 1000, y: 1000 }, screen), { x: 1215, y: 2639 })
  assert.deepEqual(toScreenPoint({ x: 500, y: 500 }, screen), { x: 608, y: 1320 })
}

{
  // 越界要夹住：点在屏幕外不会报错，只会「什么都没发生」，比夹取更难查
  assert.deepEqual(toScreenPoint({ x: 1200, y: -50 }, screen), { x: 1215, y: 0 })
}

// --- 映射 ---

{
  assert.deepEqual(toDeviceAction({ type: 'click', x: 500, y: 500 }, screen), {
    action: 'TAP_POINT',
    extras: { x: 608, y: 1320 },
  })
  assert.deepEqual(toDeviceAction({ type: 'press_back' }, screen), {
    action: 'PRESS',
    extras: { key: 'BACK' },
  })
  assert.deepEqual(toDeviceAction({ type: 'press_home' }, screen), {
    action: 'PRESS',
    extras: { key: 'HOME' },
  })
  assert.deepEqual(toDeviceAction({ type: 'scroll', x: 1, y: 1, direction: 'down' }, screen), {
    action: 'SWIPE',
    extras: { direction: 'DOWN' },
  })
  assert.deepEqual(toDeviceAction({ type: 'open_app', appName: '设置' }, screen), {
    action: 'LAUNCH_APP',
    extras: { query: '设置' },
  })
  assert.deepEqual(toDeviceAction({ type: 'type', content: 'hi' }, screen), {
    action: 'TYPE_TEXT',
    extras: { text: 'hi' },
  })
}

{
  // 不支持的动作必须如实报，不能近似
  assert.deepEqual(
    toDeviceAction({ type: 'drag', start: { x: 1, y: 1 }, end: { x: 2, y: 2 } }, screen),
    { unsupported: 'drag' },
  )
  assert.deepEqual(toDeviceAction({ type: 'finished', content: '' }, screen), { unsupported: 'finished' })
  assert.deepEqual(toDeviceAction(null, screen), { unsupported: 'unknown' })
}

console.log('uitars adapt: ok')
