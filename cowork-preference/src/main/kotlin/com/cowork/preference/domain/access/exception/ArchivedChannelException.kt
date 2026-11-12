package com.cowork.preference.domain.access.exception

/** 보관된 채널의 설정을 바꾸려 할 때 던진다. 호출부는 409로 매핑해야 한다. */
class ArchivedChannelException : RuntimeException("보관된 채널의 설정은 변경할 수 없습니다.")
