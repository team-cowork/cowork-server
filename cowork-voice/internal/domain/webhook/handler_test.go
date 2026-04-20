package webhook

import (
	"testing"

	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

func Test룸_이름에서_채널_ID를_추출한다(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name     string
		roomName string
		wantID   int64
		wantSess string
		wantOK   bool
	}{
		{
			name:     "정상 포맷",
			roomName: "voice-123-session-1",
			wantID:   123,
			wantSess: "session-1",
			wantOK:   true,
		},
		{
			name:     "세션 ID 없음",
			roomName: "voice-123",
			wantOK:   false,
		},
		{
			name:     "접두사가 다름",
			roomName: "room-123",
			wantOK:   false,
		},
		{
			name:     "숫자가 아님",
			roomName: "voice-abc",
			wantOK:   false,
		},
		{
			name:     "빈 값",
			roomName: "",
			wantOK:   false,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			parsed, ok := roomdomain.ParseRoomName(tc.roomName)
			if ok != tc.wantOK {
				t.Fatalf("ParseRoomName(%q) ok = %t, want %t", tc.roomName, ok, tc.wantOK)
			}
			if !tc.wantOK {
				return
			}
			if parsed.ChannelID != tc.wantID {
				t.Fatalf("ParseRoomName(%q) channelID = %d, want %d", tc.roomName, parsed.ChannelID, tc.wantID)
			}
			if parsed.SessionID != tc.wantSess {
				t.Fatalf("ParseRoomName(%q) sessionID = %q, want %q", tc.roomName, parsed.SessionID, tc.wantSess)
			}
		})
	}
}
