package channel

import "testing"

func TestVoiceConnectPermission(t *testing.T) {
	ch := State{Kind: "channel", ChannelID: 11, TeamID: 3, ChannelType: "VOICE"}
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
	t.Run("higher priority wins and equal priority denies", func(t *testing.T) {
		for _, priority := range []int{9, 10, 11} {
			states := []State{member, assignment, allow,
				{Kind: "assignment", TeamID: 3, UserID: 217, RoleID: 20, Version: 2},
				{Kind: "role", TeamID: 3, RoleID: 20, Priority: priority, Permissions: map[string]bool{"voice_connect": false}},
			}
			if got := canConnect(ch, 217, states); got != (priority < 10) {
				t.Errorf("deny priority %d: canConnect = %t", priority, got)
			}
		}
	})
}
