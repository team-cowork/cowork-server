package room

import (
	"context"
	"log/slog"
	"strconv"
	"time"

	"github.com/cowork/cowork-voice/internal/apperr"
)

type RoomService struct {
	repo         Repository
	membership   MembershipChecker
	livekit      LiveKitRoom
	livekitWsURL string
}

func NewRoomService(repo Repository, membership MembershipChecker, livekit LiveKitRoom, livekitWsURL string) *RoomService {
	return &RoomService{repo: repo, membership: membership, livekit: livekit, livekitWsURL: livekitWsURL}
}

func (s *RoomService) Join(ctx context.Context, channelID, userID int64) (*JoinResponse, error) {
	teamID, err := s.membership.VerifyMembership(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}

	voiceSession, err := s.repo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	sessionCreatedByUs := false
	if voiceSession == nil {
		voiceSession, err = s.repo.CreateSession(ctx, channelID, teamID)
		if err != nil {
			return nil, err
		}
		sessionCreatedByUs = true
	}

	if err := s.livekit.CreateRoomIfNotExists(ctx, voiceSession.RoomName); err != nil {
		if sessionCreatedByUs {
			if err := s.repo.EndSession(ctx, voiceSession.SessionID, time.Now().UTC()); err != nil {
				slog.Error("failed to end session", "err", err, "session_id", voiceSession.SessionID)
			}
		}
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

	return &JoinResponse{
		Token:      token,
		LiveKitURL: s.livekitWsURL,
		SessionID:  voiceSession.SessionID,
		RoomName:   voiceSession.RoomName,
	}, nil
}

func (s *RoomService) Leave(ctx context.Context, channelID, userID int64) error {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
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

	if _, err := s.repo.MarkParticipantLeft(ctx, voiceSession.SessionID, userID, time.Now().UTC()); err != nil {
		slog.Warn("failed to mark participant left", "err", err, "session_id", voiceSession.SessionID)
	}

	return nil
}

func (s *RoomService) GetParticipants(ctx context.Context, channelID, userID int64) (*ParticipantsResponse, error) {
	if _, err := s.membership.VerifyMembership(ctx, channelID, userID); err != nil {
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

	if _, err := s.membership.VerifyMembership(ctx, sess.ChannelID, userID); err != nil {
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
