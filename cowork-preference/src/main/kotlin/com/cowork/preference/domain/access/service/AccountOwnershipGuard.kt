package com.cowork.preference.domain.access.service

import com.cowork.preference.domain.access.exception.AccountOwnershipDeniedException
import com.cowork.preference.global.security.RequesterContext

/** 계정 설정 조회·수정은 본인에게만 허용하며, 전역 ADMIN도 타인 계정에 접근할 수 없다. */
object AccountOwnershipGuard {
    fun requireOwner(accountId: Long, requester: RequesterContext) {
        if (accountId != requester.userId) {
            throw AccountOwnershipDeniedException()
        }
    }
}
