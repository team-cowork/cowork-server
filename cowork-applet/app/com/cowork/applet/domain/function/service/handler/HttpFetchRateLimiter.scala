package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.global.ratelimit.FixedWindowRateLimiter
import javax.inject.{Inject, Singleton}

// Every user of a published applet can call http.fetch with the owner's secrets, so calls are limited per applet.
// A lower limit per user keeps one user from using up the whole applet limit.
@Singleton
class HttpFetchRateLimiter @Inject() ():
  private val perApplet = FixedWindowRateLimiter(HttpFetchRateLimiter.AppletLimit, 60_000L, () => System.currentTimeMillis())
  private val perUser = FixedWindowRateLimiter(HttpFetchRateLimiter.UserLimit, 60_000L, () => System.currentTimeMillis())

  // The user limit is checked first, so calls over it do not count against the applet.
  def tryAcquire(appletId: String, userId: String): Boolean =
    perUser.tryAcquire(s"$appletId:$userId") && perApplet.tryAcquire(appletId)

object HttpFetchRateLimiter:
  val AppletLimit = 60
  val UserLimit = 20
