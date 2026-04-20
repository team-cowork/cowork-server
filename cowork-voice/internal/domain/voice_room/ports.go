package room

import (
	"context"
	"time"
)

type Service interface {
	Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error)
	Leave(ctx context.Context, channelID, userID int64) error
	GetParticipants(ctx context.Context, channelID, userID int64) (*ParticipantsResponse, error)
	GetSession(ctx context.Context, sessionID string, userID int64) (*SessionResponse, error)
}

type Repository interface {
	FindActiveSession(ctx context.Context, channelID int64) (*VoiceSession, error)
	FindSessionByRoomName(ctx context.Context, roomName string) (*VoiceSession, error)
	CreateSession(ctx context.Context, channelID, teamID int64) (*VoiceSession, error)
	GetSession(ctx context.Context, sessionID string) (*VoiceSession, error)
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) error
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	InsertParticipant(ctx context.Context, p *VoiceParticipant) error
	MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
}

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
