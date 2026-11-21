package service

import (
	"context"
	"errors"
	"fmt"
	"time"

	"gorm.io/gorm"

	"github.com/cowork/authorization/internal/domain"
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
) (*domain.SessionIdentity, error) {
	operationID, err := c.store.Submit(ctx, command, c.commandTopic)
	if err != nil {
		return nil, fmt.Errorf("%w: failed to submit user identity command: %v", ErrAuthenticationUnavailable, err)
	}

	waitCtx, cancel := context.WithTimeout(ctx, c.waitTimeout)
	defer cancel()
	ticker := time.NewTicker(c.pollInterval)
	defer ticker.Stop()

	for {
		operation, err := c.store.Find(waitCtx, operationID)
		if err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
			return nil, fmt.Errorf("%w: failed to read user identity operation: %v", ErrAuthenticationUnavailable, err)
		}
		if operation != nil {
			switch operation.Status {
			case domain.UserIdentitySucceeded:
				if operation.ResultUserID == nil || *operation.ResultUserID <= 0 || operation.ResultEmail == nil || operation.ResultGsmRole == nil {
					return nil, fmt.Errorf("user identity result did not prove the requested owner commit")
				}
				return &domain.SessionIdentity{UserID: *operation.ResultUserID, Email: *operation.ResultEmail, GsmRole: *operation.ResultGsmRole}, nil
			case domain.UserIdentityFailed:
				code := "USER_IDENTITY_FAILED"
				if operation.ErrorCode != nil {
					code = *operation.ErrorCode
				}
				return nil, fmt.Errorf("user identity command failed: %s", code)
			case domain.UserIdentityPending:
			default:
				return nil, fmt.Errorf("%w: invalid user identity operation status", ErrAuthenticationUnavailable)
			}
		}

		select {
		case <-waitCtx.Done():
			return nil, fmt.Errorf(
				"%w: user identity command did not complete before deadline: %v",
				ErrAuthenticationUnavailable,
				waitCtx.Err(),
			)
		case <-ticker.C:
		}
	}
}
