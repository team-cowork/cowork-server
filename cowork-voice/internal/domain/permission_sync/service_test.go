package permissionsync

import (
	"context"
	"errors"
	"testing"

	"github.com/livekit/protocol/livekit"

	"github.com/cowork/cowork-voice/internal/apperr"
	live "github.com/cowork/cowork-voice/internal/domain/live_room"
	"github.com/cowork/cowork-voice/internal/domain/permission"
)

type stubRooms struct {
	name         string
	participants []*livekit.ParticipantInfo
	removed      []string
	updates      []*livekit.UpdateParticipantRequest
	deleted      bool
}

func (s *stubRooms) ListRooms(context.Context, *livekit.ListRoomsRequest) (*livekit.ListRoomsResponse, error) {
	return &livekit.ListRoomsResponse{Rooms: []*livekit.Room{{Name: s.name}}}, nil
}
func (s *stubRooms) ListParticipants(context.Context, *livekit.ListParticipantsRequest) (*livekit.ListParticipantsResponse, error) {
	return &livekit.ListParticipantsResponse{Participants: s.participants}, nil
}
func (s *stubRooms) RemoveParticipant(_ context.Context, request *livekit.RoomParticipantIdentity) (*livekit.RemoveParticipantResponse, error) {
	s.removed = append(s.removed, request.Identity)
	return &livekit.RemoveParticipantResponse{}, nil
}
func (s *stubRooms) UpdateParticipant(_ context.Context, request *livekit.UpdateParticipantRequest) (*livekit.ParticipantInfo, error) {
	s.updates = append(s.updates, request)
	for _, participant := range s.participants {
		if participant.Identity == request.Identity {
			participant.Permission = request.Permission
		}
	}
	return &livekit.ParticipantInfo{}, nil
}
func (s *stubRooms) DeleteRoom(context.Context, *livekit.DeleteRoomRequest) (*livekit.DeleteRoomResponse, error) {
	s.deleted = true
	return &livekit.DeleteRoomResponse{}, nil
}

type stubPermissions struct {
	access permission.Access
	err    error
}

func (s *stubPermissions) RequireConnect(context.Context, int64, int64) (permission.Access, error) {
	return s.access, s.err
}

type stubSessions struct {
	session *live.LiveSession
	err     error
}

func (s stubSessions) FindActiveSession(context.Context, int64) (*live.LiveSession, error) {
	return s.session, s.err
}

func TestVoicePermissionReconciliation(t *testing.T) {
	t.Run("revoked connect removes the participant", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7"}}}
		svc := New(rooms, &stubPermissions{err: apperr.PermissionDenied()}, stubSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if len(rooms.removed) != 1 || rooms.removed[0] != "7" || len(rooms.updates) != 0 {
			t.Fatalf("unexpected removal: %+v", rooms)
		}
	})
	t.Run("revoked speaking retains screen sharing and unchanged grants are not rewritten", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true)}}}
		svc := New(rooms, &stubPermissions{access: permission.Access{ScreenShare: true}}, stubSessions{})
		for range 2 {
			if err := svc.Reconcile(context.Background()); err != nil {
				t.Fatal(err)
			}
		}
		if len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, mediaPermission(true, false, true)) {
			t.Fatalf("unexpected update: %+v", rooms.updates)
		}
	})
	t.Run("unready projection pauses media without disconnect and restores when ready", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true)}}}
		permissions := &stubPermissions{err: apperr.ServiceUnavailable("catching up")}
		svc := New(rooms, permissions, stubSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if len(rooms.removed) != 0 || len(rooms.updates) != 1 || rooms.updates[0].Permission.CanSubscribe || rooms.updates[0].Permission.CanPublish {
			t.Fatal("unready participants must stay connected without media access")
		}
		permissions.err = nil
		permissions.access = permission.Access{Speak: true}
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if len(rooms.updates) != 2 || !sameMedia(rooms.updates[1].Permission, mediaPermission(true, true, false)) {
			t.Fatal("ready permissions were not restored")
		}
	})
}

func TestLivePermissionReconciliation(t *testing.T) {
	session := &live.LiveSession{ChannelID: 10, SessionID: "session", RoomName: "live-10-session", HostUserID: 7}
	t.Run("session lookup failure pauses everyone without deleting the room", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{
			{Identity: "7", Permission: mediaPermission(true, true, true)},
			{Identity: "8", Permission: mediaPermission(true, false, false)},
		}}
		svc := New(rooms, &stubPermissions{}, stubSessions{err: errors.New("storage unavailable")})
		if err := svc.Reconcile(context.Background()); err == nil {
			t.Fatal("lookup failure must be reported")
		}
		if rooms.deleted || len(rooms.removed) != 0 || len(rooms.updates) != 2 {
			t.Fatal("lookup failure must pause rather than disconnect")
		}
		for _, update := range rooms.updates {
			if update.Permission.CanSubscribe || update.Permission.CanPublish {
				t.Fatal("unknown live session must not retain media access")
			}
		}
	})
	t.Run("revoking host broadcast closes the room for webhook cleanup", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName}
		svc := New(rooms, &stubPermissions{access: permission.Access{Speak: true}}, stubSessions{session: session})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if !rooms.deleted {
			t.Fatal("revoked live host must close room")
		}
	})
	t.Run("viewer stays subscribe-only even with broadcasting and speaking permissions", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{{Identity: "8", Permission: mediaPermission(true, true, true)}}}
		svc := New(rooms, &stubPermissions{access: permission.Access{Speak: true, ScreenShare: true, Broadcast: true}}, stubSessions{session: session})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if rooms.deleted || len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, mediaPermission(true, false, false)) {
			t.Fatal("viewer must never be promoted to publisher")
		}
	})
	t.Run("unready host is paused without ending the broadcast session", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true)}}}
		svc := New(rooms, &stubPermissions{err: apperr.ServiceUnavailable("catching up")}, stubSessions{session: session})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if rooms.deleted || len(rooms.removed) != 0 || len(rooms.updates) != 1 || rooms.updates[0].Permission.CanPublish {
			t.Fatal("unknown access should pause rather than end a live session")
		}
	})
}
