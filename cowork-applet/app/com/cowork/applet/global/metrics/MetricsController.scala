package com.cowork.applet.global.metrics

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

class MetricsController @Inject() (components: ControllerComponents, registry: PrometheusMeterRegistry)
    extends AbstractController(components):

  def scrape: Action[AnyContent] = Action(Ok(registry.scrape()).as("text/plain; version=0.0.4; charset=utf-8"))
