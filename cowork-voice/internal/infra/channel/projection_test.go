package channel

import "testing"

func TestVoiceConnectPermission(t *testing.T) {
	isPrivate := false
	ch := State{IsPrivate: &isPrivate, Kind: "channel", ChannelID: 11, TeamID: 3, ChannelType: "VOICE"}
	member := State{Kind: "member", TeamID: 3, UserID: 217, Role: "MEMBER"}
	assignment := State{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 10, Version: 2}
	allow := State{Kind: "role", TeamID: 3, RoleID: 10, Priority: 10, Permissions: map[string]bool{"voice_connect": true, "message_read": false}}
	tests := []struct {
		name   string
		states []State
		want   bool
	}{
		{"team membership alone does not grant access", []State{member}, false},
		{"role grants voice without channel membership or message read", []State{member, assignment, allow}, true},
		{"role grant requires current team membership", []State{assignment, allow}, false},
		{"owner bypasses custom roles", []State{{Kind: "member", TeamID: 3, UserID: 217, Role: "OWNER"}}, true},
		{"deleted role assignment revokes access", []State{member, allow, {Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 10, Deleted: true, Version: 3}}, false},
		{"member fence rejects old assignment", []State{member, assignment, allow, {Kind: "member-fence", TeamID: 3, UserID: 217, Version: 2}}, false},
		{"channel override denies base allow", []State{member, assignment, allow, {Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 10, Permissions: map[string]bool{"voice_connect": false}}}, false},
		{"different channel override has no effect", []State{member, assignment, allow, {Kind: "policy", TeamID: 3, ChannelID: 12, RoleID: 10, Permissions: map[string]bool{"voice_connect": false}}}, true},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := canConnect(ch, 217, test.states); got != test.want {
				t.Fatalf("canConnect = %t, want %t", got, test.want)
			}
		})
	}
	t.Run("base permissions combine regardless of priority", func(t *testing.T) {
		for _, priority := range []int{9, 10, 11} {
			states := []State{member, assignment, allow,
				{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 20, Version: 2},
				{Kind: "role", TeamID: 3, RoleID: 20, Priority: priority, Permissions: map[string]bool{"voice_connect": false}},
			}
			if got := canConnect(ch, 217, states); got != true {
				t.Errorf("deny priority %d: canConnect = %t", priority, got)
			}
		}
	})
}

func TestPrivateVoiceConnectPermission(t *testing.T) {
	private := true
	ch := State{Kind: "channel", ChannelID: 11, TeamID: 3, ChannelType: "VOICE", IsPrivate: &private}
	states := []State{
		{Kind: "member", TeamID: 3, UserID: 217, Role: "MEMBER"},
		{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 10, Version: 2},
		{Kind: "role", TeamID: 3, RoleID: 10, Priority: 10, Permissions: map[string]bool{"voice_connect": true}},
	}
	if canConnect(ch, 217, states) {
		t.Fatal("private channel inherited a team role permission")
	}
	ch.IsPrivate = nil
	if canConnect(ch, 217, states) {
		t.Fatal("unknown visibility inherited a team role permission")
	}
	ch.IsPrivate = &private
	states = append(states, State{Kind: "policy", TeamID: 3, ChannelID: 12, RoleID: 10, Permissions: map[string]bool{"voice_connect": true}})
	if canConnect(ch, 217, states) {
		t.Fatal("another channel's permission granted access")
	}
	states = append(states, State{Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 10, Permissions: map[string]bool{"voice_connect": true}})
	if !canConnect(ch, 217, states) {
		t.Fatal("explicit voice_connect permission did not grant access")
	}
}

func TestDefaultRoleAndChannelOverwrites(t *testing.T) {
	public, private := false, true
	ch := State{Kind: "channel", TeamID: 3, ChannelID: 11, ChannelType: "VOICE", IsPrivate: &public}
	member := State{Kind: "member", TeamID: 3, UserID: 217, Role: "MEMBER"}
	everyone := State{Kind: "role", TeamID: 3, RoleID: 1, IsDefault: true, Permissions: map[string]bool{"voice_connect": true, "voice_speak": true}}
	custom := State{Kind: "role", TeamID: 3, RoleID: 2, Priority: 100, Permissions: map[string]bool{"screen_share": true}}
	assignment := State{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 2, Version: 2}
	denyEveryone := State{Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 1, Permissions: map[string]bool{"voice_connect": false}}
	allowCustom := State{Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 2, Permissions: map[string]bool{"voice_connect": true}}
	tests := []struct {
		name   string
		states []State
		want   bool
	}{
		{"default applies without assignment", []State{member, everyone}, true},
		{"deleted member loses default", []State{{Kind: "member", TeamID: 3, UserID: 217, Role: "MEMBER", Deleted: true}, everyone}, false},
		{"default requires current membership", []State{everyone}, false},
		{"rejoined member receives default despite old assignment fence", []State{member, everyone, {Kind: "member-fence", TeamID: 3, UserID: 217, Version: 3}}, true},
		{"default changes affect unassigned member", []State{member, {Kind: "role", TeamID: 3, RoleID: 1, IsDefault: true, Permissions: map[string]bool{"voice_connect": false}}}, false},
		{"default deny follows role base union", []State{member, everyone, custom, assignment, denyEveryone}, false},
		{"custom allow follows default deny", []State{member, everyone, custom, assignment, denyEveryone, allowCustom}, true},
		{"admin bypasses overwrite", []State{{Kind: "member", TeamID: 3, UserID: 217, Role: "ADMIN"}, everyone, denyEveryone}, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := canConnect(ch, 217, tt.states); got != tt.want {
				t.Fatalf("canConnect=%v, want %v", got, tt.want)
			}
		})
	}
	ch.IsPrivate = &private
	if canConnect(ch, 217, []State{member, everyone}) {
		t.Fatal("default role exposed private channel")
	}
	if !canConnect(ch, 217, []State{member, everyone, custom, assignment, allowCustom}) {
		t.Fatal("explicit private access denied")
	}
}

func TestVoiceActionPermissions(t *testing.T) {
	public := false
	ch := State{Kind: "channel", TeamID: 3, ChannelID: 11, ChannelType: "VOICE", IsPrivate: &public}
	states := []State{
		{Kind: "member", TeamID: 3, UserID: 217, Role: "MEMBER"},
		{Kind: "role", TeamID: 3, RoleID: 1, IsDefault: true, Permissions: map[string]bool{"voice_connect": true}},
		{Kind: "role", TeamID: 3, RoleID: 2, Priority: 1, Permissions: map[string]bool{"voice_speak": true, "screen_share": true, "live_broadcast": true}},
		{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 2, Version: 2},
		{Kind: "role", TeamID: 3, RoleID: 3, Priority: 100},
		{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 3, Version: 2},
		{Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 2, Permissions: map[string]bool{"voice_speak": false}},
		{Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 3, Permissions: map[string]bool{"voice_speak": true, "screen_share": false}},
	}
	got := permissionsFor(ch, 217, states)
	if !got["voice_speak"] || got["screen_share"] || !got["live_broadcast"] {
		t.Fatalf("unexpected permissions: %v", got)
	}
	states = append(states, State{Kind: "policy", TeamID: 3, ChannelID: 11, RoleID: 1, Permissions: map[string]bool{"voice_connect": false}})
	if len(permissionsFor(ch, 217, states)) != 0 {
		t.Fatal("action grants bypassed connect denial")
	}
}
