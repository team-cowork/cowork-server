package token

type RegisterTokenResponse struct {
	AccountID int64  `json:"accountId"`
	Token     string `json:"token"`
	Platform  string `json:"platform"`
}
