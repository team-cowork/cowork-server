package live

import (
	"strconv"
	"strings"
)

const roomNamePrefix = "live-"

type ParsedRoomName struct {
	ChannelID int64
	SessionID string
}

func RoomName(channelID int64, sessionID string) string {
	return roomNamePrefix + strconv.FormatInt(channelID, 10) + "-" + sessionID
}

func ParseRoomName(roomName string) (*ParsedRoomName, bool) {
	s, ok := strings.CutPrefix(roomName, roomNamePrefix)
	if !ok {
		return nil, false
	}

	channelPart, sessionPart, hasSession := strings.Cut(s, "-")
	if !hasSession || sessionPart == "" {
		return nil, false
	}

	id, err := strconv.ParseInt(channelPart, 10, 64)
	if err != nil {
		return nil, false
	}
	return &ParsedRoomName{
		ChannelID: id,
		SessionID: sessionPart,
	}, true
}

// IsLiveRoomName은 웹훅 디스패치용으로 룸 이름이 라이브 룸인지 확인한다.
func IsLiveRoomName(roomName string) bool {
	return strings.HasPrefix(roomName, roomNamePrefix)
}
