// Package apidoc describes JSON bodies returned by the public gateway.
package apidoc

type Error struct {
	Status  string `json:"status" binding:"required"`
	Code    int    `json:"code" binding:"required"`
	Message string `json:"message" binding:"required"`
}
