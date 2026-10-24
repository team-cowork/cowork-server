package channel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/domain/permission"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
)

const permissionCollection = "channel_permission_sources"

var ErrInvalidEvent = errors.New("invalid permission state event")
var permissionTopics = []string{"channel.event.v2", "team.member.event", "preference.team-role.state.v2", "preference.channel-role-policy.state.v2"}

func expectedSource(topic string) string {
	switch topic {
	case "channel.event.v2":
		return "cowork-channel"
	case "team.member.event":
		return "cowork-team"
	case "preference.team-role.state.v2", "preference.channel-role-policy.state.v2":
		return "cowork-preference"
	default:
		return ""
	}
}

// State copies source facts. No channel/user membership or computed access row is stored.
type State struct {
	Key         string          `bson:"_id"`
	Kind        string          `bson:"kind"`
	TeamID      int64           `bson:"team_id"`
	ChannelID   int64           `bson:"channel_id"`
	UserID      int64           `bson:"user_id"`
	RoleID      int64           `bson:"role_id"`
	Role        string          `bson:"role"`
	ChannelType string          `bson:"channel_type"`
	IsPrivate   *bool           `bson:"is_private"`
	Priority    int             `bson:"priority"`
	IsDefault   bool            `bson:"is_default"`
	Permissions map[string]bool `bson:"permissions"`
	Deleted     bool            `bson:"deleted"`
	Version     int64           `bson:"source_version"`
}

type CurrentHighChecker interface{ EstablishCurrent(context.Context) error }
type StateStore interface {
	Apply(context.Context, State) error
}
type accessLookup interface {
	Channel(context.Context, int64) (State, bool, error)
	TeamStates(context.Context, int64) ([]State, error)
}
type Projection struct {
	col           *mongo.Collection
	lookup        accessLookup
	currentHighMu sync.RWMutex
	currentHigh   CurrentHighChecker
}

