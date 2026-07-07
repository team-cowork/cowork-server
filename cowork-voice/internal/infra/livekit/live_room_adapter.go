package livekit

import (
	"context"

	livekitproto "github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"

	live "github.com/cowork/cowork-voice/internal/domain/live_room"
)

// liveEmptyTimeoutSecs는 라이브 방의 빈 방 자동 정리 시간이다.
// 호스트가 start 후 접속하지 않는 유령 세션이 이 시간 뒤 room_finished로 정리된다.
const liveEmptyTimeoutSecs = 300

type liveKitLiveRoomService struct {
	client    *lksdk.RoomServiceClient
	apiKey    string
	apiSecret string
	ttlSecs   int64
}

func NewLiveKitLiveRoom(client *lksdk.RoomServiceClient, apiKey, apiSecret string, ttlSecs int64) live.LiveKitRoom {
	return &liveKitLiveRoomService{
		client:    client,
		apiKey:    apiKey,
		apiSecret: apiSecret,
		ttlSecs:   ttlSecs,
	}
}

func (l *liveKitLiveRoomService) CreateRoomIfNotExists(ctx context.Context, roomName string) error {
	return CreateRoomWithEmptyTimeout(ctx, l.client, roomName, liveEmptyTimeoutSecs)
}

func (l *liveKitLiveRoomService) GenerateToken(userID int64, roomName string, isHost bool) (string, error) {
	return GenerateLiveToken(l.apiKey, l.apiSecret, userID, roomName, isHost, l.ttlSecs)
}

func (l *liveKitLiveRoomService) RemoveParticipant(ctx context.Context, roomName, identity string) error {
	return RemoveParticipant(ctx, l.client, roomName, identity)
}

func (l *liveKitLiveRoomService) ListParticipants(ctx context.Context, roomName string) ([]live.LiveKitParticipant, error) {
	participants, err := ListParticipants(ctx, l.client, roomName)
	if err != nil {
		return nil, err
	}

	result := make([]live.LiveKitParticipant, 0, len(participants))
	for _, p := range participants {
		if p == nil {
			continue
		}
		result = append(result, fromProtoLiveParticipant(p))
	}
	return result, nil
}

func (l *liveKitLiveRoomService) DeleteRoom(ctx context.Context, roomName string) error {
	return DeleteRoom(ctx, l.client, roomName)
}

func fromProtoLiveParticipant(p *livekitproto.ParticipantInfo) live.LiveKitParticipant {
	return live.LiveKitParticipant{
		Identity: p.Identity,
		JoinedAt: p.JoinedAt,
	}
}
