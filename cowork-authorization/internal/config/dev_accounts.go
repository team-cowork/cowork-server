package config

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// The slot is the permanent provider subject. Rotating either credential keeps the account.
type DevAccounts struct {
	slots []devSlot
}

type devSlot struct {
	subject      string
	id, password [sha256.Size]byte
}

func ParseDevAccounts(raw string) (DevAccounts, error) {
	if raw == "" {
		return DevAccounts{}, nil
	}
	var values map[string]struct {
		ID       string `json:"id"`
		Password string `json:"password"`
	}
	if json.Unmarshal([]byte(raw), &values) != nil || len(values) != 4 {
		return DevAccounts{}, errors.New("DEV_SIGNIN_ACCOUNTS must contain dev-1 through dev-4")
	}
	var accounts DevAccounts
	seen := map[string]bool{}
	for i := 1; i <= 4; i++ {
		subject := fmt.Sprintf("dev-%d", i)
		value := values[subject]
		if strings.TrimSpace(value.ID) == "" || strings.TrimSpace(value.Password) == "" || seen[value.ID] {
			return DevAccounts{}, errors.New("DEV_SIGNIN_ACCOUNTS requires distinct IDs and nonempty passwords in all four slots")
		}
		seen[value.ID] = true
		accounts.slots = append(accounts.slots, devSlot{subject, sha256.Sum256([]byte(value.ID)), sha256.Sum256([]byte(value.Password))})
	}
	return accounts, nil
}

func (a DevAccounts) Authenticate(id, password string) (string, bool) {
	idHash, pwHash := sha256.Sum256([]byte(id)), sha256.Sum256([]byte(password))
	var subject string
	for _, slot := range a.slots {
		if subtle.ConstantTimeCompare(slot.id[:], idHash[:])&subtle.ConstantTimeCompare(slot.password[:], pwHash[:]) == 1 {
			subject = slot.subject
		}
	}
	return subject, subject != ""
}
