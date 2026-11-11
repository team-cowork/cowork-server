package redisinfra

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/cowork/authorization/internal/service"
	"github.com/redis/go-redis/v9"
)

type LoginAttemptStore struct{ client *redis.Client }

func NewLoginAttemptStore(address string) *LoginAttemptStore {
	return &LoginAttemptStore{client: redis.NewClient(&redis.Options{Addr: address, DialTimeout: 3 * time.Second,
		ReadTimeout: 3 * time.Second, WriteTimeout: 3 * time.Second, MaxRetries: -1, ContextTimeoutEnabled: true})}
}

func (s *LoginAttemptStore) Close() error { return s.client.Close() }

func (s *LoginAttemptStore) Save(ctx context.Context, key string, attempt service.LoginAttempt, ttl time.Duration) error {
	body, err := json.Marshal(attempt)
	if err != nil {
		return err
	}
	ok, err := s.client.SetNX(ctx, "cowork:authorization:oauth:"+key, body, ttl).Result()
	if err != nil {
		return err
	}
	if !ok {
		return errors.New("login attempt already exists")
	}
	return nil
}

func (s *LoginAttemptStore) Take(ctx context.Context, key string) (service.LoginAttempt, error) {
	var attempt service.LoginAttempt
	// GETDEL consumes the attempt atomically across authorization replicas; retries are disabled.
	body, err := s.client.GetDel(ctx, "cowork:authorization:oauth:"+key).Bytes()
	if errors.Is(err, redis.Nil) {
		return attempt, service.ErrLoginAttemptNotFound
	}
	if err != nil {
		return attempt, err
	}
	err = json.Unmarshal(body, &attempt)
	return attempt, err
}
