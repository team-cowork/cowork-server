package room

type JoinResponse struct {
	Token      string `json:"token"`
	LiveKitURL string `json:"livekit_url"`
	SessionID  string `json:"session_id"`
	RoomName   string `json:"room_name"`
}

type ParticipantResponse struct {
	UserID   int64  `json:"user_id"`
	JoinedAt string `json:"joined_at"`
}

type ParticipantsResponse struct {
	ChannelID    int64                 `json:"channel_id"`
	RoomName     string                `json:"room_name"`
	Participants []ParticipantResponse `json:"participants"`
}

type SessionResponse struct {
	SessionID string  `json:"session_id"`
	ChannelID int64   `json:"channel_id"`
	TeamID    int64   `json:"team_id"`
	Status    string  `json:"status"`
	StartedAt string  `json:"started_at"`
	EndedAt   *string `json:"ended_at"`
}
