package session

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/config"
	"github.com/cowork/cowork-voice/internal/dto"
)

type SessionService struct {
	repo       Repository
	membership MembershipChecker
	livekit    LiveKitRoom
	cfg        *config.AppConfig
}

func NewSessionService(repo Repository, membership MembershipChecker, livekit LiveKitRoom, cfg *config.AppConfig) *SessionService {
	return &SessionService{repo: repo, membership: membership, livekit: livekit, cfg: cfg}
}

func (s *SessionService) Join(ctx context.Context, channelID, userID int64) (*dto.JoinResponse, error) {
	teamID, err := s.membership.VerifyMembership(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if voiceSession == nil {
		voiceSession, err = s.repo.CreateSession(ctx, channelID, teamID)
		if err != nil {
			return nil, err
		}
	}

	if err := s.livekit.CreateRoomIfNotExists(ctx, voiceSession.RoomName); err != nil {
		return nil, err
	}

	if err := s.repo.InsertParticipant(ctx, &VoiceParticipant{
		SessionID: voiceSession.SessionID,
		UserID:    userID,
		ChannelID: channelID,
		JoinedAt:  time.Now().UTC(),
	}); err != nil {
		return nil, err
	}

	token, err := s.livekit.GenerateToken(userID, voiceSession.RoomName)
	if err != nil {
		return nil, err
	}

	return &dto.JoinResponse{
		Token:      token,
		LiveKitURL: s.cfg.LiveKitWsURL,
		SessionID:  voiceSession.SessionID,
		RoomName:   voiceSession.RoomName,
	}, nil
}

func (s *SessionService) Leave(ctx context.Context, channelID, userID int64) error {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
		return err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return err
	}
	if voiceSession == nil {
		return apperr.NotFound("active session not found")
	}

	identity := strconv.FormatInt(userID, 10)
	if err := s.livekit.RemoveParticipant(ctx, voiceSession.RoomName, identity); err != nil {
		return err
	}

	participants, err := s.livekit.ListParticipants(ctx, voiceSession.RoomName)
	if err != nil {
		slog.Warn("failed to list participants after leave", "err", err)
	} else if len(participants) == 0 {
		if err := s.livekit.DeleteRoom(ctx, voiceSession.RoomName); err != nil {
			slog.Warn("failed to delete empty room", "err", err, "room", voiceSession.RoomName)
		}
	}

	return nil
}

func (s *SessionService) GetParticipants(ctx context.Context, channelID, userID int64) (*dto.ParticipantsResponse, error) {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
		return nil, err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if voiceSession == nil {
		return &dto.ParticipantsResponse{
			ChannelID:    channelID,
			Participants: []dto.ParticipantInfo{},
		}, nil
	}

	roomName := voiceSession.RoomName
	lkParticipants, err := s.livekit.ListParticipants(ctx, roomName)
	if err != nil {
		return nil, err
	}

	participants := make([]dto.ParticipantInfo, 0, len(lkParticipants))
	for _, p := range lkParticipants {
		uid, err := strconv.ParseInt(p.Identity, 10, 64)
		if err != nil {
			continue
		}
		joinedAt := time.Unix(p.JoinedAt, 0).UTC().Format(time.RFC3339)
		participants = append(participants, dto.ParticipantInfo{
			UserID:   uid,
			JoinedAt: joinedAt,
		})
	}

	return &dto.ParticipantsResponse{
		ChannelID:    channelID,
		RoomName:     roomName,
		Participants: participants,
	}, nil
}

func (s *SessionService) GetSession(ctx context.Context, sessionID string, userID int64) (*dto.SessionResponse, error) {
	sess, err := s.repo.GetSession(ctx, sessionID)
	if err != nil {
		return nil, err
	}
	if sess == nil {
		return nil, apperr.NotFound("session not found")
	}

	if _, err := s.membership.VerifyMembership(ctx, sess.ChannelID, userID); err != nil {
		return nil, err
	}

	var endedAt *string
	if sess.EndedAt != nil {
		v := sess.EndedAt.UTC().Format(time.RFC3339)
		endedAt = &v
	}

	return &dto.SessionResponse{
		SessionID: sess.SessionID,
		ChannelID: sess.ChannelID,
		TeamID:    sess.TeamID,
		Status:    sess.Status,
		StartedAt: sess.StartedAt.UTC().Format(time.RFC3339),
		EndedAt:   endedAt,
	}, nil
}
