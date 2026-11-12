package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func schemas() openapi3.Schemas {
	return openapi3.Schemas{
		"github_com_cowork_authorization_internal_apidoc.Error": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"code":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"message": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"status":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"code", "message", "status"},
			Type:     &openapi3.Types{"object"}}},
		"github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair": successSchema(schemaRef("github_com_cowork_authorization_internal_service.TokenPair")),
		"github_com_cowork_authorization_internal_apidoc.Success-internal_handler_LoginStartResponse":                        successSchema(schemaRef("internal_handler.LoginStartResponse")),
		"github_com_cowork_authorization_internal_apidoc.Success-internal_handler_WebhookReceipt":                            successSchema(schemaRef("internal_handler.WebhookReceipt")),
		"github_com_cowork_authorization_internal_service.TokenPair": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"access_token":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"expires_in":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"refresh_token": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"token_type":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_handler.LoginCallbackRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"code":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"error": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"state": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"state"},
			Type:     &openapi3.Types{"object"}}},
		"internal_handler.LoginStartRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"redirect_uri": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"redirect_uri"},
			Type:     &openapi3.Types{"object"}}},
		"internal_handler.LoginStartResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"authorization_url": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_handler.WebhookReceipt": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"status": &openapi3.SchemaRef{Value: &openapi3.Schema{Enum: []any{"accepted", "duplicate", "ignored"},
				Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"status"},
			Type:     &openapi3.Types{"object"}}},
	}
}

func schemaRef(name string) *openapi3.SchemaRef {
	return &openapi3.SchemaRef{Ref: "#/components/schemas/" + name}
}

// Gateway wraps successful JSON responses in this envelope.
func successSchema(data *openapi3.SchemaRef) *openapi3.SchemaRef {
	return &openapi3.SchemaRef{Value: openapi3.NewObjectSchema().
		WithRequired([]string{"code", "data", "message", "status"}).
		WithProperty("code", openapi3.NewIntegerSchema().WithEnum(200)).
		WithPropertyRef("data", data).
		WithProperty("message", &openapi3.Schema{Type: &openapi3.Types{"string"}, Example: "OK"}).
		WithProperty("status", openapi3.NewStringSchema().WithEnum("200 OK"))}
}
