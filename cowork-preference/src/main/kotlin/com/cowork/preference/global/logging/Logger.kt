package com.cowork.preference.global.logging

import kotlinx.coroutines.CoroutineScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * the-sdk `logger()`와 같은 동작이다. the-sdk는 Spring 의존성을 함께 가져오고 Kotlin Toolchain은
 * transitive 의존성 제외를 지원하지 않으므로 preference에 직접 둔다.
 */
fun Any.logger(): Logger = LoggerFactory.getLogger(javaClass)

/** `launch { }` 같은 coroutine builder 안에서는 receiver가 바뀌어 logger 이름이 coroutine 클래스가 되므로 막는다. */
@Deprecated(
    "Use this@EnclosingClass.logger() inside a coroutine builder",
    ReplaceWith("this@EnclosingClass.logger()"),
    DeprecationLevel.ERROR,
)
fun CoroutineScope.logger(): Logger = LoggerFactory.getLogger(javaClass)
