package apperr

import "fmt"

type AppError struct {
	Code    int
	Message string
}

func (e *AppError) Error() string {
	return fmt.Sprintf("[%d] %s", e.Code, e.Message)
}

func NotFound(msg string) *AppError   { return &AppError{Code: 404, Message: msg} }
func Internal(msg string) *AppError   { return &AppError{Code: 500, Message: msg} }
func BadRequest(msg string) *AppError { return &AppError{Code: 400, Message: msg} }
