package room

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

type RoomService struct {
	repo         Repository
	permissions  ConnectPermissionChecker
	livekit      LiveKitRoom
	livekitWsURL string
}

func NewRoomService(repo Repository, permissions ConnectPermissionChecker, livekit LiveKitRoom, livekitWsURL string) *RoomService {
	return &RoomService{repo: repo, permissions: permissions, livekit: livekit, livekitWsURL: livekitWsURL}
}

func (s *RoomService) Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error) {
	access, err := s.permissions.RequireConnect(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if voiceSession != nil && access.MaxParticipants > 0 {
		if err := s.requireSeat(ctx, voiceSession.RoomName, userID, access.MaxParticipants); err != nil {
			return nil, err
		}
	}
	sessionCreatedByUs := false
	if voiceSession == nil {
		created, isNew, cerr := s.repo.CreateSession(ctx, channelID, access.TeamID)
		if cerr != nil {
			return nil, cerr
		}
		if created == nil {
			return nil, apperr.Internal("failed to create or find active session")
		}
		voiceSession = created
		sessionCreatedByUs = isNew
	}

	if err := s.livekit.CreateRoomIfNotExists(ctx, voiceSession.RoomName, uint32(access.MaxParticipants)); err != nil {
		if sessionCreatedByUs {
			if _, err := s.repo.EndSession(ctx, voiceSession.SessionID, time.Now().UTC()); err != nil {
				slog.Error("failed to end session", "err", err, "session_id", voiceSession.SessionID)
			}
		}
		return nil, err
	}

	token, err := s.livekit.GenerateToken(userID, voiceSession.RoomName, access.Speak, access.ScreenShare)
	if err != nil {
		if sessionCreatedByUs {
			if _, endErr := s.repo.EndSession(context.WithoutCancel(ctx), voiceSession.SessionID, time.Now().UTC()); endErr != nil {
				slog.Error("failed to end session after token issuance failure", "err", endErr, "session_id", voiceSession.SessionID)
			}
		}
		return nil, err
	}

	return &JoinResponse{
		Token:      token,
		LiveKitURL: s.livekitWsURL,
		SessionID:  voiceSession.SessionID,
		RoomName:   voiceSession.RoomName,
		Bitrate:    access.Bitrate,
	}, nil
}

// requireSeat는 토큰 발급 전에 정원을 확인한다. 이미 방에 있는 사용자의 재입장은 허용한다.
// 동시 입장은 이 검사를 함께 통과할 수 있으므로 최종 상한은 LiveKit 방의 MaxParticipants가 맡는다.
func (s *RoomService) requireSeat(ctx context.Context, roomName string, userID int64, maxParticipants int) error {
	participants, err := s.livekit.ListParticipants(ctx, roomName)
	if err != nil {
		return err
	}
	identity := strconv.FormatInt(userID, 10)
	for _, p := range participants {
		if p.Identity == identity {
			return nil
		}
	}
	if len(participants) >= maxParticipants {
		return apperr.Conflict("음성 채널 정원이 가득 찼습니다.")
	}
	return nil
}

func (s *RoomService) Leave(ctx context.Context, channelID, userID int64) error {
	if _, err := s.permissions.RequireConnect(ctx, channelID, userID); err != nil {
		return err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return err
	}
	if voiceSession == nil {
		return apperr.NotFound("active room not found")
	}

	identity := strconv.FormatInt(userID, 10)
	if err := s.livekit.RemoveParticipant(ctx, voiceSession.RoomName, identity); err != nil {
		return err
	}

	now := time.Now().UTC()
	joinedAt, err := s.repo.GetParticipantJoinedAt(ctx, voiceSession.SessionID, userID, "")
	if err != nil {
		return err
	}

	var durationSeconds int64
	if joinedAt != nil {
		// clock skew 등으로 now < joinedAt이면 음수가 될 수 있어 0으로 보정한다.
		if diff := now.Sub(*joinedAt).Seconds(); diff > 0 {
			durationSeconds = int64(diff)
		}
	}
	_, err = s.repo.MarkParticipantLeftAndEnqueue(ctx, voiceSession.SessionID, userID, "", now, &kafkadomain.UserLeftEvent{
		EventType:       kafkadomain.EventUserLeft,
		SessionID:       voiceSession.SessionID,
		ChannelID:       channelID,
		TeamID:          voiceSession.TeamID,
		UserID:          userID,
		DurationSeconds: durationSeconds,
		Timestamp:       now.Format(time.RFC3339),
	})
	return err
}

func (s *RoomService) GetParticipants(ctx context.Context, channelID, userID int64) (*ParticipantsResponse, error) {
	if _, err := s.permissions.RequireConnect(ctx, channelID, userID); err != nil {
		return nil, err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if voiceSession == nil {
		return &ParticipantsResponse{
			ChannelID:    channelID,
			Participants: []ParticipantResponse{},
		}, nil
	}

	roomName := voiceSession.RoomName
	lkParticipants, err := s.livekit.ListParticipants(ctx, roomName)
	if err != nil {
		return nil, err
	}

	participants := make([]ParticipantResponse, 0, len(lkParticipants))
	for _, p := range lkParticipants {
		uid, err := strconv.ParseInt(p.Identity, 10, 64)
		if err != nil {
			slog.Warn("skipping participant with non-numeric identity", "identity", p.Identity)
			continue
		}
		participants = append(participants, ParticipantResponse{
			UserID:   uid,
			JoinedAt: time.Unix(p.JoinedAt, 0).UTC().Format(time.RFC3339),
		})
	}

	return &ParticipantsResponse{
		ChannelID:    channelID,
		RoomName:     roomName,
		Participants: participants,
	}, nil
}

func (s *RoomService) GetSession(ctx context.Context, sessionID string, userID int64) (*SessionResponse, error) {
	sess, err := s.repo.GetSession(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	if sess == nil {
		return nil, apperr.NotFound("session not found")
	}

	if _, err := s.permissions.RequireConnect(ctx, sess.ChannelID, userID); err != nil {
		return nil, err
	}

	var endedAt *string
	if sess.EndedAt != nil {
		v := sess.EndedAt.UTC().Format(time.RFC3339)
		endedAt = &v
	}

	return &SessionResponse{
		SessionID: sess.SessionID,
		ChannelID: sess.ChannelID,
		TeamID:    sess.TeamID,
		Status:    sess.Status,
		StartedAt: sess.StartedAt.UTC().Format(time.RFC3339),
		EndedAt:   endedAt,
	}, nil
}
