package fcm

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

type Sender struct {
	client         messagingClient
	isUnregistered func(error) bool
}

type messagingClient interface {
	SendEachForMulticast(context.Context, *messaging.MulticastMessage) (*messaging.BatchResponse, error)
}

func (s *Sender) checkUnregistered(err error) bool {
	if s.isUnregistered != nil {
		return s.isUnregistered(err)
	}
	return messaging.IsUnregistered(err)
}

func NewSender(ctx context.Context, credentialsJSON string) (*Sender, error) {
	var account struct {
		Type        string `json:"type"`
		ProjectID   string `json:"project_id"`
		ClientEmail string `json:"client_email"`
		PrivateKey  string `json:"private_key"`
	}
	if err := json.Unmarshal([]byte(credentialsJSON), &account); err != nil {
		return nil, errors.New("fcm.credentials-json must be a JSON object")
	}
	if account.Type != "service_account" || strings.TrimSpace(account.ProjectID) == "" ||
		strings.TrimSpace(account.ClientEmail) == "" || strings.TrimSpace(account.PrivateKey) == "" {
		return nil, errors.New("fcm.credentials-json requires service_account type, project_id, client_email and private_key")
	}
	app, err := firebase.NewApp(ctx, &firebase.Config{ProjectID: account.ProjectID},
		option.WithAuthCredentialsJSON(option.ServiceAccount, []byte(credentialsJSON)))
	if err != nil {
		return nil, errors.New("failed to initialize Firebase with fcm.credentials-json")
	}
	client, err := app.Messaging(ctx)
	if err != nil {
		// Credential parsing errors can contain input values; never log the original error.
		return nil, errors.New("failed to initialize Firebase messaging; check fcm.credentials-json service account credentials")
	}
	return &Sender{
		client:         client,
		isUnregistered: messaging.IsUnregistered,
	}, nil
}

const fcmBatchSize = 500

func (s *Sender) Send(ctx context.Context, tokens []string, title, body string, data map[string]string) ([]string, error) {
	if len(tokens) == 0 {
		return nil, nil
	}

	var invalid []string
	for i := 0; i < len(tokens); i += fcmBatchSize {
		if err := ctx.Err(); err != nil {
			return invalid, err
		}
		end := i + fcmBatchSize
		if end > len(tokens) {
			end = len(tokens)
		}
		batch := tokens[i:end]

		msg := &messaging.MulticastMessage{
			Notification: &messaging.Notification{Title: title, Body: body},
			Data:         data,
			Tokens:       batch,
		}
		resp, err := s.client.SendEachForMulticast(ctx, msg)
		if err != nil {
			return invalid, err
		}
		for j, r := range resp.Responses {
			if !r.Success {
				if s.checkUnregistered(r.Error) {
					invalid = append(invalid, batch[j])
				} else {
					slog.Warn("fcm send failed", "err", r.Error, "batch_index", j)
				}
			}
		}
	}
	return invalid, nil
}
