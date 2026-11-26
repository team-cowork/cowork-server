package channel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/domain/permission"
)

const permissionCollection = "channel_permission_sources"

var ErrInvalidEvent = errors.New("invalid permission state event")
var permissionTopics = []string{"channel.event.v2", "team.member.event", "preference.channel-access.state.v1", "preference.voice-channel-setting.state.v1"}

func expectedSource(topic string) string {
	switch topic {
	case "channel.event.v2":
		return "cowork-channel"
	case "team.member.event":
		return "cowork-team"
	case "preference.channel-access.state.v1", "preference.voice-channel-setting.state.v1":
		return "cowork-preference"
	default:
		return ""
	}
}

// State copies source facts and Preference's channel access decisions.
type State struct {
	Key         string          `bson:"_id"`
	Kind        string          `bson:"kind"`
	TeamID      int64           `bson:"team_id"`
	ChannelID   int64           `bson:"channel_id"`
	UserID      int64           `bson:"user_id"`
	ChannelType string          `bson:"channel_type"`
	IsPrivate   *bool           `bson:"is_private"`
	Permissions map[string]bool `bson:"permissions"`
	Deleted     bool            `bson:"deleted"`
	Version     int64           `bson:"source_version"`
	// AuthorizationVersion은 member에서는 현재 인가 version이고, access에서는 판정에 쓴 인가 version이다.
	// 두 값을 microsecond로 맞춰 저장한다. 0은 인가 version이 없다는 뜻이다.
	AuthorizationVersion int64 `bson:"authorization_version"`
	// ReadPrivate은 access에서 채널이 비공개라면 접근할 수 있는지 여부다.
	ReadPrivate bool `bson:"read_private"`
	// MaxParticipants 0은 제한 없음, Bitrate nil은 클라이언트 기본값이다(voice-setting 전용).
	MaxParticipants int  `bson:"max_participants"`
	Bitrate         *int `bson:"bitrate"`
}

type CurrentHighChecker interface {
	EstablishCurrent(context.Context) error
	IsCurrent() bool
}
type StateStore interface {
	Apply(context.Context, State) error
}
type Projection struct {
	col           *mongo.Collection
	currentHighMu sync.RWMutex
	currentHigh   CurrentHighChecker
}

