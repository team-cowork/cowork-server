package permission

import "context"

// ConnectCheck evaluates a participant against an established current projection.
type ConnectCheck func(context.Context, int64, int64) (Access, error)

// Access is evaluated from current membership, team roles and channel overwrites.
type Access struct {
	TeamID int64
	Speak  bool
	// ScreenShare represents video permission: camera and screen sharing in ordinary voice rooms.
	ScreenShare bool
	Broadcast   bool
}

type MediaAccess struct {
	Subscribe   bool
	Microphone  bool
	ScreenShare bool
	Camera      bool
	PublishData bool
}

// MediaAccessFor keeps live broadcasts restricted to microphone/screen publishing.
// Ordinary voice data is a connection capability, independent of speaking/video grants.
func MediaAccessFor(known, speak, screenShare, liveRoom bool) MediaAccess {
	if !known {
		return MediaAccess{}
	}
	return MediaAccess{
		Subscribe: true, Microphone: speak, ScreenShare: screenShare,
		Camera: screenShare && !liveRoom, PublishData: !liveRoom,
	}
}
