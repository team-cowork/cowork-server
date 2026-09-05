package service

import (
	"fmt"
	"testing"
	"time"

	"github.com/cowork/authorization/internal/config"
	"github.com/golang-jwt/jwt/v5"
)

func newUnitTokenService(accessExpire, refreshExpire time.Duration) *TokenService {
	return NewTokenService(&config.AppConfig{
		JWTSecret:        "unit-test-secret",
		JWTAccessExpire:  accessExpire,
		JWTRefreshExpire: refreshExpire,
	})
}

func TestGenerateAccessTokenEncodesClaimsAndIsVerifiableWithConfiguredSecret(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name    string
		userID  int64
		email   string
		role    string
		gsmRole string
	}{
		{name: "member", userID: 7, email: "user@example.com", role: "MEMBER", gsmRole: "GENERAL_STUDENT"},
		{name: "admin", userID: 42, email: "admin@example.com", role: "ADMIN", gsmRole: "STUDENT_COUNCIL"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			svc := newUnitTokenService(30*time.Minute, 24*time.Hour)
			signed, err := svc.GenerateAccessToken(test.userID, test.email, test.role, test.gsmRole)
			if err != nil {
				t.Fatalf("GenerateAccessToken() error = %v", err)
			}
			if signed == "" {
				t.Fatal("GenerateAccessToken() returned empty token")
			}

			claims := &Claims{}
			parser := jwt.NewParser(jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Name}))
			token, err := parser.ParseWithClaims(signed, claims, func(_ *jwt.Token) (any, error) {
				return []byte(svc.cfg.JWTSecret), nil
			})
			if err != nil || !token.Valid {
				t.Fatalf("parse signed access token: valid=%v err=%v", token != nil && token.Valid, err)
			}

			wantSubject := fmt.Sprintf("%d", test.userID)
			if claims.Subject != wantSubject {
				t.Errorf("Subject = %q, want %q", claims.Subject, wantSubject)
			}
			if claims.Email != test.email || claims.Role != test.role || claims.GsmRole != test.gsmRole {
				t.Errorf("claims = %+v, want email=%q role=%q gsmRole=%q", claims, test.email, test.role, test.gsmRole)
			}
			if claims.ExpiresAt == nil || claims.IssuedAt == nil {
				t.Fatal("claims missing IssuedAt/ExpiresAt")
			}
			if got, want := claims.ExpiresAt.Sub(claims.IssuedAt.Time), 30*time.Minute; got != want {
				t.Errorf("token lifetime = %s, want %s", got, want)
			}
		})
	}
}

func TestGenerateAccessTokenRejectsWrongSigningSecret(t *testing.T) {
	t.Parallel()

	svc := newUnitTokenService(time.Minute, time.Hour)
	signed, err := svc.GenerateAccessToken(1, "user@example.com", "MEMBER", "GENERAL_STUDENT")
	if err != nil {
		t.Fatalf("GenerateAccessToken() error = %v", err)
	}

	claims := &Claims{}
	parser := jwt.NewParser(jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Name}))
	_, err = parser.ParseWithClaims(signed, claims, func(_ *jwt.Token) (any, error) {
		return []byte("a-completely-different-secret"), nil
	})
	if err == nil {
		t.Fatal("ParseWithClaims() expected signature verification failure")
	}
}

func TestGenerateRefreshTokenReturnsHighEntropyRawValueAndDeterministicHash(t *testing.T) {
	t.Parallel()

	svc := newUnitTokenService(time.Minute, time.Hour)

	rawA, hashA, err := svc.GenerateRefreshToken()
	if err != nil {
		t.Fatalf("GenerateRefreshToken() error = %v", err)
	}
	rawB, hashB, err := svc.GenerateRefreshToken()
	if err != nil {
		t.Fatalf("GenerateRefreshToken() error = %v", err)
	}

	if rawA == "" || hashA == "" {
		t.Fatal("GenerateRefreshToken() returned empty raw token or hash")
	}
	if rawA == rawB {
		t.Fatal("GenerateRefreshToken() must not return the same raw token twice")
	}
	if hashA == hashB {
		t.Fatal("GenerateRefreshToken() must not return the same hash twice")
	}
	if hashA == rawA {
		t.Fatal("stored hash must not equal the raw token")
	}
	if got, want := hashA, HashToken(rawA); got != want {
		t.Errorf("hash for raw token = %q, want deterministic HashToken() = %q", got, want)
	}
}

func TestHashTokenIsDeterministicAndSensitiveToInput(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		a    string
		b    string
		want bool // want a's hash == b's hash
	}{
		{name: "same input produces same hash", a: "raw-token-value", b: "raw-token-value", want: true},
		{name: "different input produces different hash", a: "raw-token-value", b: "another-token-value", want: false},
		{name: "empty input is still hashed deterministically", a: "", b: "", want: true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			got := HashToken(test.a) == HashToken(test.b)
			if got != test.want {
				t.Errorf("HashToken(%q) == HashToken(%q) = %v, want %v", test.a, test.b, got, test.want)
			}
		})
	}
}
