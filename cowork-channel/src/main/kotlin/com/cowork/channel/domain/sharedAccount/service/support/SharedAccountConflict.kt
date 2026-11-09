package com.cowork.channel.domain.sharedAccount.service.support

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

private const val SHARED_ACCOUNT_UNIQUE_CONSTRAINT = "uq_tb_channel_accounts_channel_provider_identifier"

/** 공유 계정 unique 위반만 409로 바꾸고, 길이 초과·FK 위반 같은 다른 무결성 오류는 원래 예외로 둔다. */
fun DataIntegrityViolationException.toSharedAccountConflict(): RuntimeException =
    if (mostSpecificCause.message?.contains(SHARED_ACCOUNT_UNIQUE_CONSTRAINT) == true) {
        ExpectedException("이미 채널에 등록된 공유 계정입니다.", HttpStatus.CONFLICT)
    } else {
        this
    }
