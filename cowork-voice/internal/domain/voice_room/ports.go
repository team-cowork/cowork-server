package room

import "context"

type MembershipChecker interface {
	VerifyMembership(ctx context.Context, channelID, userID int64) (int64, error)
}

type LiveKitParticipant struct {
	Identity string
	JoinedAt int64
}

type LiveKitRoom interface {
	CreateRoomIfNotExists(ctx context.Context, roomName string) error
	GenerateToken(userID int64, roomName string) (string, error)
	RemoveParticipant(ctx context.Context, roomName, identity string) error
	ListParticipants(ctx context.Context, roomName string) ([]LiveKitParticipant, error)
	DeleteRoom(ctx context.Context, roomName string) error
}
