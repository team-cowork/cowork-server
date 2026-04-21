package fcm

import (
	"context"
	"log/slog"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

type Sender struct {
	client *messaging.Client
}

func NewSender(ctx context.Context, credentialsFile string) (*Sender, error) {
	app, err := firebase.NewApp(ctx, nil, option.WithCredentialsFile(credentialsFile))
	if err != nil {
		return nil, err
	}
	client, err := app.Messaging(ctx)
	if err != nil {
		return nil, err
	}
	return &Sender{client: client}, nil
}

const fcmBatchSize = 500

func (s *Sender) Send(ctx context.Context, tokens []string, title, body string, data map[string]string) ([]string, error) {
	if len(tokens) == 0 {
		return nil, nil
	}

	var invalid []string
	for i := 0; i < len(tokens); i += fcmBatchSize {
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
				if messaging.IsUnregistered(r.Error) {
					invalid = append(invalid, batch[j])
				} else {
					slog.Warn("fcm send failed for token", "err", r.Error, "token", batch[j])
				}
			}
		}
	}
	return invalid, nil
}
