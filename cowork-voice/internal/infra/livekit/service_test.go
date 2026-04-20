package livekit

import (
	"errors"
	"testing"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

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
