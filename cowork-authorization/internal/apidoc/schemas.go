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
			"access_token":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "jwt", Description: "로그인과 갱신으로 발급하는 액세스 JWT입니다. sub는 사용자 ID이고 gsm_role은 DataGSM 학생 역할입니다. 학생 정보가 없는 계정의 gsm_role은 빈 문자열입니다. 플랫폼 role 클레임은 발급 대상에서 제외합니다."}},
			"expires_in":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"refresh_token": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"token_type":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_IssuedAgentToken":   successSchema(schemaRef("github_com_cowork_authorization_internal_service.IssuedAgentToken")),
		"github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_AgentTokenInfoList": successSchema(&openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"array"}, Items: schemaRef("github_com_cowork_authorization_internal_service.AgentTokenInfo")}}),
		"github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_AgentAccessToken":   successSchema(schemaRef("github_com_cowork_authorization_internal_service.AgentAccessToken")),
		"github_com_cowork_authorization_internal_service.AgentTokenInfo":                                                             agentTokenInfoSchema(false),
		"github_com_cowork_authorization_internal_service.IssuedAgentToken":                                                           agentTokenInfoSchema(true),
		"github_com_cowork_authorization_internal_service.AgentAccessToken": &openapi3.SchemaRef{Value: openapi3.NewObjectSchema().
			WithProperty("access_token", &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "jwt", Description: "token_use 클레임은 agent이고 scope 클레임은 공백으로 구분한 scope 목록입니다."}).
			WithProperty("token_type", openapi3.NewStringSchema().WithEnum("Bearer")).
			WithProperty("expires_in", openapi3.NewIntegerSchema()).
			WithRequired([]string{"access_token", "token_type", "expires_in"})},
		"internal_handler.CreateAgentTokenRequest": &openapi3.SchemaRef{Value: openapi3.NewObjectSchema().
			WithProperty("name", openapi3.NewStringSchema().WithMaxLength(100)).
			WithProperty("scopes", openapi3.NewArraySchema().WithMinItems(1).WithItems(agentScopeSchema())).
			WithProperty("expires_in_days", openapi3.NewIntegerSchema().WithMin(1).WithMax(365)).
			WithRequired([]string{"name", "scopes", "expires_in_days"})},
		"internal_handler.LoginCallbackRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"code":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"error": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"state": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"state"},
			Type:     &openapi3.Types{"object"}}},
		"internal_handler.LoginStartRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"redirect_uri": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"provider": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}, Enum: []any{"DATAGSM", "GITHUB"}, Default: "DATAGSM",
				Description: "GITHUB는 서버에 GitHub 로그인이 설정된 경우에만 사용할 수 있습니다."}},
		},
			Required: []string{"redirect_uri"},
			Type:     &openapi3.Types{"object"}}},
		"internal_handler.LinkStartRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"redirect_uri": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"provider":     &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}, Enum: []any{"DATAGSM", "GITHUB"}}},
		},
			Required: []string{"provider", "redirect_uri"},
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

func agentScopeSchema() *openapi3.Schema {
	schema := openapi3.NewStringSchema().WithEnum("workspace:read", "chat:read", "chat:write")
	schema.Description = "workspace:read는 내 팀/팀의 채널/채널의 스레드 목록 조회, chat:read는 채널 메시지 조회, chat:write는 스레드 메시지 전송을 허용합니다."
	return schema
}

// nullableDateTimeSchema lists null in type because OpenAPI 3.2 ignores the 3.0 nullable keyword.
func nullableDateTimeSchema() *openapi3.Schema {
	schema := openapi3.NewDateTimeSchema()
	schema.Type = &openapi3.Types{openapi3.TypeString, openapi3.TypeNull}
	return schema
}

func agentTokenInfoSchema(issued bool) *openapi3.SchemaRef {
	schema := openapi3.NewObjectSchema().
		WithProperty("id", openapi3.NewInt64Schema()).
		WithProperty("name", openapi3.NewStringSchema()).
		WithProperty("scopes", openapi3.NewArraySchema().WithItems(agentScopeSchema())).
		WithProperty("expires_at", openapi3.NewDateTimeSchema()).
		WithProperty("last_used_at", nullableDateTimeSchema()).
		WithProperty("revoked_at", nullableDateTimeSchema()).
		WithProperty("created_at", openapi3.NewDateTimeSchema())
	required := []string{"id", "name", "scopes", "expires_at", "last_used_at", "revoked_at", "created_at"}
	if issued {
		schema = schema.WithProperty("token", &openapi3.Schema{Type: &openapi3.Types{"string"}, Description: "토큰 원문입니다. 다시 조회할 수 없습니다."})
		required = append(required, "token")
	}
	return &openapi3.SchemaRef{Value: schema.WithRequired(required)}
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
