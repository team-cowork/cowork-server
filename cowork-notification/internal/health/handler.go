package health

import "net/http"

// Handler reports liveness. It returns JSON so the public Gateway probe can tell a downstream response apart.
func Handler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"UP"}`))
}
