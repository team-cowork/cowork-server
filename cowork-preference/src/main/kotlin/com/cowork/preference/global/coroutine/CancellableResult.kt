package com.cowork.preference.global.coroutine

import kotlinx.coroutines.CancellationException

/** runCatching과 같지만 코루틴 취소는 Result로 감싸지 않고 다시 던진다. */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}
