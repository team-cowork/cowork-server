package com.cowork.applet.domain.applet.service

import com.cowork.applet.domain.applet.model.Applet
import com.cowork.applet.domain.applet.repository.AppletRepository
import com.cowork.applet.global.error.ExpectedException
import javax.inject.{Inject, Singleton}
import play.api.http.Status.{FORBIDDEN, NOT_FOUND}
import scala.concurrent.{ExecutionContext, Future}

// Owners manage their applets. Other users can see and run an applet only after a version is published.
@Singleton
class AppletReader @Inject() (appletRepository: AppletRepository)(using ExecutionContext):

  def visible(appletId: String, userId: String): Future[Applet] =
    find(appletId).map(applet => AppletReader.requireVisible(applet, userId))

  def owned(appletId: String, userId: String): Future[Applet] =
    find(appletId).map(applet => AppletReader.requireOwned(applet, userId))

  private def find(appletId: String): Future[Applet] =
    appletRepository.findById(appletId).map(_.getOrElse(throw AppletReader.notFound))

object AppletReader:
  def requireVisible(applet: Applet, userId: String): Applet =
    if applet.isOwnedBy(userId) || applet.isPublished then applet else throw notFound

  def requireOwned(applet: Applet, userId: String): Applet =
    if applet.isOwnedBy(userId) then applet
    else if applet.isPublished then throw ExpectedException(FORBIDDEN, "앱을 관리할 권한이 없습니다.")
    else throw notFound

  private def notFound = ExpectedException(NOT_FOUND, "앱을 찾을 수 없습니다.")
