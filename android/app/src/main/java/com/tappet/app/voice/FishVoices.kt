package com.tappet.app.voice

/**
 * Fish Audio 的预置音色（`reference_id`）。
 *
 * id 是 Fish 上**语音模型**的公开 id，不是 API key；换一条参考音色只是换合成的声音，
 * 调用方式不变。空 id 表示不带 `reference_id`，用 Fish 的默认音色。
 *
 * 这些条目是社区上传的角色音（授权不在我们手里）。挑的是标签里明确写了角色名、且
 * 实测能合成的那几条；同名的模型很多（搜「流萤」有上百条），耳感只有用户能判断，
 * 所以这里放几个候选、由用户在设置页用「试听」挑，而不是替用户定死一条。
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
      TtsVoice("4ee64e5052834c25b9785b693d3489ab", "流萤（角色音）"),
      TtsVoice("bcc5fbb281f14f9fbb081b6eb542f9a7", "流萤（叙事）"),
      TtsVoice("668eb79560fa46449773871d36c594db", "流萤（自然）"),
    )

  fun byId(id: String?): TtsVoice? = ALL.firstOrNull { it.id == (id ?: DEFAULT_ID) }

  /** 界面上的当前音色名；认不出的 id（模型被删）回落成默认音色的名字。 */
  fun labelOf(id: String?): String = byId(id)?.label ?: ALL.first().label
}
