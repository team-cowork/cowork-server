package apidoc

import (
	"fmt"
	"net/http"
	"strings"

	"github.com/getkin/kin-openapi/openapi3"
)

func schemas() openapi3.Schemas {
	return openapi3.Schemas{
		"github_com_cowork_authorization_internal_apidoc.Error": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"code":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"message": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"status":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"code", "message", "status"},
			Type:     &openapi3.Types{"object"}}},
		"github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair": successSchema(200, schemaRef("github_com_cowork_authorization_internal_service.TokenPair")),
		"github_com_cowork_authorization_internal_apidoc.Success-internal_handler_LoginStartResponse":                        successSchema(200, schemaRef("internal_handler.LoginStartResponse")),
		"internal_handler.AcceptedLoginStartResponse":                                                                        successSchema(202, schemaRef("internal_handler.LoginStartResponse")),
		"github_com_cowork_authorization_internal_apidoc.Success-internal_handler_WebhookReceipt":                            successSchema(200, schemaRef("internal_handler.WebhookReceipt")),
		"github_com_cowork_authorization_internal_service.TokenPair": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"access_token":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "jwt", Description: "로그인과 갱신으로 발급하는 액세스 JWT입니다. sub는 사용자 ID이고 gsm_role은 DataGSM 학생 역할입니다. 학생 정보가 없는 계정의 gsm_role은 빈 문자열입니다. 플랫폼 role 클레임은 발급 대상에서 제외합니다."}},
			"expires_in":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"refresh_token": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"token_type":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_handler.LinkCallbackRequest":  &openapi3.SchemaRef{Value: callbackSchema()},
		"internal_handler.LoginCallbackRequest": &openapi3.SchemaRef{Value: callbackSchema().WithProperty("datagsm_redirect_uri", &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "uri", Description: "GitHub와 DataGSM에 등록한 콜백 URL이 다르면 GitHub 콜백에 DataGSM용 URL을 함께 보냅니다. 생략하면 처음 사용한 URL을 재사용합니다."})},

		"internal_handler.LoginStartRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"redirect_uri": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"provider": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}, Enum: []any{"DATAGSM", "GITHUB"},
				Description: "기본값은 GitHub입니다. GitHub가 설정되지 않은 환경에서는 DataGSM을 사용합니다."}},
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

func schemaRef(name string) *openapi3.SchemaRef {
	return &openapi3.SchemaRef{Ref: "#/components/schemas/" + name}
}

// Gateway wraps successful JSON responses in this envelope.
func successSchema(code int, data *openapi3.SchemaRef) *openapi3.SchemaRef {
	return &openapi3.SchemaRef{Value: openapi3.NewObjectSchema().
		WithRequired([]string{"code", "data", "message", "status"}).
		WithProperty("code", openapi3.NewIntegerSchema().WithEnum(code)).
		WithPropertyRef("data", data).
		WithProperty("message", &openapi3.Schema{Type: &openapi3.Types{"string"}, Example: http.StatusText(code)}).
		WithProperty("status", openapi3.NewStringSchema().WithEnum(fmt.Sprintf("%d %s", code, strings.ToUpper(http.StatusText(code)))))}
}

func callbackSchema() *openapi3.Schema {
	return openapi3.NewObjectSchema().WithProperty("code", openapi3.NewStringSchema()).WithProperty("error", openapi3.NewStringSchema()).WithProperty("state", openapi3.NewStringSchema()).WithRequired([]string{"state"})
}
