package docs

import (
	"encoding/json"
	"testing"
)

func TestGatewayOpenAPIContract(t *testing.T) {
	var spec struct {
		BasePath string                     `json:"basePath"`
		Paths    map[string]json.RawMessage `json:"paths"`
	}
	if err := json.Unmarshal([]byte(SwaggerInfo.ReadDoc()), &spec); err != nil {
		t.Fatalf("parse generated Swagger: %v", err)
	}

	if spec.BasePath != "/api/voice" {
		t.Fatalf("basePath = %q", spec.BasePath)
	}
	for _, path := range []string{"/live/channels/{channel_id}", "/voice/channels/{channel_id}/join", "/voice/webhook"} {
		if _, exists := spec.Paths[path]; !exists {
			t.Fatalf("Gateway-facing Swagger must include %s", path)
		}
	}
}
