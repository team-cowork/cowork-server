package monitoring

import "github.com/prometheus/client_golang/prometheus"

var loginFailures = prometheus.NewCounterVec(prometheus.CounterOpts{
	Name: "cowork_authorization_login_failures_total",
	Help: "DataGSM login failures by validation stage, without provider or identity values.",
}, []string{"stage"})

func init() { prometheus.MustRegister(loginFailures) }

func RecordLoginFailure(stage string) { loginFailures.WithLabelValues(stage).Inc() }
