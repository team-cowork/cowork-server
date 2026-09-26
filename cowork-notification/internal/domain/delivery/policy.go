package delivery

import (
	"math/rand"
	"time"
)

const (
	// MaxAttempts is the retry ceiling for a classified-retryable failure (transient
	// provider/infra errors such as internal errors, unavailability, or quota limits).
	MaxAttempts = 6
	// MaxAttemptsUnclassified is the (lower) ceiling for a failure the FCM SDK could not
	// map to a known category — it may in fact be permanent, so it is quarantined sooner.
	MaxAttemptsUnclassified = 2

	backoffBase = 30 * time.Second
	backoffMax  = 30 * time.Minute
	// jitterFraction randomizes each backoff by up to +/-20% so many tokens queued at
	// the same time do not all retry in the same instant.
	jitterFraction = 0.2
)

// maxAttemptsFor returns the retry ceiling for errClass.
func maxAttemptsFor(errClass ErrorClass) int {
	if errClass == ErrorClassUnclassified {
		return MaxAttemptsUnclassified
	}
	return MaxAttempts
}

// NextAfterFailure decides a row's next state after a non-success FCM attempt.
// attemptsSoFar is the row's AttemptCount before this failed attempt (0 on the first
// send). It returns the new status, the row's updated attempt count, and — when the
// row is still retryable — the next attempt time.
func NextAfterFailure(errClass ErrorClass, attemptsSoFar int, now time.Time) (status Status, newAttemptCount int, nextAttemptAt *time.Time) {
	newAttemptCount = attemptsSoFar + 1
	if newAttemptCount >= maxAttemptsFor(errClass) {
		return StatusQuarantined, newAttemptCount, nil
	}
	at := now.Add(backoffWithJitter(newAttemptCount))
	return StatusPendingRetry, newAttemptCount, &at
}

func backoffWithJitter(attempt int) time.Duration {
	d := backoffBase
	for i := 1; i < attempt; i++ {
		if d >= backoffMax {
			d = backoffMax
			break
		}
		d *= 2
	}
	if d > backoffMax {
		d = backoffMax
	}
	jitter := time.Duration(float64(d) * jitterFraction * (rand.Float64()*2 - 1)) //nolint:gosec // timing jitter only, not security-sensitive
	return d + jitter
}
