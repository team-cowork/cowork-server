package webhook

import (
	"fmt"
	"testing"

	livekit "github.com/livekit/protocol/livekit"
)

func Test참가자_점유_ID를_생성한다(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name  string
		event *livekit.WebhookEvent
		want  string
	}{
		{
			name: "SID가_있으면_SID를_그대로_반환한다",
			event: &livekit.WebhookEvent{
				Room: &livekit.Room{Name: "voice-123-session-1"},
				Participant: &livekit.ParticipantInfo{
					Sid:        "PA_abc123",
					Identity:   "42",
					JoinedAtMs: 1700000000000,
					JoinedAt:   1700000000,
				},
			},
			want: "PA_abc123",
		},
		{
			name: "SID가_없으면_room_identity_joinedAtMs_joinedAt으로_합성키를_만든다",
			event: &livekit.WebhookEvent{
				Room: &livekit.Room{Name: "voice-123-session-1"},
				Participant: &livekit.ParticipantInfo{
					Identity:   "42",
					JoinedAtMs: 1700000000000,
					JoinedAt:   1700000000,
				},
			},
			want: fmt.Sprintf("%s:%s:%d:%d", "voice-123-session-1", "42", int64(1700000000000), int64(1700000000)),
		},
		{
			name: "Participant가_nil이면_빈_identity로_합성키를_만든다",
			event: &livekit.WebhookEvent{
				Room: &livekit.Room{Name: "voice-123-session-1"},
			},
			want: fmt.Sprintf("%s:%s:%d:%d", "voice-123-session-1", "", int64(0), int64(0)),
		},
		{
			name: "Room이_nil이면_빈_room_이름으로_합성키를_만든다",
			event: &livekit.WebhookEvent{
				Participant: &livekit.ParticipantInfo{
					Identity:   "42",
					JoinedAtMs: 1700000000000,
					JoinedAt:   1700000000,
				},
			},
			want: fmt.Sprintf("%s:%s:%d:%d", "", "42", int64(1700000000000), int64(1700000000)),
		},
		{
			name:  "이벤트_전체가_nil이어도_패닉없이_빈_합성키를_반환한다",
			event: &livekit.WebhookEvent{},
			want:  fmt.Sprintf("%s:%s:%d:%d", "", "", int64(0), int64(0)),
		},
		{
			name: "SID가_빈_문자열이면_합성키로_폴백한다",
			event: &livekit.WebhookEvent{
				Room: &livekit.Room{Name: "voice-999-session-9"},
				Participant: &livekit.ParticipantInfo{
					Sid:        "",
					Identity:   "7",
					JoinedAtMs: 1,
					JoinedAt:   2,
				},
			},
			want: fmt.Sprintf("%s:%s:%d:%d", "voice-999-session-9", "7", int64(1), int64(2)),
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			got := participantOccurrenceID(tc.event)
			if got != tc.want {
				t.Fatalf("participantOccurrenceID() = %q, want %q", got, tc.want)
			}
		})
	}
}

func Test재연결시_SID가_다르면_점유_ID도_달라진다(t *testing.T) {
	t.Parallel()

	base := &livekit.WebhookEvent{
		Room: &livekit.Room{Name: "voice-123-session-1"},
		Participant: &livekit.ParticipantInfo{
			Sid:      "PA_old",
			Identity: "42",
		},
	}
	reconnected := &livekit.WebhookEvent{
		Room: &livekit.Room{Name: "voice-123-session-1"},
		Participant: &livekit.ParticipantInfo{
			Sid:      "PA_new",
			Identity: "42",
		},
	}

	oldID := participantOccurrenceID(base)
	newID := participantOccurrenceID(reconnected)

	if oldID == newID {
		t.Fatalf("participantOccurrenceID() old = %q, new = %q, want different occurrence IDs across reconnects", oldID, newID)
	}
}
