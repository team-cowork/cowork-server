package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"gorm.io/gorm"
)

type UserIdentityOperationStore interface {
	Submit(context.Context, domain.UserIdentityCommand, string) (string, error)
	Find(context.Context, string) (*domain.UserIdentityOperation, error)
}

type UserIdentityCoordinator struct {
	store        UserIdentityOperationStore
	commandTopic string
	waitTimeout  time.Duration
	pollInterval time.Duration
}

func NewUserIdentityCoordinator(
	store UserIdentityOperationStore,
	commandTopic string,
	waitTimeout time.Duration,
) *UserIdentityCoordinator {
	return &UserIdentityCoordinator{
		store:        store,
		commandTopic: commandTopic,
		waitTimeout:  waitTimeout,
		pollInterval: 50 * time.Millisecond,
	}
}

// EnsureUser returns only after cowork-user's result proves its owner
// transaction committed. Timeout and FAILED never permit token/session issue.
func (c *UserIdentityCoordinator) EnsureUser(
	ctx context.Context,
	command domain.UserIdentityCommand,
) (int64, error) {
	operationID, err := c.store.Submit(ctx, command, c.commandTopic)
	if err != nil {
		return 0, fmt.Errorf("%w: failed to submit user identity command: %v", ErrAuthenticationUnavailable, err)
	}

	waitCtx, cancel := context.WithTimeout(ctx, c.waitTimeout)
	defer cancel()
	ticker := time.NewTicker(c.pollInterval)
	defer ticker.Stop()

	for {
		operation, err := c.store.Find(waitCtx, operationID)
		if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return 0, fmt.Errorf("%w: failed to read user identity operation: %v", ErrAuthenticationUnavailable, err)
		}
		if operation != nil {
			switch operation.Status {
			case domain.UserIdentitySucceeded:
				if operation.ResultUserID == nil || *operation.ResultUserID != command.UserID {
					return 0, fmt.Errorf("user identity result did not prove the requested owner commit")
				}
				return *operation.ResultUserID, nil
			case domain.UserIdentityFailed:
				code := "USER_IDENTITY_FAILED"
				if operation.ErrorCode != nil {
					code = *operation.ErrorCode
				}
				return 0, fmt.Errorf("user identity command failed: %s", code)
			case domain.UserIdentityPending:
			default:
				return 0, fmt.Errorf("%w: invalid user identity operation status", ErrAuthenticationUnavailable)
			}
		}

		select {
		case <-waitCtx.Done():
			return 0, fmt.Errorf(
				"%w: user identity command did not complete before deadline: %v",
				ErrAuthenticationUnavailable,
				waitCtx.Err(),
			)
		case <-ticker.C:
		}
	}
}
