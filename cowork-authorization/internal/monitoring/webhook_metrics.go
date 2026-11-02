package monitoring

import "github.com/prometheus/client_golang/prometheus"

var (
	webhookResults = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_requests_total", Help: "Webhook requests by acceptance result.",
	}, []string{"result"})
	webhookPublished = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_outbox_completed_total", Help: "Webhook outbox rows removed after publication and database commit.",
	})
	webhookPublishFailures = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_outbox_publish_failures_total", Help: "Failed publication attempts for webhook outbox rows.",
	})
	webhookPending = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_outbox_pending", Help: "Shared database webhook outbox rows, aggregate replicas with max rather than sum.",
	})
	webhookOldest = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_outbox_oldest_seconds", Help: "Age of the oldest pending webhook outbox row in the shared database.",
	})
	webhookExpired = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_inbox_expired", Help: "Expired receipts still present in the shared database.",
	})
	webhookRetained = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_inbox_retained", Help: "Expired receipts protected by a pending outbox row in the shared database.",
	})
	webhookObserved = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_observation_success", Help: "Whether the latest shared-database webhook metrics refresh succeeded.",
	})
	webhookObservationTime = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_observation_timestamp_seconds", Help: "Unix timestamp of the last successful webhook backlog observation.",
	})
	webhookCleaned = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_inbox_deleted_total", Help: "Expired webhook receipts deleted without pending deliveries.",
	})
	webhookMaintenanceFailures = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_maintenance_failures_total", Help: "Webhook maintenance failures by operation.",
	}, []string{"operation"})
	webhookStreamConnected = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_stream_connected", Help: "Whether the Redis webhook stream consumer can read the stream.",
	})
	webhookStreamObserved = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_stream_observation_success", Help: "Whether webhook stream backlog metrics have been observed since the last connection failure.",
	})
	webhookStreamLength = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_stream_entries", Help: "Webhook entries retained in the Redis stream.",
	})
	webhookStreamPending = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_stream_pending", Help: "Webhook stream entries delivered but not yet acknowledged.",
	})
	webhookStreamOldest = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "cowork_authorization_webhook_stream_oldest_seconds", Help: "Age of the oldest retained webhook stream entry.",
	})
	webhookStreamProcessed = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_stream_processed_total", Help: "Webhook stream entries successfully processed, acknowledged, and deleted by result.",
	}, []string{"result"})
	webhookStreamFailures = prometheus.NewCounterVec(prometheus.CounterOpts{
		Name: "cowork_authorization_webhook_stream_failures_total", Help: "Webhook stream processing failures by safe category.",
	}, []string{"reason"})
)

func init() {
	prometheus.MustRegister(webhookResults, webhookPublished, webhookPublishFailures, webhookPending, webhookOldest,
		webhookExpired, webhookRetained, webhookObserved, webhookObservationTime, webhookCleaned, webhookMaintenanceFailures,
		webhookStreamConnected, webhookStreamObserved, webhookStreamLength, webhookStreamPending, webhookStreamOldest,
		webhookStreamProcessed, webhookStreamFailures)
}

func RecordWebhookResult(result string) { webhookResults.WithLabelValues(result).Inc() }
func RecordWebhookPublished(count int)  { webhookPublished.Add(float64(count)) }
func RecordWebhookPublishFailure()      { webhookPublishFailures.Inc() }
func RecordWebhookCleanup(count int64)  { webhookCleaned.Add(float64(count)) }
func RecordWebhookMaintenanceFailure(operation string) {
	webhookMaintenanceFailures.WithLabelValues(operation).Inc()
}
func WebhookObservationFailed() { webhookObserved.Set(0) }

func ObserveWebhookBacklog(pending int64, oldest float64, expired, retained int64) {
	webhookPending.Set(float64(pending))
	webhookOldest.Set(oldest)
	webhookExpired.Set(float64(expired))
	webhookRetained.Set(float64(retained))
	webhookObserved.Set(1)
	webhookObservationTime.SetToCurrentTime()
}

func WebhookStreamConnected(connected bool) {
	if connected {
		webhookStreamConnected.Set(1)
	} else {
		webhookStreamConnected.Set(0)
		webhookStreamObserved.Set(0)
	}
}

func ObserveWebhookStream(length, pending int64, oldestSeconds float64) {
	webhookStreamLength.Set(float64(length))
	webhookStreamPending.Set(float64(pending))
	webhookStreamOldest.Set(oldestSeconds)
	webhookStreamObserved.Set(1)
}

func RecordWebhookStreamProcessed(result string) {
	webhookStreamProcessed.WithLabelValues(result).Inc()
}
func RecordWebhookStreamFailure(reason string) { webhookStreamFailures.WithLabelValues(reason).Inc() }
