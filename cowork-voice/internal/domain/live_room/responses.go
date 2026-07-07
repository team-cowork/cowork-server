package live

type StartResponse struct {
	Token      string `json:"token"       example:"eyJhbGciOiJIUzI1NiJ9..."`
	LiveKitURL string `json:"livekit_url" example:"wss://livekit.example.com"`
	SessionID  string `json:"session_id"  example:"550e8400-e29b-41d4-a716-446655440000"`
	RoomName   string `json:"room_name"   example:"live-42-550e8400"`
}

type JoinResponse struct {
	Token      string `json:"token"        example:"eyJhbGciOiJIUzI1NiJ9..."`
	LiveKitURL string `json:"livekit_url"  example:"wss://livekit.example.com"`
	SessionID  string `json:"session_id"   example:"550e8400-e29b-41d4-a716-446655440000"`
	RoomName   string `json:"room_name"    example:"live-42-550e8400"`
	HostUserID int64  `json:"host_user_id" example:"1"`
}

type StatusResponse struct {
	Live        bool   `json:"live"          example:"true"`
	SessionID   string `json:"session_id,omitempty"   example:"550e8400-e29b-41d4-a716-446655440000"`
	HostUserID  int64  `json:"host_user_id,omitempty" example:"1"`
	StartedAt   string `json:"started_at,omitempty"   example:"2024-01-01T00:00:00Z"`
	ViewerCount int    `json:"viewer_count"  example:"3"`
}
