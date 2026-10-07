package controlplane

import (
	"net/http"
	"testing"
)

type recordingTransport struct{ called bool }

func (r *recordingTransport) RoundTrip(*http.Request) (*http.Response, error) {
	r.called = true
	return &http.Response{StatusCode: http.StatusOK, Body: http.NoBody}, nil
}

func TestTransportAllowsOnlyHTTPSOrPrivateIPv4HTTPInProduction(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name    string
		url     string
		allowed bool
	}{
		{name: "10/8 private IPv4 HTTP is allowed", url: "http://10.0.0.93:8761/eureka/", allowed: true},
		{name: "172.16/12 private IPv4 HTTP is allowed", url: "http://172.31.255.255:8761", allowed: true},
		{name: "192.168/16 private IPv4 HTTP is allowed", url: "http://192.168.0.1:8761", allowed: true},
		{name: "HTTPS host name is allowed", url: "https://config.example.com:8761", allowed: true},
		{name: "172.32 outside RFC1918 is rejected", url: "http://172.32.0.1:8761", allowed: false},
		{name: "link-local IPv4 is rejected", url: "http://169.254.1.1:8761", allowed: false},
		{name: "public IPv4 is rejected", url: "http://8.8.8.8:8761", allowed: false},
		{name: "loopback IPv4 is rejected", url: "http://127.0.0.1:8761", allowed: false},
		{name: "HTTP host name is rejected", url: "http://config.internal:8761", allowed: false},
		{name: "IPv4-mapped IPv6 is rejected", url: "http://[::ffff:10.0.0.1]:8761", allowed: false},
		{name: "private IPv6 is rejected", url: "http://[fd00::1]:8761", allowed: false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			t.Parallel()
			base := &recordingTransport{}
			client := transport{base: base, username: "user", password: "secret", profile: "prod"}
			req, err := http.NewRequest(http.MethodGet, tt.url, nil)
			if err != nil {
				t.Fatalf("NewRequest() error = %v", err)
			}

			_, err = client.RoundTrip(req)

			if (err == nil) != tt.allowed || base.called != tt.allowed {
				t.Fatalf("RoundTrip(%s) error = %v, sent = %v, want allowed = %v", tt.url, err, base.called, tt.allowed)
			}
		})
	}
}
