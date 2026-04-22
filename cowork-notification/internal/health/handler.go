package health

import "net/http"

// Handler godoc
//
//	@Summary		헬스 체크
//	@Description	서비스 생존 여부 확인
//	@Tags			system
//	@Produce		plain
//	@Success		200	{string}	string	"ok"
//	@Router			/health [get]
func Handler(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte("ok"))
}
