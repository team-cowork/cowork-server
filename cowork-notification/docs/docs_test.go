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

	if spec.BasePath != "/api/notification" {
		t.Fatalf("basePath = %q", spec.BasePath)
	}
	if _, exists := spec.Paths["/health"]; exists {
		t.Fatal("Gateway-facing Swagger must not expose /health")
	}
	if _, exists := spec.Paths["/notifications/stream"]; !exists {
		t.Fatal("Gateway-facing Swagger must include the SSE endpoint")
	}
}
