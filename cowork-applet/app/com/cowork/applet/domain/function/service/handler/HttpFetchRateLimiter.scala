package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.global.ratelimit.FixedWindowRateLimiter
import javax.inject.{Inject, Singleton}

// Every user of a published applet can call http.fetch with the owner's secrets, so calls are limited per applet.
@Singleton
class HttpFetchRateLimiter @Inject() ()
    extends FixedWindowRateLimiter(HttpFetchRateLimiter.Limit, 60_000L, () => System.currentTimeMillis())

object HttpFetchRateLimiter:
  val Limit = 60
