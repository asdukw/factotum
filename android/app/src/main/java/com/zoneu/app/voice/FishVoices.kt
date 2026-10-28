package com.zoneu.app.voice

/**
 * Fish Audio 的预置音色（`reference_id`）。
 *
 * id 是 Fish 上**语音模型**的公开 id，不是 API key；换一条参考音色只是换合成的声音，
 * 调用方式不变。空 id 表示不带 `reference_id`，用 Fish 的默认音色。
 *
 * 这些条目是社区上传的角色音（授权不在我们手里）。同名的模型很多，选法：
 * 流萤取用户试听后选定的那条；菲比与胡桃取公开列表里 `task_count`（合成次数）与
 * `like_count` 都最高的那条（胡桃 3636 次 / 124 赞；菲比 1538 次 / 29 赞，也正是
 * 用户另一个 App 在用的 id）。加进来之前都实测过 `POST /v1/tts` 返回音频。
 *
 * 新增条目的规矩：先在 Fish 上确认 id 存在且 `POST /v1/tts` 返回音频，再加进来；
 * 同一个 id 只出现一次（[FishVoicesTest] 钉住）。
 */
object FishVoices {
  /** 不带 reference_id：Fish 的默认音色。 */
  const val DEFAULT_ID = ""

  val ALL: List<TtsVoice> =
    listOf(
      TtsVoice(DEFAULT_ID, "默认音色"),
      TtsVoice("4ee64e5052834c25b9785b693d3489ab", "流萤"),
      TtsVoice("8e80c3dcdf894213a10b48e9b08ad8c6", "菲比"),
      TtsVoice("27afebb2dd2e424eaba1d74fb0d25c9f", "胡桃"),
    )

  fun byId(id: String?): TtsVoice? = ALL.firstOrNull { it.id == (id ?: DEFAULT_ID) }

  /** 界面上的当前音色名；认不出的 id（模型被删）回落成默认音色的名字。 */
  fun labelOf(id: String?): String = byId(id)?.label ?: ALL.first().label
}
