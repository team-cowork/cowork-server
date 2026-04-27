package monitoring

import (
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

var (
	httpRequestsTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cowork_notification_http_requests_total",
			Help: "Total number of HTTP requests handled by cowork-notification.",
		},
		[]string{"method", "route", "status"},
	)
	httpRequestDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "cowork_notification_http_request_duration_seconds",
			Help:    "HTTP request latency for cowork-notification.",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "route", "status"},
	)
	httpRequestsInFlight = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "cowork_notification_http_requests_in_flight",
			Help: "Number of in-flight HTTP requests in cowork-notification.",
		},
		[]string{"method"},
	)
)

func init() {
	prometheus.MustRegister(httpRequestsTotal, httpRequestDuration, httpRequestsInFlight)
}

func Handler(w http.ResponseWriter, r *http.Request) {
	promhttp.Handler().ServeHTTP(w, r)
}

func HTTPMetricsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		method := r.Method
		httpRequestsInFlight.WithLabelValues(method).Inc()
		start := time.Now()
		defer func() {
			httpRequestsInFlight.WithLabelValues(method).Dec()
		}()

		rw := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rw, r)

		route := chi.RouteContext(r.Context()).RoutePattern()
		if route == "" {
			route = "not_found"
		}
		if shouldSkipRoute(route) {
			return
		}

		status := strconv.Itoa(rw.status)
		httpRequestsTotal.WithLabelValues(method, route, status).Inc()
		httpRequestDuration.WithLabelValues(method, route, status).Observe(time.Since(start).Seconds())
	})
}

func shouldSkipRoute(route string) bool {
	switch route {
	case "/metrics", "/health", "/swagger/*":
		return true
	default:
		return false
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}
