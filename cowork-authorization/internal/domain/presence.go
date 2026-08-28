package domain

import "time"

const (
	PresenceOnline  = "online"
	PresenceOffline = "offline"
)

type UserPresenceState struct {
	UserID             int64     `gorm:"primaryKey;column:user_id"`
	Status             string    `gorm:"size:30;column:status;not null"`
	ActiveSessionCount int64     `gorm:"column:active_session_count;not null"`
	OccurredAt         time.Time `gorm:"column:occurred_at;not null"`
	CreatedAt          time.Time `gorm:"column:created_at;autoCreateTime:nano"`
	UpdatedAt          time.Time `gorm:"column:updated_at;autoUpdateTime:nano"`
}

func (UserPresenceState) TableName() string {
	return "tb_user_presence_states"
}

type UserPresenceEvent struct {
	EventType  string    `json:"eventType"`
	UserID     int64     `json:"userId"`
	Status     string    `json:"status"`
	OccurredAt time.Time `json:"occurredAt"`
}

type PresenceTransition struct {
	Status  string
	Changed bool
}

// PresenceTransitionOccurredAt makes serialized source transitions strictly
// monotonic at MySQL DATETIME(6) precision. This preserves last-session
// semantics even when two mutations observe the same wall-clock microsecond.
func PresenceTransitionOccurredAt(
	currentStatus string,
	currentOccurredAt time.Time,
	activeSessions int64,
	requestedOccurredAt time.Time,
) time.Time {
	desired := PresenceOffline
	if activeSessions > 0 {
		desired = PresenceOnline
	}
	if desired != currentStatus && !requestedOccurredAt.After(currentOccurredAt) {
		return currentOccurredAt.Add(time.Microsecond)
	}
	return requestedOccurredAt
}

// DecidePresenceTransition derives presence exclusively from unexpired sessions.
// A durable offline state is retained when the final session disappears. The
// stored timestamp is authoritative: stale transitions are ignored and offline
// wins a same-microsecond conflict so delivery order cannot change the result.
func DecidePresenceTransition(
	currentStatus string,
	currentOccurredAt time.Time,
	activeSessions int64,
	incomingOccurredAt time.Time,
) PresenceTransition {
	desired := PresenceOffline
	if activeSessions > 0 {
		desired = PresenceOnline
	}
	if desired == currentStatus {
		return PresenceTransition{Status: currentStatus, Changed: false}
	}
	if incomingOccurredAt.Before(currentOccurredAt) {
		return PresenceTransition{Status: currentStatus, Changed: false}
	}
	if incomingOccurredAt.Equal(currentOccurredAt) &&
		currentStatus == PresenceOffline && desired == PresenceOnline {
		return PresenceTransition{Status: currentStatus, Changed: false}
	}
	return PresenceTransition{Status: desired, Changed: true}
}
