package com.cowork.preference.service

/** 프로젝트·채널 projection이 아직 따라잡지 못했을 때 던진다. 호출부는 503으로 매핑해야 한다. */
class ProjectionNotReadyException(message: String) : IllegalStateException(message)

/** 요청자가 프로젝트·채널 멤버가 아니거나 요구되는 관리 권한이 없을 때 던진다. 호출부는 403으로 매핑해야 한다. */
class ResourceAccessDeniedException(message: String) : RuntimeException(message)

/** 보관된 채널의 설정을 바꾸려 할 때 던진다. 호출부는 409로 매핑해야 한다. */
class ArchivedChannelException : RuntimeException("보관된 채널의 설정은 변경할 수 없습니다.")
