package webhook

import (
	"fmt"

	livekit "github.com/livekit/protocol/livekit"
)

// participantOccurrenceID identifies one physical LiveKit connection. The SID
// is stable across webhook redelivery and changes on reconnect, so an old
// participant_left delivery cannot close a newer connection by the same user.
func participantOccurrenceID(event *livekit.WebhookEvent) string {
	participant := event.GetParticipant()
	if sid := participant.GetSid(); sid != "" {
		return sid
	}
	// Webhook event IDs differ between joined and left deliveries. Use only
	// participant fields that remain stable for the physical connection.
	return fmt.Sprintf(
		"%s:%s:%d:%d",
		event.GetRoom().GetName(),
		participant.GetIdentity(),
		participant.GetJoinedAtMs(),
		participant.GetJoinedAt(),
	)
}
