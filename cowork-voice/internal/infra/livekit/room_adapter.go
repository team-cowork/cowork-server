package livekit

import (
	"context"

	livekitproto "github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"

	room "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

type liveKitRoomService struct {
	client    *lksdk.RoomServiceClient
	apiKey    string
	apiSecret string
	ttlSecs   int64
}

func NewLiveKitRoom(client *lksdk.RoomServiceClient, apiKey, apiSecret string, ttlSecs int64) room.LiveKitRoom {
	return &liveKitRoomService{
		client:    client,
		apiKey:    apiKey,
		apiSecret: apiSecret,
		ttlSecs:   ttlSecs,
	}
}

func (l *liveKitRoomService) CreateRoomIfNotExists(ctx context.Context, roomName string) error {
	return CreateRoomIfNotExists(ctx, l.client, roomName)
}

func (l *liveKitRoomService) GenerateToken(userID int64, roomName string) (string, error) {
	return GenerateToken(l.apiKey, l.apiSecret, userID, roomName, l.ttlSecs)
}

func (l *liveKitRoomService) RemoveParticipant(ctx context.Context, roomName, identity string) error {
	return RemoveParticipant(ctx, l.client, roomName, identity)
}

func (l *liveKitRoomService) ListParticipants(ctx context.Context, roomName string) ([]room.LiveKitParticipant, error) {
	participants, err := ListParticipants(ctx, l.client, roomName)
	if err != nil {
		return nil, err
	}

	result := make([]room.LiveKitParticipant, 0, len(participants))
	for _, p := range participants {
		if p == nil {
			continue
		}
		result = append(result, fromProtoParticipant(p))
	}
	return result, nil
}

func (l *liveKitRoomService) DeleteRoom(ctx context.Context, roomName string) error {
	return DeleteRoom(ctx, l.client, roomName)
}

func fromProtoParticipant(p *livekitproto.ParticipantInfo) room.LiveKitParticipant {
	return room.LiveKitParticipant{
		Identity: p.Identity,
		JoinedAt: p.JoinedAt,
	}
}
