package config

import "testing"

func TestRegisteredRedirectURIs(t *testing.T) {
	for _, tc := range []struct {
		value string
		valid bool
	}{
		{"https://web.example/api/auth/callback,http://localhost:3000/api/auth/callback,http://localhost:19420/callback,cowork://oauth/callback", true},
		{" , https://web.example/api/auth/callback, ,cowork://oauth/callback, ", true},
		{" , , ", false},
		{"", false}, {"http://web.example/callback", false}, {"https://user:secret@web.example/callback", false}, {"https://web.example/callback#fragment", false}, {"javascript:alert(1)", false},
	} {
		_, err := parseRedirectURIs("UNIT_REDIRECT_URIS", tc.value)
		if (err == nil) != tc.valid {
			t.Fatalf("valid=%v err=%v", tc.valid, err)
		}
	}
}

func TestGitHubLoginRequiresCompleteConfiguration(t *testing.T) {
	for _, tc := range []struct {
		id, secret, redirects string
		enabled, valid        bool
	}{
		{"", "", "", false, true},
		{"client", "secret", "https://web.example/auth/github/callback", true, true},
		{"client", "", "https://web.example/auth/github/callback", false, false},
		{"", "secret", "", false, false},
		{"client", "secret", "", false, false},
	} {
		t.Setenv("GITHUB_LOGIN_CLIENT_ID", tc.id)
		t.Setenv("GITHUB_LOGIN_CLIENT_SECRET", tc.secret)
		t.Setenv("GITHUB_LOGIN_REDIRECT_URIS", tc.redirects)
		cfg := &AppConfig{}
		err := cfg.loadGitHubLogin()
		if (err == nil) != tc.valid || (len(cfg.GitHubRedirectURIs) > 0) != tc.enabled {
			t.Fatalf("case=%+v redirects=%v err=%v", tc, cfg.GitHubRedirectURIs, err)
		}
	}
}
