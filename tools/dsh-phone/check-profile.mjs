// 在把 phone profile 打进 dsh-runtime.zip **之前**验一遍。
//
// 为什么必须在这里挡：overlay 的 YAML 一旦写坏，dsh 启动时直接报错，而内嵌 Node 与宿主
// 同进程 —— 报错就是**整个 App 崩**（实测：`bad indentation of a mapping entry`，
// 起因是 !!js 表达式里写了对象字面量，而 YAML 的 plain scalar 里不能出现 ": "）。
// CI 上没有 dsh 运行时，跑不了这一步，所以只能靠装配脚本把住。
//
// 两个坑，都是踩过的：
//   1) 必须用**真解析器**解析整份文件。把 !!js 表达式整行替换成占位串再解析，等于把要检查的
//      东西删掉再检查 —— 上面那个 indentation 错误就是这么漏过去的。
//   2) 表达式要**真的执行一遍**：语法错、返回 undefined、类型不对，都要在这里炸。
//
// 用法：node check-profile.mjs [profile.yml] [node_modules 目录]
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const repoRoot = resolve(here, '../..')
const profilePath = process.argv[2] ?? join(here, 'profile/cordis.patch.yml')
// 必须绝对路径：createRequire 只收绝对路径 / file URL。CI 传的是相对路径（node_modules），
// 这里统一 resolve 一次 —— 本地测试时我传的是绝对路径，于是这个坑是 CI 先踩到的。
const modulesDir = resolve(process.argv[3] ?? join(repoRoot, 'android/.dsh-cache/runtime/node_modules'))

// js-yaml 从 vendored 运行时里借（装配脚本刚装好的那份），不给本仓库引入 npm 依赖
const require = createRequire(join(modulesDir, 'index.js'))
let yaml
try {
  yaml = require('js-yaml')
} catch (cause) {
  console.error(`找不到 js-yaml（${modulesDir}）。先跑 tools/bundle-dsh.ps1 装配运行时，` +
    `或把 node_modules 目录作为第二个参数传进来。`)
  throw cause
}

const fail = (msg) => {
  console.error(`FAIL ${msg}`)
  process.exit(1)
}

// !!js 是一个自定义 tag：注册成标量类型，让 YAML 按**真实标量规则**解析它的内容。
// 只支持 js-yaml 4.x —— 5.x 换了一套自定义 tag 的 API（defineScalarTag / Schema），
// 与其在这里两套都维护，不如把版本钉死并给出清楚的提示（vendored 运行时里就是 4.x）。
if (typeof yaml.Type !== 'function' || typeof yaml.DEFAULT_SCHEMA?.extend !== 'function') {
  fail(
    `这份 js-yaml 是 ${require('js-yaml/package.json').version}，本脚本只支持 4.x。` +
      `CI 里用 npm install --no-save 'js-yaml@^4'；本机可以直接从 vendored 运行时里借` +
      `（不传第二个参数就是这条路径）。`,
  )
}
const JsType = new yaml.Type('tag:yaml.org,2002:js', {
  kind: 'scalar',
  construct: (data) => ({ js: data }),
})
const schema = yaml.DEFAULT_SCHEMA.extend([JsType])

const raw = readFileSync(profilePath, 'utf8')
let doc
try {
  doc = yaml.load(raw, { schema })
} catch (cause) {
  fail(`${profilePath} 不是合法 YAML：${cause.message}`)
}
if (!Array.isArray(doc)) fail('overlay 顶层应该是「一行一个组件」的列表')

const entries = new Map(doc.map((e) => [e?.id, e]))
const provider = entries.get('llm-pi-ai')?.config?.providers
const defaultModel = entries.get('agent-default-model')?.config
if (!provider || !defaultModel) fail('缺少 llm-pi-ai 或 agent-default-model')

const [providerId, cfg] = Object.entries(provider)[0] ?? []
if (!providerId) fail('llm-pi-ai 没有配任何 provider')
if (defaultModel.provider !== providerId) {
  fail(`agent-default-model.provider=${defaultModel.provider} 与 provider 名 ${providerId} 对不上`)
}

// 每个 !!js 都要真的跑一遍：四种环境（全空 / Anthropic / zen / 动态模型清单）下都必须是可用的值
const exprs = [cfg.api, cfg.baseURL, cfg.headers, cfg.compat, defaultModel.model, cfg.models]
for (const [i, v] of exprs.entries()) {
  if (!v || typeof v.js !== 'string') fail(`第 ${i + 1} 个 !!js 不是字符串标量`)
}
const scenarios = [
  { name: '空环境（走兜底）', env: {} },
  {
    name: 'Anthropic',
    env: {
      TAPPET_LLM_API: 'anthropic-messages',
      TAPPET_LLM_BASE_URL: 'https://api.anthropic.com',
      TAPPET_LLM_MODEL: 'claude-sonnet-5',
      TAPPET_LLM_THINKING_FORMAT: '',
      TAPPET_LLM_ZEN: '',
    },
  },
  {
    name: 'opencode zen',
    env: {
      TAPPET_LLM_API: 'openai-completions',
      TAPPET_LLM_BASE_URL: 'https://opencode.ai/zen/go/v1',
      TAPPET_LLM_MODEL: 'deepseek-v4.1-flash',
      TAPPET_LLM_THINKING_FORMAT: 'deepseek',
      TAPPET_LLM_ZEN: '1',
      TAPPET_LLM_SESSION: 'check-profile',
    },
  },
  {
    // 宿主（NodeRuntime）按当前选中的模型注入的动态清单，见 ProviderConfig.llmModelsJson。
    // 自定义提供商 / 目录外模型全靠它；空环境下的 fallback 只是一条可用的默认。
    name: '动态模型清单',
    env: { TAPPET_LLM_MODELS: '[{"id":"gateway/model-x","input":["text"]}]' },
  },
]
const validatedModels = []
for (const { name, env } of scenarios) {
  const values = exprs.map((v) => {
    try {
      return new Function('process', `return (${v.js})`)({ env })
    } catch (cause) {
      return fail(`[${name}] 表达式求值失败：${v.js} —— ${cause.message}`)
    }
  })
  const [api, baseURL, headers, compat, model, models] = values
  for (const [label, value] of [
    ['api', api],
    ['baseURL', baseURL],
    ['headers', headers],
    ['compat', compat],
    ['model', model],
    ['models', models],
  ]) {
    if (value === undefined || value === null) fail(`[${name}] ${label} 求值成了 ${value}`)
  }
  if (typeof api !== 'string' || typeof baseURL !== 'string' || typeof model !== 'string') {
    fail(`[${name}] api / baseURL / model 必须是字符串`)
  }
  if (typeof headers !== 'object' || typeof compat !== 'object') {
    fail(`[${name}] headers / compat 必须是对象（空对象也要给，不能是 undefined）`)
  }
  if (!Array.isArray(models) || models.length === 0) fail(`[${name}] models 列表为空`)
  for (const m of models) {
    if (!m?.id) fail(`[${name}] models 里有条目缺 id`)
    if (!Array.isArray(m.input) || m.input.length === 0) {
      fail(`[${name}] 模型 ${m.id} 没声明 input：不声明时 MODALITIES 只当 text，工具结果里的图会被直接拒掉`)
    }
  }
  validatedModels.push(...models)
  console.log(`  ok   ${name}：api=${api} model=${model} models=${models.length}`)
}

console.log(`  ok   provider=${providerId}，动态模型清单可被注入（共校验 ${validatedModels.length} 条模型）`)
console.log('profile 校验通过')
