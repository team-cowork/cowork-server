package livekit

import (
	"testing"

	"github.com/livekit/protocol/auth"
	"github.com/livekit/protocol/livekit"
)

func TestTokenRestrictsMediaSources(t *testing.T) {
	for _, speak := range []bool{false, true} {
		for _, screenShare := range []bool{false, true} {
			token, err := GenerateToken("test-key", "unit-test-signing-secret", 42, "voice-room", speak, screenShare, 60)
			if err != nil {
				t.Fatal(err)
			}
			verifier, err := auth.ParseAPIToken(token)
			if err != nil {
				t.Fatal(err)
			}
			_, claims, err := verifier.Verify("unit-test-signing-secret")
			if err != nil {
				t.Fatal(err)
			}
			grant := claims.Video
			if grant == nil || !grant.RoomJoin || grant.Room != "voice-room" || !grant.GetCanSubscribe() {
				t.Fatalf("missing room access: %+v", grant)
			}
			for source, want := range map[livekit.TrackSource]bool{
				livekit.TrackSource_MICROPHONE:         speak,
				livekit.TrackSource_SCREEN_SHARE:       screenShare,
				livekit.TrackSource_SCREEN_SHARE_AUDIO: screenShare,
				livekit.TrackSource_CAMERA:             screenShare,
			} {
				if got := grant.GetCanPublishSource(source); got != want {
					t.Errorf("speak=%t screenShare=%t source=%s: allowed=%t, want %t", speak, screenShare, source, got, want)
				}
			}
			if !grant.GetCanPublishData() {
				t.Fatal("connected voice participants must retain data publishing")
			}
		}
	}
}
