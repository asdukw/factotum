package com.zoneu.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 候选切换的边界：绕回、单份、脏数据。 */
class VariantsTest {
  @Test
  fun stepsForwardAndWrapsAround() {
    assertEquals(1, Variants.step(size = 3, current = 0, delta = 1))
    assertEquals(0, Variants.step(size = 3, current = 2, delta = 1))
  }

  @Test
  fun stepsBackwardAndWrapsAround() {
    assertEquals(2, Variants.step(size = 3, current = 0, delta = -1))
    assertEquals(1, Variants.step(size = 3, current = 2, delta = -1))
  }

  @Test
  fun singleCandidateStaysPut() {
    assertEquals(0, Variants.step(size = 1, current = 0, delta = 1))
  }

  @Test
  fun emptyGroupDoesNotCrash() {
    assertEquals(0, Variants.step(size = 0, current = 0, delta = 1))
  }

  @Test
  fun labelOnlyAppearsWhenThereIsSomethingToSwitch() {
    assertNull(Variants.label(index = 0, size = 1))
    assertEquals("1/2", Variants.label(index = 0, size = 2))
    assertEquals("3/3", Variants.label(index = 2, size = 3))
  }

  @Test
  fun unknownSelectionFallsBackToTheFirst() {
    assertEquals(0, Variants.currentIndex(listOf(0, 1), selected = 5))
    assertEquals(1, Variants.currentIndex(listOf(0, 1), selected = 1))
    assertEquals(0, Variants.currentIndex(emptyList(), selected = null))
  }
}
