// Package apidoc describes JSON bodies returned by the public gateway.
package apidoc

type Success[T any] struct {
	Status  string `json:"status" enums:"200 OK" binding:"required"`
	Code    int    `json:"code" enums:"200" binding:"required"`
	Message string `json:"message" example:"OK" binding:"required"`
	Data    T      `json:"data" binding:"required"`
}

type Error struct {
	Status  string `json:"status" binding:"required"`
	Code    int    `json:"code" binding:"required"`
	Message string `json:"message" binding:"required"`
}
