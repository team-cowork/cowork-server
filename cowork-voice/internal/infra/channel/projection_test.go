package channel

import "testing"

func TestParseVoiceChannelSetting(t *testing.T) {
	const topic = "preference.voice-channel-setting.state.v1"
	s, err := parseState(topic, "11", []byte(`{"eventType":"UPSERT","schemaVersion":1,"channelId":11,"maxParticipants":5,"bitrate":64000,"occurredAt":"2026-10-26T00:00:00Z"}`))
	if err != nil {
		t.Fatalf("parseState() error = %v", err)
	}
	if s.Key != "voice-setting:11" || s.Kind != "voice-setting" || s.MaxParticipants != 5 || s.Bitrate == nil || *s.Bitrate != 64000 {
		t.Fatalf("unexpected state: %+v", s)
	}
	invalid := []string{
		`{"eventType":"UPSERT","schemaVersion":1,"channelId":11,"bitrate":null,"occurredAt":"2026-10-26T00:00:00Z"}`,
		`{"eventType":"UPSERT","schemaVersion":1,"channelId":11,"maxParticipants":-1,"occurredAt":"2026-10-26T00:00:00Z"}`,
		`{"eventType":"DELETE","schemaVersion":1,"channelId":11,"maxParticipants":0,"occurredAt":"2026-10-26T00:00:00Z"}`,
	}
	for _, payload := range invalid {
		if _, err := parseState(topic, "11", []byte(payload)); err == nil {
			t.Fatalf("parseState(%s) accepted invalid setting", payload)
		}
	}
	if _, err := parseState(topic, "12", []byte(`{"eventType":"UPSERT","schemaVersion":1,"channelId":11,"maxParticipants":0,"occurredAt":"2026-10-26T00:00:00Z"}`)); err == nil {
		t.Fatal("parseState() accepted mismatched key")
	}
}

func TestParseChannelAccess(t *testing.T) {
	const topic = "preference.channel-access.state.v1"
	payload := func(auth string, connect bool) []byte {
		return []byte(`{"eventType":"UPSERT","channelId":11,"accountId":217,"teamId":3,` +
			`"permissions":{"voice_connect":` + map[bool]string{true: "true", false: "false"}[connect] + `,"voice_speak":false},` +
			`"readPrivate":false,"sourceMembershipVersion":"2026-11-25T00:00:02Z","sourceAuthorizationVersion":` + auth + `,` +
			`"occurredAt":"2026-11-25T00:00:03.123456Z"}`)
	}
	s, err := parseState(topic, "11:217", payload(`"2026-11-25T00:00:01.000001Z"`, true))
	if err != nil {
		t.Fatalf("parseState() error = %v", err)
	}
	if s.Key != "access:11:217" || s.Kind != "access" || s.TeamID != 3 || s.ChannelID != 11 || s.UserID != 217 ||
		!s.Permissions["voice_connect"] || s.AuthorizationVersion != 1_795_564_801_000_001 {
		t.Fatalf("unexpected state: %+v", s)
	}
	if _, err := parseState(topic, "11:217", payload("null", false)); err != nil {
		t.Fatalf("deny without authorization version rejected: %v", err)
	}
	invalid := map[string][]byte{
		"allow without authorization version": payload("null", true),
		"authorization after membership":      payload(`"2026-11-25T00:00:05Z"`, true),
		"missing readPrivate":                 []byte(`{"eventType":"UPSERT","channelId":11,"accountId":217,"teamId":3,"permissions":{"voice_connect":false},"sourceMembershipVersion":"1970-01-01T00:00:00Z","sourceAuthorizationVersion":null,"occurredAt":"2026-11-25T00:00:03Z"}`),
	}
	for name, body := range invalid {
		if _, err := parseState(topic, "11:217", body); err == nil {
			t.Errorf("%s: parseState() accepted invalid record", name)
		}
	}
	if _, err := parseState(topic, "11:218", payload(`"2026-11-25T00:00:01Z"`, true)); err == nil {
		t.Fatal("parseState() accepted mismatched key")
	}
}

