package livekit

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/livekit/protocol/auth"
	livekit "github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/cowork/cowork-voice/internal/apperr"
)

func GenerateToken(apiKey, apiSecret string, userID int64, roomName string, ttlSecs int64) (string, error) {
	identity := strconv.FormatInt(userID, 10)
	at := auth.NewAccessToken(apiKey, apiSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     roomName,
	}
	at.SetVideoGrant(grant).
		SetIdentity(identity).
		SetName(identity).
		SetValidFor(time.Duration(ttlSecs) * time.Second)
	token, err := at.ToJWT()
	if err != nil {
		return "", apperr.Internal(err.Error())
	}
	return token, nil
}

// GenerateLiveToken은 라이브 방송용 토큰을 발급한다.
// 호스트는 마이크·화면공유 publish가 허용되고, 시청자는 subscribe 전용이다(서버 강제).
func GenerateLiveToken(apiKey, apiSecret string, userID int64, roomName string, isHost bool, ttlSecs int64) (string, error) {
	identity := strconv.FormatInt(userID, 10)
	at := auth.NewAccessToken(apiKey, apiSecret)
	grant := &auth.VideoGrant{
		RoomJoin: true,
		Room:     roomName,
	}
	grant.SetCanSubscribe(true)
	if isHost {
		// CanPublishSources는 CanPublish=true일 때만 유효하다. 카메라는 스펙상 제외.
		grant.SetCanPublish(true)
		grant.SetCanPublishSources([]livekit.TrackSource{
			livekit.TrackSource_MICROPHONE,
			livekit.TrackSource_SCREEN_SHARE,
			livekit.TrackSource_SCREEN_SHARE_AUDIO,
		})
	} else {
		grant.SetCanPublish(false)
	}
	at.SetVideoGrant(grant).
		SetIdentity(identity).
		SetName(identity).
		SetValidFor(time.Duration(ttlSecs) * time.Second)
	token, err := at.ToJWT()
	if err != nil {
		return "", apperr.Internal(err.Error())
	}
	return token, nil
}

func CreateRoomIfNotExists(ctx context.Context, client *lksdk.RoomServiceClient, roomName string) error {
	_, err := client.CreateRoom(ctx, &livekit.CreateRoomRequest{Name: roomName})
	if err != nil {
		if isRoomAlreadyExistsError(err) {
			return nil
		}
		return apperr.Internal(err.Error())
	}
	return nil
}

// CreateRoomWithEmptyTimeout은 빈 방 자동 정리 시간을 명시해 방을 생성한다.
// 라이브는 호스트가 start 후 접속하지 않는 유령 세션을 EmptyTimeout으로 정리한다.
func CreateRoomWithEmptyTimeout(ctx context.Context, client *lksdk.RoomServiceClient, roomName string, emptyTimeoutSecs uint32) error {
	_, err := client.CreateRoom(ctx, &livekit.CreateRoomRequest{
		Name:         roomName,
		EmptyTimeout: emptyTimeoutSecs,
	})
	if err != nil {
		if isRoomAlreadyExistsError(err) {
			return nil
		}
		return apperr.Internal(err.Error())
	}
	return nil
}

// DeleteRoom은 방을 삭제해 전원을 퇴장시킨다(호스트 종료). 없는 방이면 무시한다(멱등).
func DeleteRoom(ctx context.Context, client *lksdk.RoomServiceClient, roomName string) error {
	_, err := client.DeleteRoom(ctx, &livekit.DeleteRoomRequest{Room: roomName})
	if err != nil {
		msg := strings.ToLower(err.Error())
		if strings.Contains(msg, "not_found") || strings.Contains(msg, "not found") || strings.Contains(msg, "404") {
			return nil
		}
		return apperr.Internal(err.Error())
	}
	return nil
}

func isRoomAlreadyExistsError(err error) bool {
	if err == nil {
		return false
	}
	if s, ok := status.FromError(err); ok && s.Code() == codes.AlreadyExists {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "already exists") ||
		strings.Contains(msg, "already_exists")
}

func RemoveParticipant(ctx context.Context, client *lksdk.RoomServiceClient, roomName, identity string) error {
	_, err := client.RemoveParticipant(ctx, &livekit.RoomParticipantIdentity{
		Room:     roomName,
		Identity: identity,
	})
	if err != nil {
		msg := strings.ToLower(err.Error())
		if strings.Contains(msg, "not_found") || strings.Contains(msg, "not found") || strings.Contains(msg, "404") {
			return nil
		}
		return apperr.Internal(err.Error())
	}
	return nil
}

func ListParticipants(ctx context.Context, client *lksdk.RoomServiceClient, roomName string) ([]*livekit.ParticipantInfo, error) {
	res, err := client.ListParticipants(ctx, &livekit.ListParticipantsRequest{Room: roomName})
	if err != nil {
		msg := strings.ToLower(err.Error())
		if strings.Contains(msg, "not_found") || strings.Contains(msg, "not found") || strings.Contains(msg, "404") {
			return []*livekit.ParticipantInfo{}, nil
		}
		return nil, apperr.Internal(err.Error())
	}
	return res.Participants, nil
}
