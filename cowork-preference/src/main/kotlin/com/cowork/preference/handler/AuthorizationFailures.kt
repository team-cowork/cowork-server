package com.cowork.preference.handler

import com.cowork.preference.service.AccountOwnershipDeniedException
import com.cowork.preference.service.ProjectionNotReadyException
import com.cowork.preference.service.ResourceAccessDeniedException
import com.cowork.preference.service.TeamMembershipDeniedException
import com.cowork.preference.service.TeamMembershipProjectionNotReadyException
import org.slf4j.Logger

/** 인가 실패 예외의 HTTP 상태를 반환한다. 인가 실패가 아니면 null이다. */
internal fun authorizationStatus(e: Throwable): Int? = when (e) {
    is AccountOwnershipDeniedException,
    is TeamMembershipDeniedException,
    is ResourceAccessDeniedException,
    -> 403
    is TeamMembershipProjectionNotReadyException,
    is ProjectionNotReadyException,
    -> 503
    else -> null
}

/** 인가 실패를 감사 로그로 남긴다. 요청자·리소스 ID와 상태만 기록하고 헤더 원문이나 본문은 기록하지 않는다. */
internal fun auditAuthorizationFailure(
    log: Logger,
    status: Int,
    requesterId: Long,
    resource: String,
    resourceId: Long,
) {
    log.warn(
        "Rejected preference request status={} requesterId={} resource={} resourceId={}",
        status,
        requesterId,
        resource,
        resourceId,
    )
}
