package com.cowork.preference.domain.access.exception

/** 프로젝트·채널 projection이 아직 따라잡지 못했을 때 던진다. 호출부는 503으로 매핑해야 한다. */
class ProjectionNotReadyException(message: String) : IllegalStateException(message)
