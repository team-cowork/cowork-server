package session

import (
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
)

const (
	CollectionSessions     = "voice_sessions"
	CollectionParticipants = "voice_participants"
	StatusActive           = "active"
	StatusEnded            = "ended"
)

type VoiceSession struct {
	ID                   bson.ObjectID `bson:"_id,omitempty"`
	SessionID            string        `bson:"session_id"`
	ChannelID            int64         `bson:"channel_id"`
	TeamID               int64         `bson:"team_id"`
	RoomName             string        `bson:"room_name"`
	Status               string        `bson:"status"`
	StartedAt            time.Time     `bson:"started_at"`
	StartedEventSentAt   *time.Time    `bson:"started_event_sent_at,omitempty"`
	EndedAt              *time.Time    `bson:"ended_at,omitempty"`
}

type VoiceParticipant struct {
	ID        bson.ObjectID `bson:"_id,omitempty"`
	SessionID string        `bson:"session_id"`
	UserID    int64         `bson:"user_id"`
	ChannelID int64         `bson:"channel_id"`
	JoinedAt  time.Time     `bson:"joined_at"`
	LeftAt    *time.Time    `bson:"left_at,omitempty"`
}
