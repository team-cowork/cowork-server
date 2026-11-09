package com.cowork.applet.global.metrics

import com.google.inject.{AbstractModule, Provides, Singleton}
import io.micrometer.core.instrument.binder.MeterBinder
import io.micrometer.core.instrument.binder.jvm.{ClassLoaderMetrics, JvmGcMetrics, JvmMemoryMetrics, JvmThreadMetrics}
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.{PrometheusConfig, PrometheusMeterRegistry}

class MetricsModule extends AbstractModule:

  @Provides
  @Singleton
  def prometheusMeterRegistry: PrometheusMeterRegistry =
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    registry.config().commonTags("application", "cowork-applet")
    List[MeterBinder](
      ClassLoaderMetrics(),
      JvmGcMetrics(),
      JvmMemoryMetrics(),
      JvmThreadMetrics(),
      ProcessorMetrics()
    ).foreach(_.bindTo(registry))
    registry
