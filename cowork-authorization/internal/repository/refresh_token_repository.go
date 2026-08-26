package repository

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

const expiredSessionBatchSize = 500

type RefreshTokenRepository struct {
	db *gorm.DB
}

func NewRefreshTokenRepository(db *gorm.DB) *RefreshTokenRepository {
	return &RefreshTokenRepository{db: db}
}

// CreateSession commits the refresh token, derived durable presence state, and
// first-session online event in one transaction.
func (r *RefreshTokenRepository) CreateSession(
	ctx context.Context,
	token *domain.RefreshToken,
	occurredAt time.Time,
	topic string,
) error {
	occurredAt = occurredAt.UTC().Truncate(time.Microsecond)
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		state, err := lockPresenceState(tx, token.UserID, occurredAt)
		if err != nil {
			return err
		}
		if err := tx.Create(token).Error; err != nil {
			return err
		}
		activeSessions, err := countActiveSessions(tx, token.UserID, occurredAt)
		if err != nil {
			return err
		}
		return persistPresence(tx, state, activeSessions, occurredAt, topic)
	})
}

// RotateSession locks and validates the old token before conditionally replacing
// it. Concurrent refresh/logout/expiry cleanup therefore has exactly one winner.
func (r *RefreshTokenRepository) RotateSession(
	ctx context.Context,
	oldHash string,
	newHash string,
	newExpiresAt time.Time,
	now time.Time,
) (*domain.RefreshToken, error) {
	now = now.UTC().Truncate(time.Microsecond)
	newExpiresAt = newExpiresAt.UTC().Truncate(time.Microsecond)
	var source domain.RefreshToken
	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		var candidate domain.RefreshToken
		if err := tx.Select("user_id").Where("token_hash = ?", oldHash).
			First(&candidate).Error; err != nil {
			return err
		}
		if _, err := lockPresenceState(tx, candidate.UserID, now); err != nil {
			return err
		}
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("token_hash = ?", oldHash).
			First(&source).Error; err != nil {
			return err
		}
		if !domain.RefreshTokenUnexpired(source.ExpiresAt, now) {
			return domain.ErrRefreshTokenExpired
		}

		deleted := tx.Where("id = ? AND token_hash = ?", source.ID, oldHash).
			Delete(&domain.RefreshToken{})
		if deleted.Error != nil {
			return deleted.Error
		}
		if deleted.RowsAffected != 1 {
			return gorm.ErrRecordNotFound
		}

		replacement := &domain.RefreshToken{
			UserID:       source.UserID,
			TokenHash:    newHash,
			DeviceInfo:   source.DeviceInfo,
			Email:        source.Email,
			GsmRole:      source.GsmRole,
			PlatformRole: source.PlatformRole,
			ExpiresAt:    newExpiresAt,
		}
		return tx.Create(replacement).Error
	})
	if err != nil {
		return nil, err
	}
	return &source, nil
}

// RevokeSession removes one token and only emits offline when no unexpired
// session remains. Ownership validation is inside the same locked transaction.
func (r *RefreshTokenRepository) RevokeSession(
	ctx context.Context,
	hash string,
	userID int64,
	occurredAt time.Time,
	topic string,
) error {
	occurredAt = occurredAt.UTC().Truncate(time.Microsecond)
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		state, err := lockPresenceState(tx, userID, occurredAt)
		if err != nil {
			return err
		}
		var token domain.RefreshToken
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("token_hash = ?", hash).
			First(&token).Error; err != nil {
			return err
		}
		if token.UserID != userID {
			return domain.ErrRefreshTokenOwnerMismatch
		}

		deleted := tx.Where("id = ? AND token_hash = ?", token.ID, hash).
			Delete(&domain.RefreshToken{})
		if deleted.Error != nil {
			return deleted.Error
		}
		if deleted.RowsAffected != 1 {
			return gorm.ErrRecordNotFound
		}
		activeSessions, err := countActiveSessions(tx, userID, occurredAt)
		if err != nil {
			return err
		}
		return persistPresence(tx, state, activeSessions, occurredAt, topic)
	})
}

// DeleteExpiredSessions removes expired refresh tokens in bounded transactions.
// Each affected user's durable state and final-session offline event are updated
// atomically with the deletion. Offline rows are deliberately retained.
func (r *RefreshTokenRepository) DeleteExpiredSessions(
	ctx context.Context,
	cutoff time.Time,
	topic string,
) error {
	cutoff = cutoff.UTC().Truncate(time.Microsecond)
	for {
		found, err := r.deleteExpiredSessionBatch(ctx, cutoff, topic)
		if err != nil {
			return err
		}
		if !found {
			return nil
		}
	}
}

