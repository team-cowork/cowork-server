package monitoring

import "github.com/prometheus/client_golang/prometheus"

var (
	fcmDeliveryOutcomesTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cowork_notification_fcm_delivery_outcomes_total",
			Help: "FCM per-token delivery outcomes, labeled by outcome and attempt source (initial send vs retry worker).",
		},
		[]string{"outcome", "source"},
	)
	fcmDeliveryQuarantinedTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cowork_notification_fcm_delivery_quarantined_total",
			Help: "FCM deliveries that exhausted their retry ceiling without succeeding, labeled by error class.",
		},
		[]string{"error_class"},
	)
	fcmDeliveryPending = prometheus.NewGauge(
		prometheus.GaugeOpts{
			Name: "cowork_notification_fcm_delivery_pending",
			Help: "Current PENDING_RETRY/IN_PROGRESS backlog size in tb_notification_delivery_retry.",
		},
	)
)

func init() {
	prometheus.MustRegister(fcmDeliveryOutcomesTotal, fcmDeliveryQuarantinedTotal, fcmDeliveryPending)
}

func RecordFCMDeliveryOutcome(outcome, source string) {
	fcmDeliveryOutcomesTotal.WithLabelValues(outcome, source).Inc()
}

func RecordFCMDeliveryQuarantined(errorClass string) {
	fcmDeliveryQuarantinedTotal.WithLabelValues(errorClass).Inc()
}

func SetFCMDeliveryPending(count float64) {
	fcmDeliveryPending.Set(count)
}
