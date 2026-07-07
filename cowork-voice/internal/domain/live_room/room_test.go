package live

import "testing"

func TestRoomName_라이브_접두어로_생성한다(t *testing.T) {
	t.Parallel()

	if got := RoomName(123, "session-1"); got != "live-123-session-1" {
		t.Fatalf("RoomName() = %q, want live-123-session-1", got)
	}
}

func TestParseRoomName_라이브_포맷을_파싱한다(t *testing.T) {
	t.Parallel()

	parsed, ok := ParseRoomName("live-123-session-1")
	if !ok {
		t.Fatal("ParseRoomName() ok = false, want true")
	}
	if parsed.ChannelID != 123 {
		t.Fatalf("ChannelID = %d, want 123", parsed.ChannelID)
	}
	if parsed.SessionID != "session-1" {
		t.Fatalf("SessionID = %q, want session-1", parsed.SessionID)
	}
}

func TestParseRoomName_음성_접두어는_실패한다(t *testing.T) {
	t.Parallel()

	if _, ok := ParseRoomName("voice-123-session-1"); ok {
		t.Fatal("ParseRoomName() ok = true, want false")
	}
}

func TestParseRoomName_세션이_없으면_실패한다(t *testing.T) {
	t.Parallel()

	if _, ok := ParseRoomName("live-123"); ok {
		t.Fatal("ParseRoomName() ok = true, want false")
	}
}

func TestParseRoomName_잘못된_포맷은_실패한다(t *testing.T) {
	t.Parallel()

	if _, ok := ParseRoomName("invalid-room"); ok {
		t.Fatal("ParseRoomName() ok = true, want false")
	}
}

func TestIsLiveRoomName_접두어로_구분한다(t *testing.T) {
	t.Parallel()

	if !IsLiveRoomName("live-123-session-1") {
		t.Fatal("IsLiveRoomName(live-...) = false, want true")
	}
	if IsLiveRoomName("voice-123-session-1") {
		t.Fatal("IsLiveRoomName(voice-...) = true, want false")
	}
}