func NewProjection(db *mongo.Database) *Projection {
	return &Projection{col: db.Collection(permissionCollection)}
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

func (p *Projection) RequireConnect(ctx context.Context, channelID, userID int64) (permission.Access, error) {
	check, err := p.CurrentConnectCheck(ctx)
	if err != nil {
		return permission.Access{}, err
	}
	return check(ctx, channelID, userID)
}

// CurrentConnectCheck shares one fresh broker check within a reconciliation pass.
// Participant lookups still stop if the running consumer closes readiness.
func (p *Projection) CurrentConnectCheck(ctx context.Context) (permission.ConnectCheck, error) {
	p.currentHighMu.RLock()
	checker := p.currentHigh
	p.currentHighMu.RUnlock()
	if checker == nil {
		return nil, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	if err := checker.EstablishCurrent(ctx); err != nil {
		return nil, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	return func(ctx context.Context, channelID, userID int64) (permission.Access, error) {
		if !checker.IsCurrent() {
			return permission.Access{}, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
		}
		access, err := p.requireProjectedConnect(ctx, channelID, userID)
		if !checker.IsCurrent() {
			return permission.Access{}, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
		}
		return access, err
	}, nil
}

func (p *Projection) lookupState(ctx context.Context, key string) (*State, error) {
	var state State
	err := p.col.FindOne(ctx, bson.M{"_id": key}).Decode(&state)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.ServiceUnavailable("권한 정보가 아직 동기화되지 않았습니다.")
	}
	return &state, nil
}

func (p *Projection) requireProjectedConnect(ctx context.Context, channelID, userID int64) (permission.Access, error) {
	ch, err := p.lookupState(ctx, fmt.Sprintf("channel:%d", channelID))
	if err != nil {
		return permission.Access{}, err
	}
	if ch == nil || ch.Deleted || ch.ChannelType != "VOICE" || ch.TeamID <= 0 {
		return permission.Access{}, apperr.PermissionDenied()
	}
	member, err := p.lookupState(ctx, fmt.Sprintf("member:%d:%d", ch.TeamID, userID))
	if err != nil {
		return permission.Access{}, err
	}
	decision, err := p.lookupState(ctx, fmt.Sprintf("access:%d:%d", channelID, userID))
	if err != nil {
		return permission.Access{}, err
	}
	access := permissionsFor(*ch, member, decision)
	if !access["voice_connect"] {
		return permission.Access{}, apperr.PermissionDenied()
	}
	setting, err := p.lookupState(ctx, fmt.Sprintf("voice-setting:%d", channelID))
	if err != nil {
		return permission.Access{}, err
	}
	if setting == nil {
		setting = &State{}
	}
	return permission.Access{
		TeamID: ch.TeamID, Speak: access["voice_speak"], ScreenShare: access["screen_share"], Broadcast: access["live_broadcast"],
		MaxParticipants: setting.MaxParticipants, Bitrate: setting.Bitrate,
	}, nil
}

// permissionsFor returns Preference's decision only when it was computed from the member's current authorization.
// A member event can arrive before Preference republishes the decision, so a mismatched version denies until it catches up.
// A private transition keeps the authorization version, so a private channel also needs ReadPrivate. Unknown visibility is private.
func permissionsFor(ch State, member, decision *State) map[string]bool {
	if member == nil || member.Deleted || member.TeamID != ch.TeamID || member.AuthorizationVersion <= 0 ||
		decision == nil || decision.TeamID != ch.TeamID || decision.ChannelID != ch.ChannelID ||
		decision.AuthorizationVersion != member.AuthorizationVersion || !decision.Permissions["voice_connect"] {
		return nil
	}
	if (ch.IsPrivate == nil || *ch.IsPrivate) && !decision.ReadPrivate {
		return nil
	}
	return decision.Permissions
}
func (p *Projection) Apply(ctx context.Context, s State) error {
	op := "$lt"
	if s.Deleted {
		op = "$lte"
	}
	// An equal version is a replay of the same record and is ignored. Preference gives each changed decision a new version.
	accepted := bson.A{bson.M{"source_version": bson.M{"$exists": false}}, bson.M{"source_version": bson.M{op: s.Version}}}
	if s.Kind == "member" {
		// Members stored before authorization versions were kept take the field from the replayed record.
		// TODO: Remove this branch once the rollback window closes and all stored members have authorization_version.
		accepted = append(accepted, bson.M{"source_version": s.Version, "authorization_version": bson.M{"$exists": false}})
	}
	filter := bson.M{"_id": s.Key, "$or": accepted}
	_, err := p.col.UpdateOne(ctx, filter, bson.M{"$set": s}, options.UpdateOne().SetUpsert(true))
	if mongo.IsDuplicateKeyError(err) {
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
	return h.store.Apply(ctx, s)
}
func parseState(topic, key string, payload []byte) (State, error) {
	var e struct {
		EventType                  string           `json:"eventType"`
		TeamID                     int64            `json:"teamId"`
		ChannelID                  int64            `json:"channelId"`
		UserID                     int64            `json:"userId"`
		AccountID                  int64            `json:"accountId"`
		Role                       string           `json:"role"`
		Type                       string           `json:"type"`
		IsPrivate                  *bool            `json:"isPrivate"`
		Permissions                map[string]*bool `json:"permissions"`
		ReadPrivate                *bool            `json:"readPrivate"`
		SchemaVersion              int              `json:"schemaVersion"`
		OccurredAt                 string           `json:"occurredAt"`
		AuthorizationVersion       *string          `json:"authorizationVersion"`
		SourceMembershipVersion    string           `json:"sourceMembershipVersion"`
		SourceAuthorizationVersion *string          `json:"sourceAuthorizationVersion"`
		MaxParticipants            *int             `json:"maxParticipants"`
		Bitrate                    *int             `json:"bitrate"`
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
	s := State{TeamID: e.TeamID, ChannelID: e.ChannelID, UserID: e.UserID, ChannelType: e.Type, IsPrivate: e.IsPrivate, Permissions: permissions, Version: version.UnixNano()}
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
		if e.AuthorizationVersion == nil {
			return s, errors.New("missing authorization version")
		}
		authorization, err := parseOccurredAt(*e.AuthorizationVersion)
		if err != nil || authorization.After(version) {
			return s, errors.New("invalid authorization version")
		}
		expected = fmt.Sprintf("%d:%d", e.TeamID, e.UserID)
		s.Kind = "member"
		s.Key = "member:" + expected
		s.Deleted = e.EventType == "DELETE"
		s.AuthorizationVersion = toMicros(authorization)
	case "preference.channel-access.state.v1":
		if e.EventType != "UPSERT" || e.TeamID <= 0 || e.ChannelID <= 0 || e.AccountID <= 0 || e.ReadPrivate == nil || len(permissions) == 0 {
			return s, errors.New("invalid channel access")
		}
		// Preference sends the Unix epoch as the membership version of a member who is not active.
		membership, err := time.Parse(time.RFC3339Nano, e.SourceMembershipVersion)
		if err != nil || membership.Before(time.Unix(0, 0)) {
			return s, errors.New("invalid membership version")
		}
		if e.SourceAuthorizationVersion != nil {
			authorization, err := parseOccurredAt(*e.SourceAuthorizationVersion)
			if err != nil || authorization.After(membership) {
				return s, errors.New("invalid authorization version")
			}
			s.AuthorizationVersion = toMicros(authorization)
		} else {
			for _, allowed := range permissions {
				if allowed {
					return s, errors.New("decision without authorization version cannot allow")
				}
			}
		}
		expected = fmt.Sprintf("%d:%d", e.ChannelID, e.AccountID)
		s.Kind = "access"
		s.Key = "access:" + expected
		s.UserID = e.AccountID
		s.ReadPrivate = *e.ReadPrivate
	case "preference.voice-channel-setting.state.v1":
		if e.ChannelID <= 0 || e.SchemaVersion != 1 || e.EventType != "UPSERT" ||
			e.MaxParticipants == nil || *e.MaxParticipants < 0 || (e.Bitrate != nil && *e.Bitrate <= 0) {
			return s, errors.New("invalid voice channel setting")
		}
		expected = fmt.Sprint(e.ChannelID)
		s.Key = "voice-setting:" + expected
		s.Kind = "voice-setting"
		s.MaxParticipants = *e.MaxParticipants
		s.Bitrate = e.Bitrate
	default:
		return s, errors.New("unexpected topic")
	}
	if key != expected {
		return s, errors.New("state key mismatch")
	}
	return s, nil
}

// toMicros matches Preference, which stores authorization versions in PostgreSQL at microsecond precision.
func toMicros(t time.Time) int64 { return t.UnixMicro() }
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
