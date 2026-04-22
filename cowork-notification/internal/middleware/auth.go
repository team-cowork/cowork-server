package middleware

import (
	"context"
	"net/http"
	"strconv"
)

type contextKey string

const contextKeyAccountID contextKey = "accountID"

func ExtractAuthUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		userIDStr := r.Header.Get("X-User-Id")
		if userIDStr == "" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		userID, err := strconv.ParseInt(userIDStr, 10, 64)
		if err != nil {
			http.Error(w, "invalid user id", http.StatusUnauthorized)
			return
		}
		ctx := context.WithValue(r.Context(), contextKeyAccountID, userID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func AccountIDFromContext(ctx context.Context) (int64, bool) {
	id, ok := ctx.Value(contextKeyAccountID).(int64)
	return id, ok
}

func WithAccountID(ctx context.Context, id int64) context.Context {
	return context.WithValue(ctx, contextKeyAccountID, id)
}
