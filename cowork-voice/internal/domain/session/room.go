package session

import (
	"strconv"
	"strings"
)

const roomNamePrefix = "voice-"

func RoomName(channelID int64, sessionID string) string {
	return roomNamePrefix + strconv.FormatInt(channelID, 10) + "-" + sessionID
}

func ParseRoomName(roomName string) (channelID int64, sessionID string, ok bool) {
	s, ok := strings.CutPrefix(roomName, roomNamePrefix)
	if !ok {
		return 0, "", false
	}

	channelPart, sessionPart, ok := strings.Cut(s, "-")
	if !ok || sessionPart == "" {
		return 0, "", false
	}

	id, err := strconv.ParseInt(channelPart, 10, 64)
	if err != nil {
		return 0, "", false
	}
	return id, sessionPart, true
}
