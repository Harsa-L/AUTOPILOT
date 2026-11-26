package com.autopilot.core.common

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

interface AutopilotDispatchers {
  val main: CoroutineDispatcher
  val io: CoroutineDispatcher
  val default: CoroutineDispatcher

  fun newSingleThreadVisionDispatcher(name: String = "autopilot-vision"): CloseableDispatcher
}

class CloseableDispatcher(
  val dispatcher: CoroutineDispatcher,
  private val closeAction: () -> Unit,
) : AutoCloseable {
  override fun close() = closeAction()
}

class DefaultAutopilotDispatchers : AutopilotDispatchers {
  override val main: CoroutineDispatcher = Dispatchers.Main
  override val io: CoroutineDispatcher = Dispatchers.IO
  override val default: CoroutineDispatcher = Dispatchers.Default

  override fun newSingleThreadVisionDispatcher(name: String): CloseableDispatcher {
    val executor = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, name).apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    return CloseableDispatcher(executor.asCoroutineDispatcher()) { executor.shutdown() }
  }
}
