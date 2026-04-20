package kafka

const (
	EventSessionStarted = "SESSION_STARTED"
	EventSessionEnded   = "SESSION_ENDED"
	EventUserJoined     = "USER_JOINED"
	EventUserLeft       = "USER_LEFT"
)

type SessionStartedEvent struct {
	EventType string `json:"event_type"`
	SessionID string `json:"session_id"`
	ChannelID int64  `json:"channel_id"`
	TeamID    int64  `json:"team_id"`
	UserID    int64  `json:"user_id"`
	Timestamp string `json:"timestamp"`
}

type SessionEndedEvent struct {
	EventType       string `json:"event_type"`
	SessionID       string `json:"session_id"`
	ChannelID       int64  `json:"channel_id"`
	TeamID          int64  `json:"team_id"`
	DurationSeconds int64  `json:"duration_seconds"`
	Timestamp       string `json:"timestamp"`
}

type UserJoinedEvent struct {
	EventType string `json:"event_type"`
	SessionID string `json:"session_id"`
	ChannelID int64  `json:"channel_id"`
	TeamID    int64  `json:"team_id"`
	UserID    int64  `json:"user_id"`
	Timestamp string `json:"timestamp"`
}

type UserLeftEvent struct {
	EventType       string `json:"event_type"`
	SessionID       string `json:"session_id"`
	ChannelID       int64  `json:"channel_id"`
	TeamID          int64  `json:"team_id"`
	UserID          int64  `json:"user_id"`
	DurationSeconds int64  `json:"duration_seconds"`
	Timestamp       string `json:"timestamp"`
}
