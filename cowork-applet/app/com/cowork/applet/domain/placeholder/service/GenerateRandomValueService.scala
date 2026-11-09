package com.cowork.applet.domain.placeholder.service

import com.cowork.applet.domain.placeholder.service.impl.GenerateRandomValueServiceImpl
import com.google.inject.ImplementedBy

@ImplementedBy(classOf[GenerateRandomValueServiceImpl])
trait GenerateRandomValueService:
  def execute(): String
