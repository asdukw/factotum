// 用**真实的** @deepseek-ai/dsh-tools 跑一遍工具定义。
//
// 为什么单靠 smoke.mjs 不够：smoke 用 stub 顶掉了 defineTool，于是工具 schema 的
// 作者侧校验完全没跑。而 dsh 的 value schema DSL 很挑（例如对象根必须显式写
// additionalProperties），defineTool 一抛错，**整个插件注册的工具都会没了** ——
// 实测踩过一次：模型侧看到 0 个工具，只能对着 persona 猜工具名。
//
// 为了让「测的永远是当前源码」，这里自建一个沙箱：把源码拷进去，再把真实的
// @deepseek-ai/* 接进去。
//
// 真实的 dsh-tools 从两处找：本仓库的 node_modules（装了 peerDependency 的话），
// 或 memex 里的运行时缓存（android/.dsh-cache/runtime，先跑 tools/bundle-dsh.ps1）。
// 两处都没有就**跳过**并说明原因 —— 这个仓库刻意保持零依赖，CI 里没有 dsh 运行时，
// 不该因为跑不了这条检查而变红；校验在有运行时的环境（memex 本地）照常生效。
import assert from 'node:assert/strict'
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, rmSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const pluginDir = fileURLToPath(new URL('..', import.meta.url))
const repoRoot = fileURLToPath(new URL('../../../../../', import.meta.url))

/** 找一处能解析到真实 @deepseek-ai/dsh-tools 的 vendor 目录。 */
function findVendor() {
  const candidates = [
    join(pluginDir, 'node_modules', '@deepseek-ai'),
    join(repoRoot, 'android', '.dsh-cache', 'runtime', 'node_modules', '@deepseek-ai'),
  ]
  return candidates.find((dir) => existsSync(join(dir, 'dsh-tools', 'package.json')))
}

const vendor = findVendor()
if (vendor === undefined) {
  console.log('skip: 找不到真实的 @deepseek-ai/dsh-tools（这个仓库零依赖，先跑 tools/bundle-dsh.ps1）')
  process.exit(0)
}

const sandbox = mkdtempSync(join(tmpdir(), 'memex-plugin-'))
try {
  const pkgDir = join(sandbox, 'node_modules', 'dsh-plugin-android-tools')
  mkdirSync(pkgDir, { recursive: true })
  copyFileSync(join(pluginDir, 'index.mjs'), join(pkgDir, 'index.mjs'))
  copyFileSync(join(pluginDir, 'package.json'), join(pkgDir, 'package.json'))
  symlinkSync(vendor, join(sandbox, 'node_modules', '@deepseek-ai'), 'junction')

  const plugin = await import(pathToFileURL(join(pkgDir, 'index.mjs')).href)
  assert.equal(plugin.name, 'dsh-android-tools')

  const registered = []
  // 真实 defineTool 在 apply 里逐個编译 schema；抛错就说明工具集会被整锅端掉。
  plugin.apply({ on: () => {}, tools: { register: (tool) => registered.push(tool) } })
  assert.equal(registered.length, 19, '19 个工具都要注册成功')

  for (const tool of registered) {
    assert.equal(typeof tool.output.render, 'function', `${tool.name} 要有 render`)
    assert.ok(tool.output.schema?.type, `${tool.name} 的 output schema 要编译出 type`)
    assert.equal(typeof tool.execute, 'function', `${tool.name} 要有 execute`)
  }

  const shot = registered.find((tool) => tool.name === 'capture_screenshot')
  assert.equal(shot.output.schema.type, 'object')
  assert.equal(shot.output.schema.additionalProperties, true, '对象根必须显式开闭')

  // 视觉路径的两个动作必须带 screenshot_id 与图像像素 bbox —— 少一个参数模型就没法给坐标。
  // 注意 parameters 已被编译成 JSON Schema（properties / required），不是原始的声明对象。
  for (const name of ['tap_target', 'long_press_target']) {
    const tool = registered.find((entry) => entry.name === name)
    assert.equal(tool.output.schema.type, 'string', `${name} 回字符串`)
    for (const key of ['screenshot_id', 'x1', 'y1', 'x2', 'y2']) {
      assert.ok(tool.parameters.properties?.[key], `${name} 要有参数 ${key}`)
      assert.ok(tool.parameters.required?.includes(key), `${name} 的参数 ${key} 必须是必填`)
    }
  }

  // render 是纯函数：附件引用要原样进 image block。
  const withImage = shot.output.render(
    {},
    { summary: '帧 f1｜图像 100×50 px', attachment: { attachmentId: 'a'.repeat(64) } },
  )
  assert.equal(withImage.length, 2)
  assert.equal(withImage[0].type, 'text')
  assert.equal(withImage[1].type, 'image')
  assert.equal(withImage[1].attachment.attachmentId, 'a'.repeat(64))

  // 没有附件服务时退回纯文字，不能抛。
  const textOnly = shot.output.render({}, { summary: '帧 f1（未挂附件服务）' })
  assert.equal(textOnly.length, 1)
  assert.equal(textOnly[0].type, 'text')

  // run_command 只在注入桥 env 时才注册，所以上面那轮覆盖不到它 —— 而它的 schema 写错
  // 同样会让 **整个插件** 的工具全丢（defineTool 在 apply 里逐个编译）。必须单独过一遍真解析器。
  process.env.FACTOTUM_PROOT = '1'
  const withShell = []
  plugin.apply({ on: () => {}, tools: { register: (tool) => withShell.push(tool) } })
  assert.equal(withShell.length, 20, '注入桥 env 后要有 20 个工具')
  const shell = withShell.find((tool) => tool.name === 'run_command')
  assert.ok(shell, 'run_command 要注册成功')
  assert.equal(shell.output.schema.type, 'string')
  assert.ok(shell.parameters.properties?.cmd, 'run_command 要有 cmd 参数')
  assert.ok(shell.parameters.required?.includes('cmd'), 'cmd 必须必填')
  assert.equal(shell.output.render({}, 'out')[0].type, 'text')
  delete process.env.FACTOTUM_PROOT

  console.log(`ok: ${registered.length} 个工具 + run_command（共 20 个）通过真实 defineTool 校验`)
} finally {
  rmSync(sandbox, { recursive: true, force: true })
}
