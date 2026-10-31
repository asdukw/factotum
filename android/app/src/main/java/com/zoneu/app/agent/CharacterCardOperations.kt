package com.zoneu.app.agent

import android.content.Context
import com.zoneu.app.device.DeviceActionResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** 主 AI 仅生成结构化参数；宿主负责校验、联网、幂等提交与准确回执。 */
object CharacterCardOperations {
  const val RESULT_KIND = "zoneu.character-card.result.v1"
  private val controls = ConcurrentHashMap<String, CardDownloadControl>()

  fun cancel(requestId: String): DeviceActionResult {
    val control = control(requestId)
    control.cancel()
    return DeviceActionResult(true, "角色卡请求已收到取消信号")
  }

  fun create(context: Context, requestId: String, fields: JsonObject): DeviceActionResult = runOperation(requestId) { control ->
    validateFields(fields)
    val raw = buildJsonObject {
      put("spec", "chara_card_v2")
      put("spec_version", "2.0")
      put("data", fields)
    }
    val params = cardHash("create:" + cardCanonical(fields))
    CharacterStore.transaction {
      committer(context).existing(requestId, params)?.let { success(it, "existing") }?.let { return@transaction it }
      val card = CharacterImporter.fromBytes(raw.toString().toByteArray(Charsets.UTF_8), "未命名角色").card
      submit(context, requestId, params, card, "created", control)
    }
  }

  fun importUrl(context: Context, requestId: String, text: String): DeviceActionResult = runOperation(requestId) { control ->
    val url = CardNetworkPolicy.url(text)
    val params = cardHash("import:" + url)
    val early = CharacterStore.transaction { committer(context).existing(requestId, params)?.let { success(it, "existing") } }
    if (early != null) return@runOperation early
    val api = ChubCardAdapter.apiUrl(url)
    val downloaded = CharacterCardDownloader().download(api ?: url, control)
    val bytes = if (api == null) downloaded else ChubCardAdapter.convert(downloaded, url)
    control.check()
    val card = CharacterImporter.fromBytes(bytes, "导入角色").card
    CharacterStore.transaction {
      committer(context).existing(requestId, params)?.let { success(it, "existing") } ?: submit(
        context,
        requestId,
        params,
        card.copy(
          importMetadata = card.importMetadata!!.copy(
            originHost = url.host,
            originUrl = CardNetworkPolicy.source(url, api != null),
          ),
        ),
        "imported",
        control,
        downloaded.takeIf { it.isPng() },
      )
    }
  }

  private fun runOperation(request: String, operation: (CardDownloadControl) -> DeviceActionResult): DeviceActionResult {
    if (request.isBlank() || request.length > 256) return failure("CARD_INVALID_REQUEST")
    val control = control(request)
    return try {
      control.check()
      operation(control)
    } catch (error: CardOperationException) {
      failure(error.code)
    } catch (_: CharacterImporter.ImportException) {
      failure("CARD_INVALID_CONTENT")
    } catch (_: java.io.IOException) {
      failure("CARD_NETWORK_OR_STORAGE_FAILED")
    } catch (_: IllegalArgumentException) {
      failure("CARD_INVALID_FIELDS")
    } catch (_: IllegalStateException) {
      failure("CARD_SAVE_FAILED")
    }
  }

  internal fun control(request: String): CardDownloadControl =
    controls.computeIfAbsent(cardHash(request)) { CardDownloadControl() }

  private fun committer(context: Context) = CardCommitter(CharacterStore.storage(context))

  private fun submit(
    context: Context,
    request: String,
    params: String,
    card: CharacterCard,
    disposition: String,
    control: CardDownloadControl,
    png: ByteArray? = null,
  ): DeviceActionResult {
    val (read, duplicate) = committer(context).submit(request, params, card, control, png)
    return success(read, if (duplicate) "existing" else disposition)
  }

  private fun success(card: CharacterCard, disposition: String): DeviceActionResult {
    val result = buildJsonObject {
      put("kind", RESULT_KIND)
      put("ok", true)
      put("characterId", card.id)
      put("name", card.name)
      put("disposition", disposition)
      put(
        "source",
        buildJsonObject {
          put("host", card.importMetadata?.originHost.orEmpty())
          put("canonicalUrl", card.importMetadata?.originUrl.orEmpty())
        },
      )
      put(
        "report",
        buildJsonObject {
          put(
            "counts",
            buildJsonObject {
              CardHandling.entries.forEach { handling ->
                put(handling.name.lowercase(), card.compatibilityNotes().count { it.handling == handling })
              }
            },
          )
        },
      )
    }
    return DeviceActionResult(true, "角色卡已保存并完成回读", result.toString())
  }

