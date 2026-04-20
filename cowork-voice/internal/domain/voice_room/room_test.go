package room

import "testing"

func TestParseRoomName_레거시_포맷은_실패한다(t *testing.T) {
	t.Parallel()

	if _, ok := ParseRoomName("voice-123"); ok {
		t.Fatal("ParseRoomName() ok = true, want false")
	}
}

func TestParseRoomName_세션_스코프_포맷을_파싱한다(t *testing.T) {
	t.Parallel()

	parsed, ok := ParseRoomName("voice-123-session-1")
	if !ok {
		t.Fatal("ParseRoomName() ok = false, want true")
	}
	if parsed.ChannelID != 123 {
		t.Fatalf("ChannelID = %d, want 123", parsed.ChannelID)
	}
	if parsed.SessionID != "session-1" {
		t.Fatalf("SessionID = %q, want session-1", parsed.SessionID)
	}
	if parsed.Format != RoomNameFormatScoped {
		t.Fatalf("Format = %q, want %q", parsed.Format, RoomNameFormatScoped)
	}
}

func TestParseRoomName_잘못된_포맷은_실패한다(t *testing.T) {
	t.Parallel()

	if _, ok := ParseRoomName("invalid-room"); ok {
		t.Fatal("ParseRoomName() ok = true, want false")
	}
}