func (r *RefreshTokenRepository) deleteExpiredSessionBatch(
	ctx context.Context,
	cutoff time.Time,
	topic string,
) (bool, error) {
	var candidates []domain.RefreshToken
	if err := r.db.WithContext(ctx).
		Select("id", "user_id").
		Where("expires_at <= ?", cutoff).
		Order("id ASC").
		Limit(expiredSessionBatchSize).
		Find(&candidates).Error; err != nil {
		return false, err
	}
	if len(candidates) == 0 {
		return false, nil
	}
	userIDs := uniqueSortedUserIDs(candidates)

	err := r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		states := make(map[int64]*domain.UserPresenceState, len(userIDs))
		for _, userID := range userIDs {
			state, err := lockPresenceState(tx, userID, cutoff)
			if err != nil {
				return err
			}
			states[userID] = state
		}

		var tokens []domain.RefreshToken
		if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
			Where("user_id IN ? AND expires_at <= ?", userIDs, cutoff).
			Order("id ASC").
			Limit(expiredSessionBatchSize).
			Find(&tokens).Error; err != nil {
			return err
		}
		if len(tokens) == 0 {
			return nil
		}

		ids := make([]int64, len(tokens))
		for index, token := range tokens {
			ids[index] = token.ID
		}
		deleted := tx.Where("id IN ? AND expires_at <= ?", ids, cutoff).
			Delete(&domain.RefreshToken{})
		if deleted.Error != nil {
			return deleted.Error
		}
		if deleted.RowsAffected != int64(len(tokens)) {
			return fmt.Errorf(
				"expired refresh token delete affected %d rows, want %d",
				deleted.RowsAffected,
				len(tokens),
			)
		}

		for _, userID := range uniqueSortedUserIDs(tokens) {
			activeSessions, err := countActiveSessions(tx, userID, cutoff)
			if err != nil {
				return err
			}
			if err := persistPresence(tx, states[userID], activeSessions, cutoff, topic); err != nil {
				return err
			}
		}
		return nil
	})
	return true, err
}

func lockPresenceState(
	tx *gorm.DB,
	userID int64,
	occurredAt time.Time,
) (*domain.UserPresenceState, error) {
	inserted := tx.Exec(
		`INSERT IGNORE INTO tb_user_presence_states
			(user_id, status, active_session_count, occurred_at)
		 VALUES (?, ?, 0, ?)`,
		userID,
		domain.PresenceOffline,
		occurredAt,
	)
	if inserted.Error != nil {
		return nil, inserted.Error
	}

	var state domain.UserPresenceState
	if err := tx.Clauses(clause.Locking{Strength: "UPDATE"}).
		Where("user_id = ?", userID).
		First(&state).Error; err != nil {
		return nil, err
	}
	if inserted.RowsAffected == 1 {
		// The inserted offline value is only a transaction-local placeholder.
		// Treat it as no prior state so the first session or first cleanup emits
		// an authoritative event at the actual transition timestamp.
		state.Status = ""
		state.OccurredAt = time.Time{}
	}
	return &state, nil
}

func countActiveSessions(tx *gorm.DB, userID int64, at time.Time) (int64, error) {
	var count int64
	err := tx.Model(&domain.RefreshToken{}).
		Where("user_id = ? AND expires_at > ?", userID, at).
		Count(&count).Error
	return count, err
}

func persistPresence(
	tx *gorm.DB,
	state *domain.UserPresenceState,
	activeSessions int64,
	occurredAt time.Time,
	topic string,
) error {
	effectiveOccurredAt := domain.PresenceTransitionOccurredAt(
		state.Status,
		state.OccurredAt.UTC().Truncate(time.Microsecond),
		activeSessions,
		occurredAt,
	)
	decision := domain.DecidePresenceTransition(
		state.Status,
		state.OccurredAt.UTC().Truncate(time.Microsecond),
		activeSessions,
		effectiveOccurredAt,
	)
	updates := map[string]any{"active_session_count": activeSessions}
	if decision.Changed {
		updates["status"] = decision.Status
		updates["occurred_at"] = effectiveOccurredAt
	}
	updated := tx.Model(&domain.UserPresenceState{}).
		Where("user_id = ?", state.UserID).
		Updates(updates)
	if updated.Error != nil {
		return updated.Error
	}
	if !decision.Changed {
		return nil
	}

	payload, err := json.Marshal(domain.UserPresenceEvent{
		EventType:  "STATUS_CHANGED",
		UserID:     state.UserID,
		Status:     decision.Status,
		OccurredAt: effectiveOccurredAt,
	})
	if err != nil {
		return err
	}
	return tx.Exec(
		`INSERT INTO tb_kafka_outbox (topic, event_key, payload) VALUES (?, ?, ?)`,
		topic,
		fmt.Sprintf("%d", state.UserID),
		payload,
	).Error
}

func uniqueSortedUserIDs(tokens []domain.RefreshToken) []int64 {
	set := make(map[int64]struct{}, len(tokens))
	for _, token := range tokens {
		set[token.UserID] = struct{}{}
	}
	userIDs := make([]int64, 0, len(set))
	for userID := range set {
		userIDs = append(userIDs, userID)
	}
	sort.Slice(userIDs, func(left, right int) bool { return userIDs[left] < userIDs[right] })
	return userIDs
}