func TestParseTeamMemberAuthorizationVersion(t *testing.T) {
	const topic = "team.member.event"
	s, err := parseState(topic, "3:217", []byte(`{"eventType":"UPSERT","teamId":3,"userId":217,"role":"MEMBER","occurredAt":"2026-11-25T00:00:02Z","authorizationVersion":"2026-11-25T00:00:01.000001999Z"}`))
	if err != nil {
		t.Fatalf("parseState() error = %v", err)
	}
	if s.Key != "member:3:217" || s.AuthorizationVersion != 1_795_564_801_000_001 {
		t.Fatalf("unexpected state: %+v", s)
	}
	for _, body := range []string{
		`{"eventType":"UPSERT","teamId":3,"userId":217,"role":"MEMBER","occurredAt":"2026-11-25T00:00:02Z"}`,
		`{"eventType":"UPSERT","teamId":3,"userId":217,"role":"MEMBER","occurredAt":"2026-11-25T00:00:02Z","authorizationVersion":"2026-11-25T00:00:03Z"}`,
	} {
		if _, err := parseState(topic, "3:217", []byte(body)); err == nil {
			t.Errorf("parseState(%s) accepted invalid member", body)
		}
	}
}

func TestPermissionsFor(t *testing.T) {
	public := false
	ch := State{Kind: "channel", TeamID: 3, ChannelID: 11, ChannelType: "VOICE", IsPrivate: &public}
	member := &State{Kind: "member", TeamID: 3, UserID: 217, AuthorizationVersion: 5}
	decision := func(version int64, permissions map[string]bool) *State {
		return &State{Kind: "access", TeamID: 3, ChannelID: 11, UserID: 217, AuthorizationVersion: version, Permissions: permissions}
	}
	allowed := map[string]bool{"voice_connect": true, "voice_speak": true, "screen_share": false}
	tests := []struct {
		name     string
		member   *State
		decision *State
		want     bool
	}{
		{"current decision grants access", member, decision(5, allowed), true},
		{"missing decision denies", member, nil, false},
		{"missing member denies", nil, decision(5, allowed), false},
		{"deleted member denies", &State{Kind: "member", TeamID: 3, UserID: 217, AuthorizationVersion: 5, Deleted: true}, decision(5, allowed), false},
		{"decision from older authorization denies", member, decision(4, allowed), false},
		{"decision from newer authorization denies", member, decision(6, allowed), false},
		{"member without authorization version denies", &State{Kind: "member", TeamID: 3, UserID: 217}, decision(0, allowed), false},
		{"connect denial denies other actions", member, decision(5, map[string]bool{"voice_connect": false, "voice_speak": true}), false},
		{"decision for another team denies", member, &State{Kind: "access", TeamID: 4, ChannelID: 11, AuthorizationVersion: 5, Permissions: allowed}, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := permissionsFor(ch, tt.member, tt.decision)
			if got["voice_connect"] != tt.want {
				t.Fatalf("voice_connect = %t, want %t", got["voice_connect"], tt.want)
			}
			if tt.want && (!got["voice_speak"] || got["screen_share"]) {
				t.Fatalf("unexpected action permissions: %v", got)
			}
		})
	}
	t.Run("private channel needs private access even with a public decision", func(t *testing.T) {
		private := true
		for _, visibility := range []*bool{&private, nil} {
			privateCh := ch
			privateCh.IsPrivate = visibility
			if permissionsFor(privateCh, member, decision(5, allowed))["voice_connect"] {
				t.Fatal("public decision granted a private channel")
			}
			granted := decision(5, allowed)
			granted.ReadPrivate = true
			if !permissionsFor(privateCh, member, granted)["voice_connect"] {
				t.Fatal("private access was denied")
			}
		}
	})
}
