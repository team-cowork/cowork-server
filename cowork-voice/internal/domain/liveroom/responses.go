package liveroom

type StartResponse struct {
	Token      string `json:"token"`
	LiveKitURL string `json:"livekit_url"`
	SessionID  string `json:"session_id"`
	RoomName   string `json:"room_name"`
}

type JoinResponse struct {
	Token      string `json:"token"`
	LiveKitURL string `json:"livekit_url"`
	SessionID  string `json:"session_id"`
	RoomName   string `json:"room_name"`
	HostUserID int64  `json:"host_user_id"`
}

type StatusResponse struct {
	Live        bool   `json:"live"`
	SessionID   string `json:"session_id,omitempty"`
	HostUserID  int64  `json:"host_user_id,omitempty"`
	StartedAt   string `json:"started_at,omitempty"`
	ViewerCount int    `json:"viewer_count"`
}
