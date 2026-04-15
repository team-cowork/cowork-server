package session

import (
	"fmt"
	"strconv"
	"strings"
)

const roomNamePrefix = "voice-"

func RoomName(channelID int64) string {
	return fmt.Sprintf("%s%d", roomNamePrefix, channelID)
}

func ParseRoomName(roomName string) (int64, bool) {
	s, ok := strings.CutPrefix(roomName, roomNamePrefix)
	if !ok {
		return 0, false
	}
	id, err := strconv.ParseInt(s, 10, 64)
	if err != nil {
		return 0, false
	}
	return id, true
}
