package com.zoneu.app.agent

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** provider id → model id → input 模态（如 `["text", "image"]`）。 */
internal typealias ModalityIndex = Map<String, Map<String, List<String>>>

/**
 * 解析构建期生成的模态索引（`assets/model-modalities.json`，见
 * tools/dsh-phone/modality-index.mjs）。坏数据当空：设置页不该因为索引读不出来而打不开。
 */
internal fun parseModalityIndex(raw: String): ModalityIndex =
  runCatching {
    Json.parseToJsonElement(raw)
      .jsonObject
      .mapValues { (_, models) ->
        models.jsonObject.mapValues { (_, input) ->
          input.jsonArray.mapNotNull { entry ->
            runCatching { entry.jsonPrimitive.content }.getOrNull()
          }
        }
      }
  }
    .getOrElse { emptyMap() }

/**
 * 判断一个模型能不能读图。顺序：
 * 1. **当前 provider 的 pi-ai 条目** —— 最可信；
 * 2. 其它 provider 里**同 id 且结论一致**的条目 —— 聚合商（OpenRouter / zen）常直接复用
 *    同一条模型 id；有分歧就不猜；
 * 3. 不知道就 **false**。欠声明只是提前拒图（模型会被告知不支持图片），过度声明会把图发给
 *    端点、在请求中途被拒 —— 两者代价不对称。
 */
internal fun resolveSupportsImage(
  index: ModalityIndex,
  providerId: String,
  modelId: String,
): Boolean {
  index[providerId]?.get(modelId)?.let { return "image" in it }
  val cross = index.values.mapNotNull { it[modelId] }.map { "image" in it }.distinct()
  if (cross.size == 1) return cross.first()
  return false
}

/** 该 id 在索引里是否有**可信**条目（当前 provider 或跨 provider 结论一致）。 */
internal fun isModelKnown(index: ModalityIndex, providerId: String, modelId: String): Boolean {
  if (index[providerId]?.containsKey(modelId) == true) return true
  return index.values.mapNotNull { it[modelId] }.any()
}

/**
 * 模态索引的进程内缓存：第一次读 60KB 资产，之后 O(1)。资产缺失（没跑过 bundle-dsh 的
 * 开发构建）时返回空索引，所有模型按 text 声明。
 */
internal object ModelModalities {
  private const val ASSET = "model-modalities.json"

  @Volatile private var cached: ModalityIndex? = null

  fun index(context: Context): ModalityIndex {
    cached?.let { return it }
    return synchronized(this) {
      cached
        ?: runCatching {
          context.assets.open(ASSET).bufferedReader().use { it.readText() }
        }
          .getOrNull()
          ?.let(::parseModalityIndex)
          ?.also { cached = it }
        ?: emptyMap()
    }
  }

  fun supportsImage(context: Context, providerId: String, modelId: String): Boolean =
    resolveSupportsImage(index = index(context), providerId = providerId, modelId = modelId)

  fun isKnown(context: Context, providerId: String, modelId: String): Boolean =
    isModelKnown(index(context), providerId, modelId)
}
