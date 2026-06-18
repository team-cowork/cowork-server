package com.cowork.project.support

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 현재 트랜잭션이 커밋된 후에 [action]을 실행하도록 등록한다.
 * 이벤트 발행 등 외부 부수효과를 커밋 성공 시점으로 미룰 때 사용한다.
 */
fun afterCommit(action: () -> Unit) {
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = action()
        }
    )
}