package com.cowork.preference.domain.access.exception

class AccountOwnershipDeniedException : RuntimeException("본인의 계정 설정만 접근할 수 있습니다.")
