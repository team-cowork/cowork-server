package permission

import "context"

// ConnectCheck evaluates a participant against an established current projection.
type ConnectCheck func(context.Context, int64, int64) (Access, error)

// Access is evaluated from current membership, team roles and channel overwrites.
type Access struct {
	TeamID      int64
	Speak       bool
	ScreenShare bool
	Broadcast   bool
}
