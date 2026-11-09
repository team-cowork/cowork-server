package permissionsync

import (
	"context"
	"errors"
	"testing"

	"github.com/livekit/protocol/livekit"

	"github.com/cowork/cowork-voice/internal/apperr"
	live "github.com/cowork/cowork-voice/internal/domain/live_room"
	"github.com/cowork/cowork-voice/internal/domain/permission"
	voice "github.com/cowork/cowork-voice/internal/domain/voice_room"
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
	access       permission.Access
	err          error
	prepareErr   error
	preparations int
	deniedUsers  map[int64]bool
}

func (s *stubPermissions) RequireConnect(_ context.Context, _, userID int64) (permission.Access, error) {
	if s.deniedUsers[userID] {
		return permission.Access{}, apperr.PermissionDenied()
	}
	return s.access, s.err
}

func (s *stubPermissions) CurrentConnectCheck(context.Context) (permission.ConnectCheck, error) {
	s.preparations++
	return s.RequireConnect, s.prepareErr
}

type stubSessions struct {
	session *live.LiveSession
	err     error
}

func (s stubSessions) FindActiveSession(context.Context, int64) (*live.LiveSession, error) {
	return s.session, s.err
}

type stubVoiceSessions struct {
	session *voice.VoiceSession
	err     error
}

func (s stubVoiceSessions) FindActiveSession(context.Context, int64) (*voice.VoiceSession, error) {
	return s.session, s.err
}

var activeVoice = stubVoiceSessions{session: &voice.VoiceSession{SessionID: "session", RoomName: "voice-10-session"}}

func TestVoiceRoomSessionReconciliation(t *testing.T) {
	for name, sessions := range map[string]stubVoiceSessions{
		"no active session": {},
		"newer session":     {session: &voice.VoiceSession{SessionID: "next", RoomName: "voice-10-next"}},
	} {
		t.Run(name+" deletes the stale room", func(t *testing.T) {
			rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7"}}}
			if err := New(rooms, &stubPermissions{}, stubSessions{}, sessions).Reconcile(context.Background()); err != nil {
				t.Fatal(err)
			}
			if !rooms.deleted || len(rooms.updates) != 0 || len(rooms.removed) != 0 {
				t.Fatalf("stale voice room was not deleted: %+v", rooms)
			}
		})
	}
	t.Run("session lookup failure keeps the room and still reconciles participants", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7"}}}
		svc := New(rooms, &stubPermissions{err: apperr.PermissionDenied()}, stubSessions{}, stubVoiceSessions{err: errors.New("storage unavailable")})
		if err := svc.Reconcile(context.Background()); err == nil {
			t.Fatal("lookup failure must be reported")
		}
		if rooms.deleted || len(rooms.removed) != 1 {
			t.Fatalf("unexpected room handling on lookup failure: %+v", rooms)
		}
	})
}

func TestVoicePermissionReconciliation(t *testing.T) {
	t.Run("video permission includes camera while connected users retain data publishing", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7"}}}
		permissions := &stubPermissions{access: permission.Access{ScreenShare: true}}
		if err := New(rooms, permissions, stubSessions{}, activeVoice).Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if len(rooms.updates) != 1 || !rooms.updates[0].Permission.CanPublishData {
			t.Fatal("connected voice participants must retain data publishing")
		}
		camera := false
		for _, source := range rooms.updates[0].Permission.CanPublishSources {
			camera = camera || source == livekit.TrackSource_CAMERA
		}
		if !camera {
			t.Fatal("video permission must enable camera as well as screen sharing")
		}
	})
	t.Run("one current check preserves each participant's permissions", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{
			{Identity: "7", Permission: mediaPermission(true, true, true, false, false)},
			{Identity: "8", Permission: mediaPermission(true, true, true, false, false)},
		}}
		permissions := &stubPermissions{access: permission.Access{Speak: true}, deniedUsers: map[int64]bool{8: true}}
		if err := New(rooms, permissions, stubSessions{}, activeVoice).Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if permissions.preparations != 1 || len(rooms.removed) != 1 || rooms.removed[0] != "8" ||
			len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, mediaPermission(true, true, false, false, false)) {
			t.Fatalf("unexpected participant permissions after %d current checks: %+v", permissions.preparations, rooms)
		}
	})
	t.Run("failed current check pauses every participant without using projected grants", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{
			{Identity: "7", Permission: mediaPermission(true, true, true, false, false)},
			{Identity: "8", Permission: mediaPermission(true, true, true, false, false)},
		}}
		permissions := &stubPermissions{
			access: permission.Access{Speak: true, ScreenShare: true}, prepareErr: apperr.ServiceUnavailable("catching up"),
		}
		if err := New(rooms, permissions, stubSessions{}, activeVoice).Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if permissions.preparations != 1 || len(rooms.removed) != 0 || len(rooms.updates) != 2 {
			t.Fatal("unknown current permissions must pause every participant")
		}
		for _, update := range rooms.updates {
			if !sameMedia(update.Permission, mediaPermission(false, false, false, false, false)) {
				t.Fatal("a stale projected grant enabled media")
			}
		}
	})
	t.Run("revoked connect removes the participant", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7"}}}
		svc := New(rooms, &stubPermissions{err: apperr.PermissionDenied()}, stubSessions{}, activeVoice)
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if len(rooms.removed) != 1 || rooms.removed[0] != "7" || len(rooms.updates) != 0 {
			t.Fatalf("unexpected removal: %+v", rooms)
		}
	})
	t.Run("revoked speaking retains screen sharing and unchanged grants are not rewritten", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true, false, false)}}}
		svc := New(rooms, &stubPermissions{access: permission.Access{ScreenShare: true}}, stubSessions{}, activeVoice)
		for range 2 {
			if err := svc.Reconcile(context.Background()); err != nil {
				t.Fatal(err)
			}
		}
		if len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, mediaPermission(true, false, true, false, false)) {
			t.Fatalf("unexpected update: %+v", rooms.updates)
		}
	})
	t.Run("unready projection pauses media without disconnect and restores when ready", func(t *testing.T) {
		rooms := &stubRooms{name: "voice-10-session", participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true, false, false)}}}
		permissions := &stubPermissions{err: apperr.ServiceUnavailable("catching up")}
		svc := New(rooms, permissions, stubSessions{}, activeVoice)
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
		if len(rooms.updates) != 2 || !sameMedia(rooms.updates[1].Permission, mediaPermission(true, true, false, false, false)) {
			t.Fatal("ready permissions were not restored")
		}
	})
}

