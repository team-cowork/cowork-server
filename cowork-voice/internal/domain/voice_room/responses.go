package room

type JoinResponse struct {
	Token      string `json:"token"       example:"eyJhbGciOiJIUzI1NiJ9..."`
	LiveKitURL string `json:"livekit_url" example:"wss://livekit.example.com"`
	SessionID  string `json:"session_id"  example:"550e8400-e29b-41d4-a716-446655440000"`
	RoomName   string `json:"room_name"   example:"channel-42"`
}

type ParticipantResponse struct {
	UserID   int64  `json:"user_id"   example:"1"`
	JoinedAt string `json:"joined_at" example:"2024-01-01T00:00:00Z"`
}

type ParticipantsResponse struct {
	ChannelID    int64                 `json:"channel_id"   example:"42"`
	RoomName     string                `json:"room_name"    example:"channel-42"`
	Participants []ParticipantResponse `json:"participants"`
}

type SessionResponse struct {
	SessionID string  `json:"session_id" example:"550e8400-e29b-41d4-a716-446655440000"`
	ChannelID int64   `json:"channel_id" example:"42"`
	TeamID    int64   `json:"team_id"    example:"1"`
	Status    string  `json:"status"     example:"active"`
	StartedAt string  `json:"started_at" example:"2024-01-01T00:00:00Z"`
	EndedAt   *string `json:"ended_at"   example:"2024-01-01T01:00:00Z"`
}
