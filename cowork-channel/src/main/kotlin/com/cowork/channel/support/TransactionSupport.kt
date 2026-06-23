package com.cowork.channel.support

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * 현재 트랜잭션이 커밋된 후에 [action]을 실행하도록 등록한다.
 * 이벤트 발행 등 외부 부수효과를 커밋 성공 시점으로 미룰 때 사용한다.
 * 트랜잭션 동기화가 활성화되어 있지 않으면(비트랜잭션 컨텍스트 등) [action]을 즉시 실행한다.
 */
fun afterCommit(action: () -> Unit) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    } else {
        action()
    }
}
