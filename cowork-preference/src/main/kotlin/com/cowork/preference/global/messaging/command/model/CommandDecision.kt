package com.cowork.preference.global.messaging.command.model

sealed interface CommandDecision<out C, out E> {
    data class Apply<C>(val command: C) : CommandDecision<C, Nothing>
    data class Reject<E>(val envelope: E, val reason: String) : CommandDecision<Nothing, E>
    data class Quarantine(val reason: String) : CommandDecision<Nothing, Nothing>
}
