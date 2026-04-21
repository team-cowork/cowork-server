package token_test

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/middleware"
	"github.com/go-chi/chi/v5"
	"github.com/stretchr/testify/assert"
)

type mockService struct {
	registerErr error
	deleteErr   error
}

func (m *mockService) RegisterToken(_ context.Context, _ int64, _, _ string) error {
	return m.registerErr
}
func (m *mockService) DeleteToken(_ context.Context, _ string) error {
	return m.deleteErr
}
func (m *mockService) Notify(_ context.Context, _ []int64, _, _ string, _ int64) error {
	return nil
}

func TestHandler_RegisterToken_success(t *testing.T) {
	svc := &mockService{}
	h := token.NewHandler(svc)

	body := `{"token":"fcm-token-123","platform":"ANDROID"}`
	r := httptest.NewRequest(http.MethodPost, "/notifications/tokens", strings.NewReader(body))
	r = r.WithContext(middleware.WithAccountID(r.Context(), int64(1)))
	w := httptest.NewRecorder()

	h.RegisterToken(w, r)

	assert.Equal(t, http.StatusCreated, w.Code)
}

func TestHandler_RegisterToken_missingBody(t *testing.T) {
	h := token.NewHandler(&mockService{})

	r := httptest.NewRequest(http.MethodPost, "/notifications/tokens", strings.NewReader(`{}`))
	r = r.WithContext(middleware.WithAccountID(r.Context(), int64(1)))
	w := httptest.NewRecorder()

	h.RegisterToken(w, r)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandler_RegisterToken_serviceError(t *testing.T) {
	svc := &mockService{registerErr: errors.New("db error")}
	h := token.NewHandler(svc)

	body := `{"token":"t","platform":"IOS"}`
	r := httptest.NewRequest(http.MethodPost, "/notifications/tokens", strings.NewReader(body))
	r = r.WithContext(middleware.WithAccountID(r.Context(), int64(1)))
	w := httptest.NewRecorder()

	h.RegisterToken(w, r)

	assert.Equal(t, http.StatusInternalServerError, w.Code)
}

func TestHandler_DeleteToken_success(t *testing.T) {
	h := token.NewHandler(&mockService{})

	r := httptest.NewRequest(http.MethodDelete, "/notifications/tokens/my-token", nil)
	rctx := chi.NewRouteContext()
	rctx.URLParams.Add("token", "my-token")
	r = r.WithContext(context.WithValue(r.Context(), chi.RouteCtxKey, rctx))
	w := httptest.NewRecorder()

	h.DeleteToken(w, r)

	assert.Equal(t, http.StatusNoContent, w.Code)
}
