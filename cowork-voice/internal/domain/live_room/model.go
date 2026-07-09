package live

import (
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
)

const (
	CollectionSessions = "live_sessions"
	CollectionViewers  = "live_participants"
	StatusActive       = "active"
	StatusEnded        = "ended"
)

type LiveSession struct {
	ID                 bson.ObjectID `bson:"_id,omitempty"          json:"-"`
	SessionID          string        `bson:"session_id"             json:"session_id"`
	ChannelID          int64         `bson:"channel_id"             json:"channel_id"`
	TeamID             int64         `bson:"team_id"                json:"team_id"`
	HostUserID         int64         `bson:"host_user_id"           json:"host_user_id"`
	RoomName           string        `bson:"room_name"              json:"room_name"`
	Status             string        `bson:"status"                 json:"status"`
	StartedAt          time.Time     `bson:"started_at"             json:"started_at"`
	StartedEventSentAt *time.Time    `bson:"started_event_sent_at,omitempty" json:"started_event_sent_at,omitempty"`
	EndedAt            *time.Time    `bson:"ended_at,omitempty"     json:"ended_at,omitempty"`
}

type LiveViewer struct {
	ID        bson.ObjectID `bson:"_id,omitempty"`
	SessionID string        `bson:"session_id"`
	UserID    int64         `bson:"user_id"`
	ChannelID int64         `bson:"channel_id"`
	JoinedAt  time.Time     `bson:"joined_at"`
	LeftAt    *time.Time    `bson:"left_at"`
}
