package fcm

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"strconv"
	"strings"

	firebase "firebase.google.com/go/v4"
	"firebase.google.com/go/v4/messaging"
	"google.golang.org/api/option"
)

// Outcome classifies one installation's FCM send result so callers can decide whether
// to delete the installation, retry it durably, or treat it as delivered.
type Outcome string

const (
	// OutcomeSuccess: FCM accepted and delivered the message to the provider for this FID.
	OutcomeSuccess Outcome = "SUCCESS"
	// OutcomeInvalid: the FID itself is unusable (unregistered, sender mismatch) and must be removed.
	OutcomeInvalid Outcome = "INVALID"
	// OutcomeRetryable: a transient provider/infra error; the same FID can be retried later.
	OutcomeRetryable Outcome = "RETRYABLE"
	// OutcomeUnclassified: an error the SDK does not map to a known permanent/transient category.
	// Treated as retryable but with a much lower attempt ceiling, since it may in fact be permanent.
	OutcomeUnclassified Outcome = "UNCLASSIFIED"
)

// DataKeyAccountID is the data payload key carrying the account that owns the target
// installation generation. FCM cannot recall a message already handed to it, so an
// installation transferred after the pre-send ownership check may still receive the
// previous account's message; the client must drop a message whose accountId is not
// the currently signed-in account.
const DataKeyAccountID = "accountId"

// DataKeyEventID is the data payload key carrying the trigger event's stable eventId.
// Delivery is at-least-once (a send that succeeds before its ledger write can be
// repeated), so the client must drop a message whose eventId it already showed.
const DataKeyEventID = "eventId"

// Message is one Firebase installation's send request. Data is per installation so
// each message can carry the owning account (see DataKeyAccountID).
type Message struct {
	FID  string
	Data map[string]string
}

// WithAccountID returns a copy of data with DataKeyAccountID set to accountID.
func WithAccountID(data map[string]string, accountID int64) map[string]string {
	out := make(map[string]string, len(data)+1)
	for k, v := range data {
		out[k] = v
	}
	out[DataKeyAccountID] = strconv.FormatInt(accountID, 10)
	return out
}

// Result is one installation's classified send outcome.
type Result struct {
	FID     string
	Outcome Outcome
}

type Sender struct {
	client         messagingClient
	isUnregistered func(error) bool
}

type messagingClient interface {
	SendEach(context.Context, []*messaging.Message) (*messaging.BatchResponse, error)
}

func (s *Sender) checkUnregistered(err error) bool {
	if s.isUnregistered != nil {
		return s.isUnregistered(err)
	}
	return messaging.IsUnregistered(err)
}

// classify maps an FCM SDK error to an Outcome. This mapping mirrors the official
// Admin SDK error taxonomy (firebase.google.com/go/v4/messaging's Is* predicates) and
// is verified by code review against that contract rather than by unit test: the
// SDK's own error classification is not this repository's business logic to freeze.
func (s *Sender) classify(err error) Outcome {
	switch {
	case s.checkUnregistered(err), messaging.IsSenderIDMismatch(err):
		return OutcomeInvalid
	case messaging.IsInternal(err), messaging.IsUnavailable(err), messaging.IsQuotaExceeded(err):
		return OutcomeRetryable
	default:
		// Deliberately excludes messaging.IsInvalidArgument: that code also covers
		// message-level problems (oversized payload, a malformed field) that are not
		// specific to one installation. Since every message in a batch shares the same
		// notification content, classifying it as OutcomeInvalid here would delete every
		// recipient's installation in the batch for what is actually a message
		// construction bug. Routing it to OutcomeUnclassified instead only quarantines it
		// (after MaxAttemptsUnclassified retries) without touching any installation.
		return OutcomeUnclassified
	}
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

// Send delivers every message to its FID, batched at fcmBatchSize, and returns a
// classified result for each message attempted, in input order. SendEach returns one
// response per message in request order. It only returns a non-nil error for context
// cancellation — a per-installation or per-batch FCM failure is reported through the
// returned Result.Outcome instead, so a caller can persist retryable failures durably
// rather than treat the whole call as failed.
func (s *Sender) Send(ctx context.Context, messages []Message, title, body string) ([]Result, error) {
	if len(messages) == 0 {
		return nil, nil
	}

	results := make([]Result, 0, len(messages))
	for i := 0; i < len(messages); i += fcmBatchSize {
		if err := ctx.Err(); err != nil {
			return results, err
		}
		end := i + fcmBatchSize
		if end > len(messages) {
			end = len(messages)
		}
		batch := messages[i:end]

		msgs := make([]*messaging.Message, len(batch))
		for j, m := range batch {
			msgs[j] = &messaging.Message{
				Notification: &messaging.Notification{Title: title, Body: body},
				Data:         m.Data,
				Fid:          m.FID,
			}
		}
		resp, err := s.client.SendEach(ctx, msgs)
		if err != nil {
			// The whole batch failed before FCM assigned per-message responses; classify the
			// batch-level error once and apply it to every installation in the batch so none
			// of them are silently dropped from the durable retry ledger.
			outcome := s.classify(err)
			slog.Warn("fcm multicast call failed", "err", err, "batch_size", len(batch), "outcome", outcome)
			for _, m := range batch {
				results = append(results, Result{FID: m.FID, Outcome: outcome})
			}
			continue
		}
		for j, r := range resp.Responses {
			if r.Success {
				results = append(results, Result{FID: batch[j].FID, Outcome: OutcomeSuccess})
				continue
			}
			outcome := s.classify(r.Error)
			if outcome != OutcomeInvalid {
				slog.Warn("fcm send failed", "err", r.Error, "batch_index", j, "outcome", outcome)
			}
			results = append(results, Result{FID: batch[j].FID, Outcome: outcome})
		}
	}
	return results, nil
}
