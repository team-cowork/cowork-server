package com.cowork.preference.global.security

import io.vertx.ext.web.RoutingContext

data class RequesterContext(val userId: Long) {
    companion object {
        fun from(ctx: RoutingContext): RequesterContext? = ctx.request().getHeader("X-User-Id")
            ?.toLongOrNull()?.takeIf { it > 0 }?.let(::RequesterContext)
    }
}
