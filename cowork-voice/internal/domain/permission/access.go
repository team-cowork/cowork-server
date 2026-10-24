package permission

// Access is evaluated from current membership, team roles and channel overwrites.
type Access struct {
	TeamID      int64
	Speak       bool
	ScreenShare bool
	Broadcast   bool
}
