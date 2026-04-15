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
		want     int64
	}{
		{
			name:     "정상 포맷",
			roomName: "voice-123",
			want:     123,
		},
		{
			name:     "접두사가 다름",
			roomName: "room-123",
			want:     0,
		},
		{
			name:     "숫자가 아님",
			roomName: "voice-abc",
			want:     0,
		},
		{
			name:     "빈 값",
			roomName: "",
			want:     0,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			got, ok := sessiondomain.ParseRoomName(tc.roomName)
			if got != tc.want {
				t.Fatalf("ParseRoomName(%q) = %d, want %d", tc.roomName, got, tc.want)
			}
			if ok != (tc.want != 0) {
				t.Fatalf("ParseRoomName(%q) ok = %t, want %t", tc.roomName, ok, tc.want != 0)
			}
		})
	}
}
