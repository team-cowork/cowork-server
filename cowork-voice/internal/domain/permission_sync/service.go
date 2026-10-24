package permissionsync

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"time"

	"github.com/livekit/protocol/livekit"

	"github.com/cowork/cowork-voice/internal/apperr"
	live "github.com/cowork/cowork-voice/internal/domain/live_room"
	"github.com/cowork/cowork-voice/internal/domain/permission"
	voice "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

type PermissionChecker interface {
	CurrentConnectCheck(context.Context) (permission.ConnectCheck, error)
}

type LiveSessions interface {
	FindActiveSession(context.Context, int64) (*live.LiveSession, error)
}

// LiveKit calls reconcile ephemeral connections; durable session changes remain in webhook handlers.
type Rooms interface {
	ListRooms(context.Context, *livekit.ListRoomsRequest) (*livekit.ListRoomsResponse, error)
	ListParticipants(context.Context, *livekit.ListParticipantsRequest) (*livekit.ListParticipantsResponse, error)
	RemoveParticipant(context.Context, *livekit.RoomParticipantIdentity) (*livekit.RemoveParticipantResponse, error)
	UpdateParticipant(context.Context, *livekit.UpdateParticipantRequest) (*livekit.ParticipantInfo, error)
	DeleteRoom(context.Context, *livekit.DeleteRoomRequest) (*livekit.DeleteRoomResponse, error)
}

type Service struct {
	rooms       Rooms
	permissions PermissionChecker
	live        LiveSessions
}

func New(rooms Rooms, permissions PermissionChecker, sessions LiveSessions) *Service {
	return &Service{rooms: rooms, permissions: permissions, live: sessions}
}

func (s *Service) Run(ctx context.Context) {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		if err := s.Reconcile(ctx); err != nil && ctx.Err() == nil {
			slog.Warn("Reconcile connected participant permissions", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (s *Service) Reconcile(ctx context.Context) error {
	listCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	rooms, err := s.rooms.ListRooms(listCtx, &livekit.ListRoomsRequest{})
	cancel()
	if err != nil {
		return err
	}
	if len(rooms.GetRooms()) == 0 {
		return nil
	}
	checkCtx, checkCancel := context.WithTimeout(ctx, 5*time.Second)
	check, checkErr := s.permissions.CurrentConnectCheck(checkCtx)
	checkCancel()
	if checkErr != nil {
		check = func(context.Context, int64, int64) (permission.Access, error) {
			return permission.Access{}, checkErr
		}
	}
	var failures []error
	for _, room := range rooms.GetRooms() {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		roomCtx, roomCancel := context.WithTimeout(ctx, 5*time.Second)
		err := s.reconcileRoom(roomCtx, room.GetName(), check)
		roomCancel()
		if err != nil {
			failures = append(failures, fmt.Errorf("room %s: %w", room.GetName(), err))
		}
	}
	return errors.Join(failures...)
}

func (s *Service) reconcileRoom(ctx context.Context, name string, check permission.ConnectCheck) error {
	var channelID, hostID int64
	var hostAccess permission.Access
	var hostErr error
	if parsed, ok := live.ParseRoomName(name); ok && parsed.ChannelID > 0 {
		channelID = parsed.ChannelID
		session, err := s.live.FindActiveSession(ctx, channelID)
		if err != nil {
			return errors.Join(err, s.pauseRoom(ctx, name))
		}
		if session == nil || session.RoomName != name || session.SessionID != parsed.SessionID {
			_, err := s.rooms.DeleteRoom(ctx, &livekit.DeleteRoomRequest{Room: name})
			return err
		}
		hostID = session.HostUserID
		hostAccess, hostErr = check(ctx, channelID, hostID)
		if denied(hostErr) || (hostErr == nil && !hostAccess.Broadcast) {
			_, err := s.rooms.DeleteRoom(ctx, &livekit.DeleteRoomRequest{Room: name})
			return err
		}
	} else if parsed, ok := voice.ParseRoomName(name); ok && parsed.ChannelID > 0 {
		channelID = parsed.ChannelID
	} else {
		return nil
	}

	participants, err := s.rooms.ListParticipants(ctx, &livekit.ListParticipantsRequest{Room: name})
	if err != nil {
		return err
	}
	var failures []error
	for _, participant := range participants.GetParticipants() {
		userID, parseErr := strconv.ParseInt(participant.GetIdentity(), 10, 64)
		access, accessErr := hostAccess, hostErr
		if parseErr != nil || userID <= 0 {
			accessErr = apperr.PermissionDenied()
		} else if userID != hostID {
			access, accessErr = check(ctx, channelID, userID)
		}
		if denied(accessErr) {
			_, err = s.rooms.RemoveParticipant(ctx, &livekit.RoomParticipantIdentity{Room: name, Identity: participant.GetIdentity()})
		} else {
			// Unknown authorization disables media without disconnecting; a later ready pass restores it.
			known := accessErr == nil
			publisher := hostID == 0 || userID == hostID
			desired := mediaPermission(known, publisher && access.Speak, publisher && access.ScreenShare, hostID != 0)
			if sameMedia(participant.GetPermission(), desired) {
				continue
			}
			_, err = s.rooms.UpdateParticipant(ctx, &livekit.UpdateParticipantRequest{
				Room: name, Identity: participant.GetIdentity(), Permission: desired,
			})
		}
		if err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

func (s *Service) pauseRoom(ctx context.Context, name string) error {
	participants, err := s.rooms.ListParticipants(ctx, &livekit.ListParticipantsRequest{Room: name})
	if err != nil {
		return err
	}
	var failures []error
	for _, participant := range participants.GetParticipants() {
		desired := mediaPermission(false, false, false, true)
		if sameMedia(participant.GetPermission(), desired) {
			continue
		}
		if _, err := s.rooms.UpdateParticipant(ctx, &livekit.UpdateParticipantRequest{
			Room: name, Identity: participant.GetIdentity(), Permission: desired,
		}); err != nil {
			failures = append(failures, err)
		}
	}
	return errors.Join(failures...)
}

func denied(err error) bool {
	var apiErr *apperr.Error
	return errors.As(err, &apiErr) && apiErr.HTTPStatus == http.StatusForbidden
}

func mediaPermission(known, speak, screenShare, liveRoom bool) *livekit.ParticipantPermission {
	media := permission.MediaAccessFor(known, speak, screenShare, liveRoom)
	p := &livekit.ParticipantPermission{CanSubscribe: media.Subscribe, CanPublishData: media.PublishData}
	if media.Microphone {
		p.CanPublishSources = append(p.CanPublishSources, livekit.TrackSource_MICROPHONE)
	}
	if media.ScreenShare {
		p.CanPublishSources = append(p.CanPublishSources, livekit.TrackSource_SCREEN_SHARE, livekit.TrackSource_SCREEN_SHARE_AUDIO)
	}
	if media.Camera {
		p.CanPublishSources = append(p.CanPublishSources, livekit.TrackSource_CAMERA)
	}
	p.CanPublish = len(p.CanPublishSources) > 0
	return p
}

func sameMedia(current, desired *livekit.ParticipantPermission) bool {
	if current == nil || current.GetCanSubscribe() != desired.CanSubscribe ||
		current.GetCanPublish() != desired.CanPublish || current.GetCanPublishData() != desired.CanPublishData ||
		len(current.GetCanPublishSources()) != len(desired.CanPublishSources) {
		return false
	}
	for _, source := range desired.CanPublishSources {
		if !slices.Contains(current.GetCanPublishSources(), source) {
			return false
		}
	}
	return true
}
