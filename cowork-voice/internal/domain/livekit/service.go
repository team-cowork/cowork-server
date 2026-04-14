package livekit

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/livekit/protocol/auth"
	livekit "github.com/livekit/protocol/livekit"
	lksdk "github.com/livekit/server-sdk-go/v2"

	"github.com/cowork/cowork-voice/internal/apperror"
)

func NewRoomServiceClient(hostURL, apiKey, apiSecret string) *lksdk.RoomServiceClient {
	return lksdk.NewRoomServiceClient(hostURL, apiKey, apiSecret)
}

func GenerateToken(apiKey, apiSecret string, userID int64, roomName string, ttlSecs int64) (string, error) {
	identity := fmt.Sprintf("%d", userID)
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
		return "", apperror.Internal(err.Error())
	}
	return token, nil
}

func CreateRoomIfNotExists(ctx context.Context, client *lksdk.RoomServiceClient, roomName string) error {
	_, err := client.CreateRoom(ctx, &livekit.CreateRoomRequest{Name: roomName})
	if err != nil {
		return apperror.Internal(err.Error())
	}
	return nil
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
		return apperror.Internal(err.Error())
	}
	return nil
}

func ListParticipants(ctx context.Context, client *lksdk.RoomServiceClient, roomName string) ([]*livekit.ParticipantInfo, error) {
	res, err := client.ListParticipants(ctx, &livekit.ListParticipantsRequest{Room: roomName})
	if err != nil {
		return nil, apperror.Internal(err.Error())
	}
	return res.Participants, nil
}

func DeleteRoom(ctx context.Context, client *lksdk.RoomServiceClient, roomName string) error {
	_, err := client.DeleteRoom(ctx, &livekit.DeleteRoomRequest{Room: roomName})
	if err != nil {
		return apperror.Internal(err.Error())
	}
	return nil
}