func NewProjection(db *mongo.Database) *Projection {
	p := &Projection{col: db.Collection(permissionCollection)}
	p.lookup = p
	return p
}
func (p *Projection) SetCurrentHighChecker(c CurrentHighChecker) {
	p.currentHighMu.Lock()
	defer p.currentHighMu.Unlock()
	p.currentHigh = c
}
func CreateIndexes(ctx context.Context, db *mongo.Database) error {
	_, err := db.Collection(permissionCollection).Indexes().CreateOne(ctx, mongo.IndexModel{Keys: bson.D{{Key: "team_id", Value: 1}, {Key: "kind", Value: 1}}})
	return err
}
func (p *Projection) Channel(ctx context.Context, id int64) (State, bool, error) {
	var state State
	err := p.col.FindOne(ctx, bson.M{"_id": fmt.Sprintf("channel:%d", id)}).Decode(&state)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return state, false, nil
	}
	return state, err == nil, err
}
func (p *Projection) TeamStates(ctx context.Context, id int64) ([]State, error) {
	cursor, err := p.col.Find(ctx, bson.M{"team_id": id, "kind": bson.M{"$ne": "channel"}})
	if err != nil {
		return nil, err
	}
	var states []State
	err = cursor.All(ctx, &states)
	return states, err
}
func (p *Projection) RequireConnect(ctx context.Context, channelID, userID int64) (permission.Access, error) {
	p.currentHighMu.RLock()
	checker := p.currentHigh
	p.currentHighMu.RUnlock()
	if checker == nil {
		return permission.Access{}, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	if err := checker.EstablishCurrent(ctx); err != nil {
		return permission.Access{}, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	ch, found, err := p.lookup.Channel(ctx, channelID)
	if err != nil {
		return permission.Access{}, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	if !found || ch.Deleted || ch.ChannelType != "VOICE" || ch.TeamID <= 0 {
		return permission.Access{}, apperr.PermissionDenied()
	}
	states, err := p.lookup.TeamStates(ctx, ch.TeamID)
	if err != nil {
		return permission.Access{}, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	access := permissionsFor(ch, userID, states)
	if !access["voice_connect"] {
		return permission.Access{}, apperr.PermissionDenied()
	}
	return permission.Access{TeamID: ch.TeamID, Speak: access["voice_speak"], ScreenShare: access["screen_share"], Broadcast: access["live_broadcast"]}, nil
}
func canConnect(ch State, userID int64, states []State) bool {
	return permissionsFor(ch, userID, states)["voice_connect"]
}

func permissionsFor(ch State, userID int64, states []State) map[string]bool {
	var member *State
	var fence int64
	roles := map[int64]State{}
	policies := map[int64]State{}
	assignments := []State{}
	for _, s := range states {
		if s.TeamID != ch.TeamID {
			continue
		}
		switch s.Kind {
		case "member":
			if s.UserID == userID {
				v := s
				member = &v
			}
		case "member-fence":
			if s.UserID == userID && s.Version > fence {
				fence = s.Version
			}
		case "role":
			if !s.Deleted {
				roles[s.RoleID] = s
			}
		case "assignment":
			if s.UserID == userID && !s.Deleted {
				assignments = append(assignments, s)
			}
		case "policy":
			if s.ChannelID == ch.ChannelID && !s.Deleted {
				policies[s.RoleID] = s
			}
		}
	}
	if member == nil || member.Deleted || ch.Deleted || ch.ChannelType != "VOICE" {
		return nil
	}
	keys := []string{"voice_connect", "voice_speak", "screen_share", "live_broadcast"}
	allowed := make(map[string]bool, len(keys))
	if member.Role == "OWNER" || member.Role == "ADMIN" {
		for _, key := range keys {
			allowed[key] = true
		}
		return allowed
	}
	applicable := map[int64]State{}
	for id, role := range roles {
		if role.IsDefault {
			applicable[id] = role
		}
	}
	for _, assignment := range assignments {
		if role, ok := roles[assignment.RoleID]; ok && assignment.Version > fence {
			applicable[assignment.RoleID] = role
		}
	}
	for _, key := range keys {
		for _, role := range applicable {
			allowed[key] = allowed[key] || role.Permissions[key]
		}
		if key == "voice_connect" && (ch.IsPrivate == nil || *ch.IsPrivate) {
			allowed[key] = false
		}
		for id, role := range applicable {
			if value, exists := policies[id].Permissions[key]; role.IsDefault && exists {
				allowed[key] = value
			}
		}
		deny, allow := false, false
		for id, role := range applicable {
			if value, exists := policies[id].Permissions[key]; !role.IsDefault && exists {
				deny = deny || !value
				allow = allow || value
			}
		}
		allowed[key] = (allowed[key] && !deny) || allow
	}
	if !allowed["voice_connect"] {
		return nil
	}
	return allowed
}
func (p *Projection) Apply(ctx context.Context, s State) error {
	op := "$lt"
	if s.Deleted {
		op = "$lte"
	}
	filter := bson.M{"_id": s.Key, "$or": bson.A{bson.M{"source_version": bson.M{"$exists": false}}, bson.M{"source_version": bson.M{op: s.Version}}}}
	_, err := p.col.UpdateOne(ctx, filter, bson.M{"$set": s}, options.UpdateOne().SetUpsert(true))
	if mongo.IsDuplicateKeyError(err) {
		// TODO(#596): 2026-11-01 05:00 KST 이후 역할 문서 보정·구버전 롤백 지원 종료 확인 후 이 동일 버전 보정을 제거한다.
		// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
		if s.Kind == "role" && !s.Deleted {
			legacy := bson.M{"_id": s.Key, "kind": "role", "source_version": s.Version, "deleted": false, "is_default": bson.M{"$exists": false}}
			if s.IsDefault {
				// Default-role identity is immutable; an older producer must never clear a restored true value.
				legacy["is_default"] = bson.M{"$ne": true}
			}
			_, err = p.col.UpdateOne(ctx, legacy, bson.M{"$set": bson.M{"is_default": s.IsDefault}})
			return err
		}
		return nil
	}
	return err
}

type EventHandler struct{ store StateStore }

func NewEventHandler(s StateStore) *EventHandler { return &EventHandler{store: s} }
func (h *EventHandler) Handle(ctx context.Context, topic, key string, payload []byte) error {
	s, err := parseState(topic, key, payload)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrInvalidEvent, err)
	}
	if s.Kind == "member" && s.Deleted {
		fence := State{Key: fmt.Sprintf("role-source:member:%d:%d", s.TeamID, s.UserID), Kind: "member-fence", TeamID: s.TeamID, UserID: s.UserID, Deleted: true, Version: s.Version}
		if err := h.store.Apply(ctx, fence); err != nil {
			return err
		}
	}
	return h.store.Apply(ctx, s)
}
func parseState(topic, key string, payload []byte) (State, error) {
	// TODO(#596): 2026-11-01 05:00 KST 이후 구형 레코드 재처리·롤백 지원 종료 확인 후 ROLE_UPSERTED의 isDefault 누락 허용 제거.
	// 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
	var e struct {
		EventType     string           `json:"eventType"`
		TeamID        int64            `json:"teamId"`
		ChannelID     int64            `json:"channelId"`
		UserID        int64            `json:"userId"`
		AccountID     int64            `json:"accountId"`
		RoleID        int64            `json:"roleId"`
		Role          string           `json:"role"`
		Type          string           `json:"type"`
		IsPrivate     *bool            `json:"isPrivate"`
		Priority      *int             `json:"priority"`
		IsDefault     bool             `json:"isDefault"`
		Permissions   map[string]*bool `json:"permissions"`
		SchemaVersion int              `json:"schemaVersion"`
		OccurredAt    string           `json:"occurredAt"`
	}
	if err := json.Unmarshal(payload, &e); err != nil {
		return State{}, err
	}
	version, err := parseOccurredAt(e.OccurredAt)
	if err != nil {
		return State{}, err
	}
	permissions := make(map[string]bool, len(e.Permissions))
	for name, value := range e.Permissions {
		if value == nil {
			return State{}, errors.New("permission must be a boolean")
		}
		permissions[name] = *value
	}
	s := State{TeamID: e.TeamID, ChannelID: e.ChannelID, UserID: e.UserID, RoleID: e.RoleID, Role: e.Role, ChannelType: e.Type, IsPrivate: e.IsPrivate, IsDefault: e.IsDefault, Permissions: permissions, Version: version.UnixNano()}
	expected := ""
	switch topic {
	case "channel.event.v2":
		if e.ChannelID <= 0 || (e.Type != "TEXT" && e.Type != "VOICE" && e.Type != "DM") || (e.Type != "DM" && e.TeamID <= 0) || (e.Type == "DM" && e.TeamID != 0) {
			return s, errors.New("invalid channel identity")
		}
		if e.EventType != "CREATED" && e.EventType != "UPDATED" && e.EventType != "DELETED" {
			return s, errors.New("invalid channel event")
		}
		expected = fmt.Sprint(e.ChannelID)
		s.Kind = "channel"
		s.Key = "channel:" + expected
		s.Deleted = e.EventType == "DELETED"
	case "team.member.event":
		if e.TeamID <= 0 || e.UserID <= 0 || (e.Role != "OWNER" && e.Role != "ADMIN" && e.Role != "MEMBER") || (e.EventType != "UPSERT" && e.EventType != "DELETE") {
			return s, errors.New("invalid team member")
		}
		expected = fmt.Sprintf("%d:%d", e.TeamID, e.UserID)
		s.Kind = "member"
		s.Key = "member:" + expected
		s.Deleted = e.EventType == "DELETE"
	case "preference.team-role.state.v2":
		if e.TeamID <= 0 {
			return s, errors.New("invalid team")
		}
		switch e.EventType {
		case "ROLE_UPSERTED", "ROLE_DELETED":
			if e.RoleID <= 0 {
				return s, errors.New("invalid role")
			}
			expected = fmt.Sprintf("role:%d:%d", e.TeamID, e.RoleID)
			s.Kind = "role"
			s.Deleted = e.EventType == "ROLE_DELETED"
			if !s.Deleted {
				if e.Priority == nil || e.Permissions == nil {
					return s, errors.New("missing role permissions")
				}
				s.Priority = *e.Priority
			}
		case "ASSIGNMENT_UPSERTED", "ASSIGNMENT_DELETED":
			if e.RoleID <= 0 || e.AccountID <= 0 {
				return s, errors.New("invalid assignment")
			}
			expected = fmt.Sprintf("assignment:%d:%d:%d", e.TeamID, e.AccountID, e.RoleID)
			s.Kind = "assignment"
			s.UserID = e.AccountID
			s.Deleted = e.EventType == "ASSIGNMENT_DELETED"
		case "MEMBER_ASSIGNMENTS_DELETED":
			if e.AccountID <= 0 {
				return s, errors.New("invalid account")
			}
			expected = fmt.Sprintf("member:%d:%d", e.TeamID, e.AccountID)
			s.Kind = "member-fence"
			s.UserID = e.AccountID
			s.Deleted = true
		default:
			return s, errors.New("invalid role event")
		}
		s.Key = "role-source:" + expected
	case "preference.channel-role-policy.state.v2":
		if e.TeamID <= 0 || e.ChannelID <= 0 || e.RoleID <= 0 || e.SchemaVersion != 2 || (e.EventType != "UPSERT" && e.EventType != "DELETE") {
			return s, errors.New("invalid policy")
		}
		s.Deleted = e.EventType == "DELETE"
		if (!s.Deleted && len(e.Permissions) == 0) || (s.Deleted && e.Permissions != nil) {
			return s, errors.New("invalid policy permissions")
		}
		expected = fmt.Sprintf("policy:%d:%d:%d", e.TeamID, e.ChannelID, e.RoleID)
		s.Key = expected
		s.Kind = "policy"
	default:
		return s, errors.New("unexpected topic")
	}
	if key != expected {
		return s, errors.New("state key mismatch")
	}
	return s, nil
}
func parseOccurredAt(value string) (time.Time, error) {
	p, e := time.Parse(time.RFC3339Nano, value)
	if e != nil {
		return time.Time{}, e
	}
	if p.UnixNano() <= 0 || !time.Unix(0, p.UnixNano()).Equal(p) {
		return time.Time{}, errors.New("invalid event time range")
	}
	return p.UTC(), nil
}
