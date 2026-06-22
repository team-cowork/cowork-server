package relay

import (
	"context"
	"errors"
	"testing"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"

	mongoinfra "github.com/cowork/cowork-voice/internal/infra/mongo"
)

type fakeOutbox struct {
	msgs     []mongoinfra.OutboxMessage
	sent     map[bson.ObjectID]bool
	attempts map[bson.ObjectID]int
	failed   map[bson.ObjectID]bool
}

func newFakeOutbox(keys ...string) *fakeOutbox {
	f := &fakeOutbox{sent: map[bson.ObjectID]bool{}, attempts: map[bson.ObjectID]int{}, failed: map[bson.ObjectID]bool{}}
	for i, k := range keys {
		f.msgs = append(f.msgs, mongoinfra.OutboxMessage{
			ID:        bson.NewObjectID(),
			Key:       k,
			Payload:   []byte(`{"i":` + string(rune('0'+i)) + `}`),
			CreatedAt: time.Unix(int64(1700000000+i), 0).UTC(),
		})
	}
	return f
}

func (f *fakeOutbox) FetchUnsent(_ context.Context, limit int) ([]mongoinfra.OutboxMessage, error) {
	var out []mongoinfra.OutboxMessage
	for _, m := range f.msgs {
		if f.sent[m.ID] || f.failed[m.ID] {
			continue
		}
		out = append(out, m)
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}

func (f *fakeOutbox) MarkSent(_ context.Context, id bson.ObjectID, _ time.Time) error {
	f.sent[id] = true
	return nil
}

func (f *fakeOutbox) IncrementAttempts(_ context.Context, id bson.ObjectID) error {
	f.attempts[id]++
	return nil
}

func (f *fakeOutbox) MarkFailed(_ context.Context, id bson.ObjectID, _ time.Time) error {
	f.failed[id] = true
	return nil
}

type fakePublisher struct {
	published []string
	failOnKey string
}

func (p *fakePublisher) PublishRaw(_ context.Context, key string, _ []byte) error {
	if key == p.failOnKey {
		return errors.New("kafka down")
	}
	p.published = append(p.published, key)
	return nil
}

func TestDrain_모든_메시지를_순서대로_전송하고_sent로_표시한다(t *testing.T) {
	t.Parallel()

	outbox := newFakeOutbox("a", "b", "c")
	pub := &fakePublisher{}
	r := New(outbox, pub, time.Second, 100)

	r.drain()

	if len(pub.published) != 3 {
		t.Fatalf("published = %v, want 3 messages", pub.published)
	}
	if pub.published[0] != "a" || pub.published[1] != "b" || pub.published[2] != "c" {
		t.Fatalf("publish order = %v, want [a b c]", pub.published)
	}
	for _, m := range outbox.msgs {
		if !outbox.sent[m.ID] {
			t.Fatalf("message %q not marked sent", m.Key)
		}
	}
}

func TestDrain_전송_실패시_순서보존을_위해_멈추고_재시도_대상으로_남긴다(t *testing.T) {
	t.Parallel()

	outbox := newFakeOutbox("a", "b", "c")
	pub := &fakePublisher{failOnKey: "b"}
	r := New(outbox, pub, time.Second, 100)

	r.drain()

	// a만 전송/표시되고, b에서 멈춰 c는 건드리지 않아야 한다.
	if len(pub.published) != 1 || pub.published[0] != "a" {
		t.Fatalf("published = %v, want [a]", pub.published)
	}
	if !outbox.sent[outbox.msgs[0].ID] {
		t.Fatal("message a should be marked sent")
	}
	if outbox.sent[outbox.msgs[1].ID] {
		t.Fatal("message b should NOT be marked sent")
	}
	if outbox.attempts[outbox.msgs[1].ID] != 1 {
		t.Fatalf("message b attempts = %d, want 1", outbox.attempts[outbox.msgs[1].ID])
	}
	if outbox.attempts[outbox.msgs[2].ID] != 0 {
		t.Fatal("message c should be untouched")
	}
}

func TestDrain_재시도_한도_초과시_격리하고_큐를_진행시킨다(t *testing.T) {
	t.Parallel()

	outbox := newFakeOutbox("poison", "good")
	// 첫 메시지는 이미 한도 직전까지 실패한 상태 → 이번 실패로 한도 초과
	outbox.msgs[0].Attempts = maxPublishAttempts - 1
	pub := &fakePublisher{failOnKey: "poison"}
	r := New(outbox, pub, time.Second, 100)

	r.drain()

	// poison은 격리되고, 뒤의 good은 정상 전송되어야 한다(head-of-line blocking 해소).
	if !outbox.failed[outbox.msgs[0].ID] {
		t.Fatal("poison 메시지는 격리(MarkFailed)되어야 한다")
	}
	if outbox.sent[outbox.msgs[0].ID] {
		t.Fatal("poison 메시지는 sent로 표시되면 안 된다")
	}
	if len(pub.published) != 1 || pub.published[0] != "good" {
		t.Fatalf("published = %v, want [good]", pub.published)
	}
	if !outbox.sent[outbox.msgs[1].ID] {
		t.Fatal("good 메시지는 전송/표시되어야 한다")
	}
}
