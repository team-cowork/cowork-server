package config

import (
	"strings"
	"testing"
)

const fourAccounts = `{
    "dev-1":{"id":"one","password":"first"},
    "dev-2":{"id":"two","password":"second"},
    "dev-3":{"id":"three","password":"third"},
    "dev-4":{"id":"four","password":"fourth"}
}`

func TestDevAccountsBindCredentialsToStableSlots(t *testing.T) {
	changed := strings.ReplaceAll(strings.ReplaceAll(fourAccounts, "one", "renamed"), "first", "rotated")
	for _, tc := range []struct{ raw, id, pw, slot string }{
		{fourAccounts, "one", "first", "dev-1"}, {fourAccounts, "four", "fourth", "dev-4"},
		{changed, "renamed", "rotated", "dev-1"}, {changed, "one", "first", ""},
		{fourAccounts, "one", "second", ""}, {"", "one", "first", ""},
	} {
		accounts, err := ParseDevAccounts(tc.raw)
		if err != nil {
			t.Fatal(err)
		}
		got, ok := accounts.Authenticate(tc.id, tc.pw)
		if got != tc.slot || ok != (tc.slot != "") {
			t.Fatal("unexpected developer account match")
		}
	}
	for _, bad := range []string{"{}", strings.ReplaceAll(fourAccounts, "two", "one"), strings.ReplaceAll(fourAccounts, "fourth", " "), strings.ReplaceAll(fourAccounts, "dev-4", "dev-5")} {
		if _, err := ParseDevAccounts(bad); err == nil {
			t.Fatal("accepted invalid developer account configuration")
		}
	}
}