func TestLivePermissionReconciliation(t *testing.T) {
	session := &live.LiveSession{ChannelID: 10, SessionID: "session", RoomName: "live-10-session", HostUserID: 7}
	t.Run("session lookup failure pauses everyone without deleting the room", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{
			{Identity: "7", Permission: mediaPermission(true, true, true, true, true)},
			{Identity: "8", Permission: mediaPermission(true, false, false, true, false)},
		}}
		svc := New(rooms, &stubPermissions{}, stubSessions{err: errors.New("storage unavailable")}, stubVoiceSessions{})
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
		svc := New(rooms, &stubPermissions{access: permission.Access{Speak: true}}, stubSessions{session: session}, stubVoiceSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if !rooms.deleted {
			t.Fatal("revoked live host must close room")
		}
	})
	t.Run("viewer video permission grants camera only even with broadcasting and speaking permissions", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{{Identity: "8", Permission: mediaPermission(true, true, true, true, true)}}}
		svc := New(rooms, &stubPermissions{access: permission.Access{Speak: true, ScreenShare: true, Broadcast: true}}, stubSessions{session: session}, stubVoiceSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		cameraOnly := &livekit.ParticipantPermission{
			CanSubscribe: true, CanPublish: true, CanPublishSources: []livekit.TrackSource{livekit.TrackSource_CAMERA},
		}
		if rooms.deleted || len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, cameraOnly) {
			t.Fatalf("viewer must publish camera only: %+v", rooms.updates)
		}
	})
	t.Run("revoking viewer video permission stops camera publishing", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{{Identity: "8", Permission: mediaPermission(true, false, true, true, false)}}}
		svc := New(rooms, &stubPermissions{access: permission.Access{Speak: true, Broadcast: true}}, stubSessions{session: session}, stubVoiceSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		subscribeOnly := &livekit.ParticipantPermission{CanSubscribe: true}
		if rooms.deleted || len(rooms.removed) != 0 || len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, subscribeOnly) {
			t.Fatalf("revoked viewer must stay connected without camera: %+v", rooms.updates)
		}
	})
	t.Run("host camera follows video permission without ending the broadcast", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true, true, true)}}}
		svc := New(rooms, &stubPermissions{access: permission.Access{Speak: true, Broadcast: true}}, stubSessions{session: session}, stubVoiceSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		microphoneOnly := &livekit.ParticipantPermission{
			CanSubscribe: true, CanPublish: true, CanPublishSources: []livekit.TrackSource{livekit.TrackSource_MICROPHONE},
		}
		if rooms.deleted || len(rooms.updates) != 1 || !sameMedia(rooms.updates[0].Permission, microphoneOnly) {
			t.Fatalf("host without video must keep broadcasting audio only: %+v", rooms.updates)
		}
	})
	t.Run("unready host is paused without ending the broadcast session", func(t *testing.T) {
		rooms := &stubRooms{name: session.RoomName, participants: []*livekit.ParticipantInfo{{Identity: "7", Permission: mediaPermission(true, true, true, true, true)}}}
		svc := New(rooms, &stubPermissions{err: apperr.ServiceUnavailable("catching up")}, stubSessions{session: session}, stubVoiceSessions{})
		if err := svc.Reconcile(context.Background()); err != nil {
			t.Fatal(err)
		}
		if rooms.deleted || len(rooms.removed) != 0 || len(rooms.updates) != 1 || rooms.updates[0].Permission.CanPublish {
			t.Fatal("unknown access should pause rather than end a live session")
		}
	})
}