  internal fun failure(code: String): DeviceActionResult {
    val reason = when (code) {
      "CARD_HTTP_403", "CARD_HTTP_401" -> "来源要求登录或拒绝访问，不绕过限制。"
      "CARD_HTTP_429" -> "来源限流，请稍后由用户重新发起。"
      "CARD_PRIVATE_ADDRESS", "CARD_HTTPS_REQUIRED" -> "仅允许公网 HTTPS 卡来源，不允许本机或内网地址。"
      "CARD_INVALID_CONTENT", "CARD_SOURCE_CHANGED" -> "没有读到有效角色卡，可能是详情页、登录页或站点格式已变化。"
      "CARD_PREVIOUSLY_DELETED" -> "这个请求对应的角色卡已被删除，旧请求不会恢复它。"
      "CARD_CANCELLED" -> "请求已取消，不继续下载或提交。"
      "CARD_REQUEST_CONFLICT" -> "请求身份与参数冲突，不可用同一请求覆盖另一张卡。"
      "CARD_TOO_LARGE", "CARD_TRANSFER_TRUNCATED" -> "文件超过上限或传输不完整，没有确认导入成功。"
      else -> libraryFailure(code)
    }
    return DeviceActionResult(false, "角色卡操作未完成（$code）：$reason 勿改用 shell 写入。", code = code)
  }

  private fun libraryFailure(code: String): String = when (code) {
    "CARD_REVISION_CONFLICT", "CARD_CURSOR_STALE", "CARD_ENTRY_CONFLICT" -> "版本或条目已变化；请重新查询、读取当前版本，核对后重新提出修改。"
    "CARD_READ_ONLY" -> "内置角色卡仅可读取，不能修改，也不会自动复制。"
    "CARD_NOT_FOUND", "CARD_ENTRY_NOT_FOUND" -> "角色卡或条目不存在；请重新查询并使用精确身份。"
    "CARD_PREVIEW_REQUIRED", "CARD_PREVIEW_CONFLICT" -> "修改预检缺失或参数已变化；请重新读取并发起审批。"
    "CARD_EMPTY_PATCH", "CARD_INVALID_FIELDS", "CARD_REVISION_REQUIRED" -> "请补齐版本并核对稀疏字段和逐条操作，不能提交空修改或整卡替换。"
    "CARD_FIELD_TOO_LARGE" -> "所选完整字段或条目超过返回上限，请在本地角色详情中处理；没有返回截断正文。"
    else -> "请核对来源、网络或参数；保存结果尚未确认，可用同一请求重试查询。"
  }

  internal fun validateFields(fields: JsonObject) {
    val strings = setOf("name", "nickname", "description", "personality", "scenario", "first_mes", "creator_notes", "creator")
    val arrays = setOf("tags", "alternate_greetings", "group_only_greetings")
    require(fields.keys.all { it in strings || it in arrays || it == "character_book" })
    require((fields["name"] as? JsonPrimitive)?.isString == true && (fields["name"] as JsonPrimitive).content.isNotBlank())
    fields.forEach { (key, value) ->
      when (key) {
        in strings -> require(value is JsonPrimitive && value.isString && value.content.length <= 100_000)
        in arrays -> require(value is JsonArray && value.size <= 100 && value.all { it is JsonPrimitive && it.isString })
        "character_book" -> validateBook(value as? JsonObject)
      }
    }
    require(fields.toString().toByteArray(Charsets.UTF_8).size <= CardInput.MAX_JSON)
  }

  private fun validateBook(book: JsonObject?) {
    require(book != null && book.keys == setOf("entries"))
    val entries = book["entries"] as? JsonArray ?: throw IllegalArgumentException("entries")
    require(entries.size <= 200)
    entries.forEach { value ->
      val entry = value as? JsonObject ?: throw IllegalArgumentException("entry")
      require(entry.keys.all { it in setOf("keys", "content", "constant") })
      val content = entry["content"] as? JsonPrimitive
      require(content?.isString == true && content.content.length <= WorldEntryDraft.MAX_CONTENT)
      entry["keys"]?.let { keys ->
        require(keys is JsonArray && keys.size <= WorldEntryDraft.MAX_KEYS && keys.all { it is JsonPrimitive && it.isString })
      }
      entry["constant"]?.let { require(it is JsonPrimitive && !it.isString && it.content in listOf("true", "false")) }
    }
  }
}

private fun ByteArray.isPng(): Boolean = size >= 8 && copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
