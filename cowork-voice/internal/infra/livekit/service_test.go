package livekit

import (
	"errors"
	"slices"
	"testing"

	"github.com/livekit/protocol/auth"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

func parseLiveTokenGrant(t *testing.T, token string) *auth.ClaimGrants {
	t.Helper()

	verifier, err := auth.ParseAPIToken(token)
	if err != nil {
		t.Fatalf("ParseAPIToken() error = %v", err)
	}
	_, grants, err := verifier.Verify("test-secret-must-be-32-chars-long!!")
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	return grants
}

func TestGenerateLiveToken_호스트는_마이크와_화면공유만_publish_가능하다(t *testing.T) {
	t.Parallel()

	token, err := GenerateLiveToken("test-key", "test-secret-must-be-32-chars-long!!", 42, "live-123-session-1", true, 3600)
	if err != nil {
		t.Fatalf("GenerateLiveToken() error = %v", err)
	}

	grants := parseLiveTokenGrant(t, token)
	video := grants.Video
	if video == nil {
		t.Fatal("video grant = nil, want value")
	}
	if !video.GetCanPublish() {
		t.Fatal("CanPublish = false, want true")
	}
	if !video.GetCanSubscribe() {
		t.Fatal("CanSubscribe = false, want true")
	}
	want := []string{"microphone", "screen_share", "screen_share_audio"}
	if !slices.Equal(video.CanPublishSources, want) {
		t.Fatalf("CanPublishSources = %v, want %v", video.CanPublishSources, want)
	}
	if slices.Contains(video.CanPublishSources, "camera") {
		t.Fatal("CanPublishSources contains camera, want excluded")
	}
	if grants.Identity != "42" {
		t.Fatalf("Identity = %q, want 42", grants.Identity)
	}
	if video.Room != "live-123-session-1" {
		t.Fatalf("Room = %q, want live-123-session-1", video.Room)
	}
}

func TestGenerateLiveToken_시청자는_publish가_차단된다(t *testing.T) {
	t.Parallel()

	token, err := GenerateLiveToken("test-key", "test-secret-must-be-32-chars-long!!", 99, "live-123-session-1", false, 3600)
	if err != nil {
		t.Fatalf("GenerateLiveToken() error = %v", err)
	}

	grants := parseLiveTokenGrant(t, token)
	video := grants.Video
	if video == nil {
		t.Fatal("video grant = nil, want value")
	}
	if video.GetCanPublish() {
		t.Fatal("CanPublish = true, want false")
	}
	if !video.GetCanSubscribe() {
		t.Fatal("CanSubscribe = false, want true")
	}
	if len(video.CanPublishSources) != 0 {
		t.Fatalf("CanPublishSources = %v, want empty", video.CanPublishSources)
	}
}

func Test방이_이미_존재하면_중복_생성_에러로_판단한다(t *testing.T) {
	t.Parallel()

	cases := []struct {
		name string
		err  error
		want bool
	}{
		{
			name: "gRPC AlreadyExists 코드",
			err:  status.Error(codes.AlreadyExists, "room exists"),
			want: true,
		},
		{
			name: "already exists 문구",
			err:  errors.New("room already exists"),
			want: true,
		},
		{
			name: "already_exists 문구",
			err:  errors.New("rpc error: code = ALREADY_EXISTS desc = room exists"),
			want: true,
		},
		{
			name: "기타 에러",
			err:  errors.New("permission denied"),
			want: false,
		},
		{
			name: "nil 에러",
			err:  nil,
			want: false,
		},
	}

	for _, tc := range cases {
		tc := tc
		t.Run(tc.name, func(t *testing.T) {
			t.Parallel()

			got := isRoomAlreadyExistsError(tc.err)
			if got != tc.want {
				t.Fatalf("isRoomAlreadyExistsError() = %v, want %v", got, tc.want)
			}
		})
	}
}
