package com.cowork.preference.domain.access.exception

/** 요청자가 프로젝트·채널 멤버가 아니거나 요구되는 관리 권한이 없을 때 던진다. 호출부는 403으로 매핑해야 한다. */
class ResourceAccessDeniedException(message: String) : RuntimeException(message)
