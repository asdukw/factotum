package com.factotum.app.ui.main

import com.factotum.app.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
  @Test
  fun uiState_initiallyLoading() {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    // 不订阅上游：StateFlow 的初值才是 Loading。用 first() 会先订阅，
    // 冷流可能在收集器拿到初值前就把它改成 Success（CI 上偶发失败）。
    assertEquals(MainScreenUiState.Loading, viewModel.uiState.value)
  }

  @Test
  fun uiState_onItemSaved_isDisplayed() = runTest {
    val viewModel = MainScreenViewModel(FakeMyModelRepository())
    val success = viewModel.uiState.first { it is MainScreenUiState.Success }
    assertEquals(MainScreenUiState.Success(listOf("Sample")), success)
  }
}

private class FakeMyModelRepository : DataRepository {
  override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}
