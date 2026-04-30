package com.cowork.user.audit

import org.springframework.data.domain.AuditorAware
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.Optional

private const val USER_ID_HEADER = "X-User-Id"

@Component
class AuditorAwareImpl : AuditorAware<Long> {
    override fun getCurrentAuditor(): Optional<Long> {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request ?: return Optional.empty()
        return Optional.ofNullable(request.getHeader(USER_ID_HEADER)?.toLongOrNull())
    }
}
