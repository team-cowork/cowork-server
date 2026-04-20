package apperror

import (
	"encoding/json"
	"net/http"
)

// AppError godoc
// @Description API 에러 응답
type AppError struct {
	HTTPStatus int    `json:"-"`
	Code       string `json:"status" example:"NOT_FOUND"`
	Message    string `json:"message" example:"session not found"`
}

func (e *AppError) Error() string {
	return e.Message
}

func NotMember() *AppError {
	return &AppError{HTTPStatus: http.StatusForbidden, Code: "FORBIDDEN", Message: "채널 멤버가 아닙니다."}
}

func ServiceUnavailable(msg string) *AppError {
	return &AppError{HTTPStatus: http.StatusServiceUnavailable, Code: "SERVICE_UNAVAILABLE", Message: msg}
}

func NotFound(msg string) *AppError {
	return &AppError{HTTPStatus: http.StatusNotFound, Code: "NOT_FOUND", Message: msg}
}

func Unauthorized() *AppError {
	return &AppError{HTTPStatus: http.StatusUnauthorized, Code: "UNAUTHORIZED", Message: "인증이 필요합니다."}
}

func Internal(msg string) *AppError {
	return &AppError{HTTPStatus: http.StatusInternalServerError, Code: "INTERNAL_SERVER_ERROR", Message: msg}
}

func WriteResponse(w http.ResponseWriter, err *AppError) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(err.HTTPStatus)
	json.NewEncoder(w).Encode(map[string]any{
		"status":  err.Code,
		"message": err.Message,
	})
}
