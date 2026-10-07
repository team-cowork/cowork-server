package com.cowork.preference.service

import com.cowork.preference.security.RequesterContext

class AccountOwnershipDeniedException : RuntimeException("본인의 계정 설정만 접근할 수 있습니다.")

/** 계정 설정 조회·수정은 본인에게만 허용하며, 전역 ADMIN도 타인 계정에 접근할 수 없다. */
object AccountOwnershipGuard {
    fun requireOwner(accountId: Long, requester: RequesterContext) {
        if (accountId != requester.userId) {
            throw AccountOwnershipDeniedException()
        }
    }
}
