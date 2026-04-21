package monitoring

import (
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus"
)

var (
	httpRequestsTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "cowork_authorization_http_requests_total",
			Help: "Total number of HTTP requests handled by cowork-authorization.",
		},
		[]string{"method", "route", "status"},
	)
	httpRequestDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "cowork_authorization_http_request_duration_seconds",
			Help:    "HTTP request latency for cowork-authorization.",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"method", "route", "status"},
	)
	httpRequestsInFlight = prometheus.NewGaugeVec(
		prometheus.GaugeOpts{
			Name: "cowork_authorization_http_requests_in_flight",
			Help: "Number of in-flight HTTP requests in cowork-authorization.",
		},
		[]string{"method"},
	)
)

func init() {
	prometheus.MustRegister(httpRequestsTotal, httpRequestDuration, httpRequestsInFlight)
}

func HTTPMetricsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		method := c.Request.Method
		httpRequestsInFlight.WithLabelValues(method).Inc()
		start := time.Now()
		defer func() {
			httpRequestsInFlight.WithLabelValues(method).Dec()
		}()

		c.Next()

		route := normalizedRoute(c.FullPath(), c.Request.URL.Path)
		if shouldSkipRoute(route) {
			return
		}

		status := strconv.Itoa(c.Writer.Status())
		httpRequestsTotal.WithLabelValues(method, route, status).Inc()
		httpRequestDuration.WithLabelValues(method, route, status).Observe(time.Since(start).Seconds())
	}
}

func normalizedRoute(fullPath string, rawPath string) string {
	if fullPath != "" {
		return fullPath
	}
	if rawPath == "" {
		return "unknown"
	}
	return rawPath
}

func shouldSkipRoute(route string) bool {
	switch route {
	case "/metrics", "/health", "/swagger/*any":
		return true
	default:
		return false
	}
}
