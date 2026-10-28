// 从 pi-ai 的 provider 数据生成「provider → model id → input 模态」索引。
//
// 用途：设置页要给几百个端点模型标「可读图 / 仅文本」，NodeRuntime 还要按同一份结论生成
// ZONEU_LLM_MODELS 的 input 声明。让 App 去解 63MB 的 dsh-runtime.zip 只为了拿这点信息
// 不值得，所以在装配阶段抽成 ~60KB 的 assets/model-modalities.json。
//
// 生成物不入库；调用方是 tools/bundle-dsh.ps1。
// 用法：node modality-index.mjs <pi-ai providers/data 目录> <输出 json>
import { readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const [dataDir, outFile] = process.argv.slice(2)
if (!dataDir || !outFile) {
  console.error('用法: node modality-index.mjs <pi-ai providers/data 目录> <输出 json>')
  process.exit(2)
}

const index = {}
let count = 0
for (const file of readdirSync(dataDir)) {
  if (!file.endsWith('.json')) continue
  const provider = file.slice(0, -'.json'.length)
  const parsed = JSON.parse(readFileSync(join(dataDir, file), 'utf8'))
  const models = {}
  for (const byApi of Object.values(parsed)) {
    for (const [id, model] of Object.entries(byApi)) {
      if (models[id]) continue // 同一 provider 下不同 api 分组可能有重复 id，取第一个
      models[id] = Array.isArray(model?.input) ? model.input : ['text']
      count++
    }
  }
  index[provider] = models
}

writeFileSync(outFile, JSON.stringify(index))
console.log(
  `modality index: ${Object.keys(index).length} providers, ${count} models -> ${outFile}`,
)
