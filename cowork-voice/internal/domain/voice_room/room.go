package room

import (
	"strconv"
	"strings"
)

const roomNamePrefix = "voice-"

type RoomNameFormat string

const (
	RoomNameFormatLegacy RoomNameFormat = "legacy"
	RoomNameFormatScoped RoomNameFormat = "scoped"
)

type ParsedRoomName struct {
	ChannelID int64
	SessionID string
	Format    RoomNameFormat
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
	if !hasSession {
		id, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			return nil, false
		}
		return &ParsedRoomName{
			ChannelID: id,
			Format:    RoomNameFormatLegacy,
		}, true
	}
	if sessionPart == "" {
		return nil, false
	}

	id, err := strconv.ParseInt(channelPart, 10, 64)
	if err != nil {
		return nil, false
	}
	return &ParsedRoomName{
		ChannelID: id,
		SessionID: sessionPart,
		Format:    RoomNameFormatScoped,
	}, true
}
