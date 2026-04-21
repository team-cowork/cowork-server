package monitoring

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	chimiddleware "github.com/go-chi/chi/v5/middleware"
	"github.com/prometheus/client_golang/prometheus"
)

var (
	httpRequestsTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cowork_voice_http_requests_total",
			Help: "Total number of HTTP requests handled by cowork-voice.",
		},
		[]string{"method", "route", "status"},
	)
	httpRequestDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "cowork_voice_http_request_duration_seconds",
			Help:    "HTTP request latency for cowork-voice.",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "route", "status"},
	)
	httpRequestsInFlight = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "cowork_voice_http_requests_in_flight",
			Help: "Number of in-flight HTTP requests in cowork-voice.",
		},
		[]string{"method"},
	)
)

func init() {
	prometheus.MustRegister(httpRequestsTotal, httpRequestDuration, httpRequestsInFlight)
}

func HTTPMetricsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ww := chimiddleware.NewWrapResponseWriter(w, r.ProtoMajor)
		start := time.Now()

		next.ServeHTTP(ww, r)

		route := normalizedRoute(chi.RouteContext(r.Context()).RoutePattern(), r.URL.Path)
		if shouldSkipRoute(route) {
			return
		}

		method := r.Method
		status := strconv.Itoa(ww.Status())
		httpRequestsTotal.WithLabelValues(method, route, status).Inc()
		httpRequestDuration.WithLabelValues(method, route, status).Observe(time.Since(start).Seconds())
	})
}

func HTTPInFlightMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if shouldSkipRoute(r.URL.Path) {
			next.ServeHTTP(w, r)
			return
		}

		method := r.Method
		httpRequestsInFlight.WithLabelValues(method).Inc()
		defer httpRequestsInFlight.WithLabelValues(method).Dec()

		next.ServeHTTP(w, r)
	})
}

func normalizedRoute(pattern string, rawPath string) string {
	if pattern != "" {
		return strings.TrimSuffix(pattern, "/*")
	}
	return "not_found"
}

func shouldSkipRoute(route string) bool {
	return route == "/metrics" || route == "/health" || strings.HasPrefix(route, "/swagger")
}
