package permission

import "context"

// ConnectCheck evaluates a participant against an established current projection.
type ConnectCheck func(context.Context, int64, int64) (Access, error)

// Access is evaluated from current membership, team roles and channel overwrites.
type Access struct {
	TeamID int64
	Speak  bool
	// ScreenShare represents video permission: camera and screen sharing in ordinary voice rooms,
	// camera for every live participant and screen sharing for the live host.
	ScreenShare bool
	Broadcast   bool
	// MaxParticipants와 Bitrate는 같은 projection에서 읽은 음성 채널 설정이다. 0과 nil은 미설정이다.
	MaxParticipants int
	Bitrate         *int
}

type MediaAccess struct {
	Subscribe   bool
	Microphone  bool
	ScreenShare bool
	Camera      bool
	PublishData bool
}

// MediaAccessFor keeps live microphone/screen publishing to the host, while camera follows
// the video grant for every live participant.
// Ordinary voice data is a connection capability, independent of speaking/video grants.
func MediaAccessFor(known, speak, video, liveRoom, liveHost bool) MediaAccess {
	if !known {
		return MediaAccess{}
	}
	broadcaster := !liveRoom || liveHost
	return MediaAccess{
		Subscribe: true, Microphone: speak && broadcaster, ScreenShare: video && broadcaster,
		Camera: video, PublishData: !liveRoom,
	}
}
