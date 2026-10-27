package config

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
	"github.com/segmentio/kafka-go/sasl"
	"github.com/segmentio/kafka-go/sasl/scram"
	"github.com/twmb/franz-go/pkg/kgo"
	franzsasl "github.com/twmb/franz-go/pkg/sasl"
	franzscram "github.com/twmb/franz-go/pkg/sasl/scram"
)

type KafkaSecurity struct {
	tls       *tls.Config
	sasl      sasl.Mechanism
	franzSASL franzsasl.Mechanism
}

func loadKafkaSecurity() (KafkaSecurity, error) {
	var security KafkaSecurity
	protocol := strings.ToUpper(os.Getenv("KAFKA_SECURITY_PROTOCOL"))
	if protocol == "" {
		protocol = "PLAINTEXT"
	}
	switch protocol {
	case "PLAINTEXT":
		if os.Getenv("KAFKA_SASL_USERNAME") != "" || os.Getenv("KAFKA_SASL_PASSWORD") != "" || os.Getenv("KAFKA_SSL_CA_LOCATION") != "" {
			return security, fmt.Errorf("Kafka credentials and CA require KAFKA_SECURITY_PROTOCOL=SSL or SASL_SSL")
		}
		return security, nil
	case "SSL", "SASL_SSL":
		security.tls = &tls.Config{MinVersion: tls.VersionTLS12}
	default:
		return security, fmt.Errorf("KAFKA_SECURITY_PROTOCOL must be PLAINTEXT, SSL or SASL_SSL")
	}
	if path := os.Getenv("KAFKA_SSL_CA_LOCATION"); path != "" {
		pem, err := os.ReadFile(path)
		if err != nil {
			return security, fmt.Errorf("read KAFKA_SSL_CA_LOCATION: %w", err)
		}
		roots, err := x509.SystemCertPool()
		if err != nil || roots == nil {
			roots = x509.NewCertPool()
		}
		if !roots.AppendCertsFromPEM(pem) {
			return security, fmt.Errorf("KAFKA_SSL_CA_LOCATION contains no CA certificates")
		}
		security.tls.RootCAs = roots
	}
	if protocol == "SSL" {
		if os.Getenv("KAFKA_SASL_USERNAME") != "" || os.Getenv("KAFKA_SASL_PASSWORD") != "" {
			return security, fmt.Errorf("Kafka SASL credentials require KAFKA_SECURITY_PROTOCOL=SASL_SSL")
		}
		return security, nil
	}
	username, password := os.Getenv("KAFKA_SASL_USERNAME"), os.Getenv("KAFKA_SASL_PASSWORD")
	if username == "" || password == "" {
		return security, fmt.Errorf("KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required")
	}
	mechanism := strings.ToUpper(os.Getenv("KAFKA_SASL_MECHANISM"))
	if mechanism == "" {
		mechanism = "SCRAM-SHA-512"
	}
	algorithm := scram.SHA512
	switch mechanism {
	case "SCRAM-SHA-256":
		algorithm = scram.SHA256
		security.franzSASL = franzscram.Auth{User: username, Pass: password}.AsSha256Mechanism()
	case "SCRAM-SHA-512":
		security.franzSASL = franzscram.Auth{User: username, Pass: password}.AsSha512Mechanism()
	default:
		return security, fmt.Errorf("KAFKA_SASL_MECHANISM must be SCRAM-SHA-256 or SCRAM-SHA-512")
	}
	var err error
	security.sasl, err = scram.Mechanism(algorithm, username, password)
	return security, err
}

func (s KafkaSecurity) Dialer() *kafka.Dialer {
	return &kafka.Dialer{Timeout: 10 * time.Second, DualStack: true, TLS: s.tls, SASLMechanism: s.sasl}
}

func (s KafkaSecurity) Transport() *kafka.Transport {
	return &kafka.Transport{TLS: s.tls, SASL: s.sasl}
}

func (s KafkaSecurity) FranzOptions() []kgo.Opt {
	var options []kgo.Opt
	if s.tls != nil {
		options = append(options, kgo.DialTLSConfig(s.tls))
	}
	if s.franzSASL != nil {
		options = append(options, kgo.SASL(s.franzSASL))
	}
	return options
}
