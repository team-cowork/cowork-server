package liveroom

import (
	"context"
	"errors"
	"slices"
	"testing"
	"time"

	"github.com/livekit/protocol/livekit"
)

var recoveryNow = time.Unix(1700000000, 0).UTC()

func creatingSession(id string, age time.Duration) *Session {
	return &Session{
		SessionID: id,
		ChannelID: 123,
		RoomName:  "live-123-" + id,
		Status:    StatusActive,
		StartedAt: recoveryNow.Add(-age),
	}
}

func newTestStartRecovery(repo *fakeStartRecoveryRepo, rooms *fakeRoomLister) *StartRecovery {
	r := NewStartRecovery(repo, rooms)
	r.now = func() time.Time { return recoveryNow }
	return r
}

func TestStartRecovery(t *testing.T) {
	t.Run("유예 전 생성 중 세션은 방이 없어도 종료하지 않는다", func(t *testing.T) {
		t.Parallel()

		session := creatingSession("s1", startRecoveryGrace-time.Second)
		repo := &fakeStartRecoveryRepo{sessions: []*Session{session}}
		rooms := &fakeRoomLister{}

		ended, err := newTestStartRecovery(repo, rooms).Recover(context.Background())
		if err != nil {
			t.Fatalf("Recover() error = %v", err)
		}
		if ended != 0 || session.Status != StatusActive {
			t.Fatalf("ended = %d, status = %q, want 0 and active", ended, session.Status)
		}
		if rooms.calls != 0 {
			t.Fatalf("ListRooms() calls = %d, want 0", rooms.calls)
		}
	})

	t.Run("유예가 지났고 방이 없으면 종료한다", func(t *testing.T) {
		t.Parallel()

		session := creatingSession("s1", startRecoveryGrace+time.Second)
		repo := &fakeStartRecoveryRepo{sessions: []*Session{session}}
		rooms := &fakeRoomLister{}

		ended, err := newTestStartRecovery(repo, rooms).Recover(context.Background())
		if err != nil {
			t.Fatalf("Recover() error = %v", err)
		}
		if ended != 1 || session.Status != StatusEnded {
			t.Fatalf("ended = %d, status = %q, want 1 and ended", ended, session.Status)
		}
		if !slices.Equal(rooms.names, []string{"live-123-s1"}) {
			t.Fatalf("ListRooms() names = %v, want [live-123-s1]", rooms.names)
		}
	})

	t.Run("방이 있으면 종료하지 않는다", func(t *testing.T) {
		t.Parallel()

		present := creatingSession("s1", startRecoveryGrace+time.Second)
		absent := creatingSession("s2", startRecoveryGrace+time.Second)
		repo := &fakeStartRecoveryRepo{sessions: []*Session{present, absent}}
		rooms := &fakeRoomLister{present: []string{"live-123-s1"}}

		ended, err := newTestStartRecovery(repo, rooms).Recover(context.Background())
		if err != nil {
			t.Fatalf("Recover() error = %v", err)
		}
		if ended != 1 || present.Status != StatusActive || absent.Status != StatusEnded {
			t.Fatalf("ended = %d, present = %q, absent = %q", ended, present.Status, absent.Status)
		}
	})

	t.Run("방 조회가 실패하면 부재로 보지 않고 회차를 건너뛴다", func(t *testing.T) {
		t.Parallel()

		session := creatingSession("s1", startRecoveryGrace+time.Second)
		repo := &fakeStartRecoveryRepo{sessions: []*Session{session}}
		rooms := &fakeRoomLister{err: errors.New("livekit unavailable")}

		ended, err := newTestStartRecovery(repo, rooms).Recover(context.Background())
		if err == nil {
			t.Fatal("Recover() error = nil, want list error")
		}
		if ended != 0 || session.Status != StatusActive || repo.endCalls != 0 {
			t.Fatalf("ended = %d, status = %q, end calls = %d", ended, session.Status, repo.endCalls)
		}
	})

	t.Run("그사이 호스트가 입장했으면 조건부 종료가 no-op이다", func(t *testing.T) {
		t.Parallel()

		session := creatingSession("s1", startRecoveryGrace+time.Second)
		repo := &fakeStartRecoveryRepo{sessions: []*Session{session}}
		rooms := &fakeRoomLister{onList: func() {
			joinedAt := recoveryNow
			session.StartedEventSentAt = &joinedAt
		}}

		ended, err := newTestStartRecovery(repo, rooms).Recover(context.Background())
		if err != nil {
			t.Fatalf("Recover() error = %v", err)
		}
		if ended != 0 || session.Status != StatusActive || repo.endCalls != 1 {
			t.Fatalf("ended = %d, status = %q, end calls = %d", ended, session.Status, repo.endCalls)
		}
	})

	t.Run("반복·동시 복구는 한 번만 종료한다", func(t *testing.T) {
		t.Parallel()

		session := creatingSession("s1", startRecoveryGrace+time.Second)
		repo := &fakeStartRecoveryRepo{sessions: []*Session{session}}
		// 두 레플리카가 같은 후보를 읽은 뒤 각자 종료를 시도하는 경우.
		repo.staleFind = true
		replicaA := newTestStartRecovery(repo, &fakeRoomLister{})
		replicaB := newTestStartRecovery(repo, &fakeRoomLister{})

		total := 0
		for _, r := range []*StartRecovery{replicaA, replicaB, replicaA} {
			ended, err := r.Recover(context.Background())
			if err != nil {
				t.Fatalf("Recover() error = %v", err)
			}
			total += ended
		}
		if total != 1 || session.Status != StatusEnded {
			t.Fatalf("total ended = %d, status = %q, want 1 and ended", total, session.Status)
		}
	})
}

// fakeStartRecoveryRepo는 Mongo 필터와 같은 조건으로 생성 중 세션을 찾고 종료한다.
type fakeStartRecoveryRepo struct {
	sessions []*Session
	// staleFind=true이면 이미 종료된 세션도 반환해, 다른 레플리카가 먼저 종료한 경쟁을 흉내 낸다.
	staleFind bool
	endCalls  int
}

func (f *fakeStartRecoveryRepo) FindCreatingSessionsBefore(_ context.Context, before time.Time) ([]Session, error) {
	var result []Session
	for _, s := range f.sessions {
		if (f.staleFind || creating(s)) && s.StartedAt.Before(before) {
			result = append(result, *s)
		}
	}
	return result, nil
}

func (f *fakeStartRecoveryRepo) EndCreatingSession(_ context.Context, sessionID string, endedAt time.Time) (bool, error) {
	f.endCalls++
	for _, s := range f.sessions {
		if s.SessionID == sessionID && creating(s) {
			s.Status = StatusEnded
			s.EndedAt = &endedAt
			return true, nil
		}
	}
	return false, nil
}

func creating(s *Session) bool {
	return s.Status == StatusActive && s.StartedEventSentAt == nil
}

type fakeRoomLister struct {
	present []string
	err     error
	onList  func()
	calls   int
	names   []string
}

func (f *fakeRoomLister) ListRooms(_ context.Context, req *livekit.ListRoomsRequest) (*livekit.ListRoomsResponse, error) {
	f.calls++
	f.names = req.GetNames()
	if f.onList != nil {
		f.onList()
	}
	if f.err != nil {
		return nil, f.err
	}
	res := &livekit.ListRoomsResponse{}
	for _, name := range f.present {
		res.Rooms = append(res.Rooms, &livekit.Room{Name: name})
	}
	return res, nil
}
