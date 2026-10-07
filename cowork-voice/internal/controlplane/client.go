package controlplane

import (
	"fmt"
	"net/http"
	"net/netip"
	"os"
	"time"
)

type transport struct {
	base     http.RoundTripper
	username string
	password string
	profile  string
}

func (t transport) RoundTrip(req *http.Request) (*http.Response, error) {
	if req.URL.User != nil || req.URL.Host == "" || (req.URL.Scheme != "http" && req.URL.Scheme != "https") {
		return nil, fmt.Errorf("use a Config/Eureka HTTP(S) URL without credentials")
	}
	if t.profile == "prod" && req.URL.Scheme != "https" && !privateIPv4(req.URL.Hostname()) {
		return nil, fmt.Errorf("use HTTPS or a private IPv4 HTTP URL for production Config/Eureka")
	}
	if t.username == "" || t.password == "" {
		return nil, fmt.Errorf("provide CONFIG_CLIENT_USERNAME and CONFIG_CLIENT_PASSWORD")
	}
	copy := req.Clone(req.Context())
	copy.SetBasicAuth(t.username, t.password)
	return t.base.RoundTrip(copy)
}

// privateIPv4 accepts only RFC1918 IPv4 literals; host names are rejected so DNS cannot redirect plaintext traffic.
func privateIPv4(host string) bool {
	addr, err := netip.ParseAddr(host)
	return err == nil && addr.Is4() && addr.IsPrivate()
}

func NewClient() *http.Client {
	return &http.Client{
		Timeout: 8 * time.Second,
		Transport: transport{base: http.DefaultTransport.(*http.Transport).Clone(),
			username: os.Getenv("CONFIG_CLIENT_USERNAME"), password: os.Getenv("CONFIG_CLIENT_PASSWORD"), profile: os.Getenv("APP_PROFILE")},
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
	}
}
