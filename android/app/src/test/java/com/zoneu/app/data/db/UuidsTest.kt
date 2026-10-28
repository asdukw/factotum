package com.zoneu.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidsTest {
  @Test
  fun v7_encodesTimestampSoIdsSortByTime() {
    val earlier = Uuids.v7(1_700_000_000_000L)
    val later = Uuids.v7(1_700_000_001_000L)
    assertTrue("时间靠后的 id 必须排在后面，否则主键 B 树会乱序", earlier < later)
  }

  @Test
  fun v7_hasVersionAndVariantBits() {
    val id = Uuids.v7(1_700_000_000_000L)
    assertEquals(36, id.length)
    assertEquals('7', id[14])
    assertTrue("变体位应为 10xx", id[19] in listOf('8', '9', 'a', 'b'))
  }

  @Test
  fun v7_isUniqueWithinTheSameMillisecond() {
    val ids = List(1_000) { Uuids.v7(1_700_000_000_000L) }
    assertEquals(1_000, ids.toSet().size)
  }
}
