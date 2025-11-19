package com.cowork.preference.event

data class AccountStatusChangedEvent(
    val userId: Long,
    val previousStatus: String?,
    val newStatus: String?,
    val reason: String,
)
