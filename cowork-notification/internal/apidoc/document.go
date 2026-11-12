// Package apidoc defines the public Gateway API documentation.
package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func Document() *openapi3.T {
	return &openapi3.T{
		OpenAPI: "3.1.0",
		Info: &openapi3.Info{
			Title:       "cowork Notification API",
			Version:     "20261113.0",
			Description: "Firebase 설치를 계정에 연결하고 실시간 알림을 제공합니다.",
		},
		Servers: openapi3.Servers{{URL: "/api/notification", Description: "Gateway"}},
		Tags: openapi3.Tags{
			{Name: "installations", Description: "알림을 받을 Firebase 설치를 등록하고 해제합니다."},
			{Name: "notifications", Description: "실시간 알림 스트림을 구독합니다."},
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
