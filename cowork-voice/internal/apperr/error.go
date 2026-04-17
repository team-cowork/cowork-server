package apperr

import (
	"encoding/json"
	"net/http"
)

type Error struct {
	HTTPStatus int
	Code       string
	Message    string
}

func (e *Error) Error() string {
	return e.Message
}

func NotMember() *Error {
	return &Error{HTTPStatus: http.StatusForbidden, Code: "FORBIDDEN", Message: "채널 멤버가 아닙니다."}
}

func ServiceUnavailable(msg string) *Error {
	return &Error{HTTPStatus: http.StatusServiceUnavailable, Code: "SERVICE_UNAVAILABLE", Message: msg}
}

func NotFound(msg string) *Error {
	return &Error{HTTPStatus: http.StatusNotFound, Code: "NOT_FOUND", Message: msg}
}

func BadRequest(msg string) *Error {
	return &Error{HTTPStatus: http.StatusBadRequest, Code: "BAD_REQUEST", Message: msg}
}

func Unauthorized() *Error {
	return &Error{HTTPStatus: http.StatusUnauthorized, Code: "UNAUTHORIZED", Message: "인증이 필요합니다."}
}

func Internal(msg string) *Error {
	return &Error{HTTPStatus: http.StatusInternalServerError, Code: "INTERNAL_SERVER_ERROR", Message: "일시적인 서버 오류가 발생했습니다."}
}

func WriteResponse(w http.ResponseWriter, err *Error) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(err.HTTPStatus)
	json.NewEncoder(w).Encode(map[string]any{
		"status":  err.Code,
		"code":    err.HTTPStatus,
		"message": err.Message,
	})
}
