package handler

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestDevSignInRequiresGatewayVerificationBeforeReadingAccountCredentials(t *testing.T) {
	for _, tc := range []struct {
		marker string
		status int
	}{
		{"", http.StatusForbidden},
		{"false", http.StatusForbidden},
		{"true", http.StatusUnsupportedMediaType},
	} {
		response := httptest.NewRecorder()
		c, _ := gin.CreateTestContext(response)
		c.Request = httptest.NewRequest(http.MethodPost, "/internal/dev-signin", nil)
		c.Request.Header.Set("X-Cowork-Dev-Signin-Verified", tc.marker)
		new(AuthHandler).DevSignIn(c)
		if response.Code != tc.status {
			t.Errorf("verification %q: got HTTP %d, want %d", tc.marker, response.Code, tc.status)
		}
	}
}
