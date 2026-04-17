package session

import (
	"context"

	livekitproto "github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"

	"github.com/cowork/cowork-voice/internal/config"
	lkdomain "github.com/cowork/cowork-voice/internal/infra/livekit"
)

type liveKitRoomService struct {
	client *lksdk.RoomServiceClient
	cfg    *config.AppConfig
}

func NewLiveKitRoom(client *lksdk.RoomServiceClient, cfg *config.AppConfig) LiveKitRoom {
	return &liveKitRoomService{
		client: client,
		cfg:    cfg,
	}
}

func (l *liveKitRoomService) CreateRoomIfNotExists(ctx context.Context, roomName string) error {
	return lkdomain.CreateRoomIfNotExists(ctx, l.client, roomName)
}

func (l *liveKitRoomService) GenerateToken(userID int64, roomName string) (string, error) {
	return lkdomain.GenerateToken(l.cfg.LiveKitAPIKey, l.cfg.LiveKitAPISecret, userID, roomName, l.cfg.LiveKitTokenTTLSecs)
}

func (l *liveKitRoomService) RemoveParticipant(ctx context.Context, roomName, identity string) error {
	return lkdomain.RemoveParticipant(ctx, l.client, roomName, identity)
}

func (l *liveKitRoomService) ListParticipants(ctx context.Context, roomName string) ([]LiveKitParticipant, error) {
	participants, err := lkdomain.ListParticipants(ctx, l.client, roomName)
	if err != nil {
		return nil, err
	}

	result := make([]LiveKitParticipant, 0, len(participants))
	for _, p := range participants {
		if p == nil {
			continue
		}
		result = append(result, fromLiveKitParticipant(p))
	}
	return result, nil
}

func (l *liveKitRoomService) DeleteRoom(ctx context.Context, roomName string) error {
	return lkdomain.DeleteRoom(ctx, l.client, roomName)
}

func fromLiveKitParticipant(p *livekitproto.ParticipantInfo) LiveKitParticipant {
	return LiveKitParticipant{
		Identity: p.Identity,
		JoinedAt: p.JoinedAt,
	}
}
