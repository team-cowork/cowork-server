package webhook

import (
	"testing"

	sessiondomain "github.com/cowork/cowork-voice/internal/domain/session"
)

func Test룸_이름에서_채널_ID를_추출한다(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name     string
		roomName string
		wantID   int64
		wantSess string
	}{
		{
			name:     "정상 포맷",
			roomName: "voice-123-session-1",
			wantID:   123,
			wantSess: "session-1",
		},
		{
			name:     "접두사가 다름",
			roomName: "room-123",
			wantID:   0,
		},
		{
			name:     "숫자가 아님",
			roomName: "voice-abc",
			wantID:   0,
		},
		{
			name:     "빈 값",
			roomName: "",
			wantID:   0,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			gotID, gotSess, ok := sessiondomain.ParseRoomName(tc.roomName)
			if gotID != tc.wantID {
				t.Fatalf("ParseRoomName(%q) channelID = %d, want %d", tc.roomName, gotID, tc.wantID)
			}
			if gotSess != tc.wantSess {
				t.Fatalf("ParseRoomName(%q) sessionID = %q, want %q", tc.roomName, gotSess, tc.wantSess)
			}
			if ok != (tc.wantID != 0) {
				t.Fatalf("ParseRoomName(%q) ok = %t, want %t", tc.roomName, ok, tc.wantID != 0)
			}
		})
	}
}
