// Package apidoc defines the public Gateway API documentation.
package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func Document() *openapi3.T {
	return &openapi3.T{
		OpenAPI: "3.1.0",
		Info: &openapi3.Info{
			Title:       "cowork Authorization API",
			Version:     "20261113.0",
			Description: "DataGSM 계정으로 로그인하고 cowork 토큰을 발급하거나 갱신합니다.",
		},
		Servers: openapi3.Servers{{URL: "/api/authorization", Description: "Gateway"}},
		Tags: openapi3.Tags{
			{Name: "auth", Description: "DataGSM 로그인과 토큰 발급, 갱신, 로그아웃을 처리합니다."},
			{Name: "events", Description: "DataGSM의 사용자 변경 이벤트를 수신합니다."},
		},
		Components: &openapi3.Components{
			Schemas: schemas(),
			SecuritySchemes: openapi3.SecuritySchemes{
				"BearerAuth": {Value: &openapi3.SecurityScheme{Type: "http", Scheme: "bearer", BearerFormat: "JWT", Description: "cowork 액세스 토큰"}},
			},
		},
		Paths: paths(),
	}
}
