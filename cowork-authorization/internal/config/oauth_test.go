package config

import "testing"

func TestRegisteredRedirectURIs(t *testing.T) {
	for _, tc := range []struct {
		value string
		valid bool
	}{
		{"https://web.example/api/auth/callback,http://localhost:3000/api/auth/callback,http://localhost:19420/callback,cowork://oauth/callback", true},
		{"", false}, {"http://web.example/callback", false}, {"https://user:secret@web.example/callback", false}, {"https://web.example/callback#fragment", false}, {"javascript:alert(1)", false},
	} {
		_, err := parseRedirectURIs(tc.value)
		if (err == nil) != tc.valid {
			t.Fatalf("valid=%v err=%v", tc.valid, err)
		}
	}
}
