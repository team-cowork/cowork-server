package com.cowork.preference.domain.access.exception

/** 팀 멤버 projection이 아직 해당 팀/계정 상태를 따라잡지 못했을 때 던진다. 호출부는 503으로 매핑해야 한다. */
class TeamMembershipProjectionNotReadyException(message: String) : IllegalStateException(message)
