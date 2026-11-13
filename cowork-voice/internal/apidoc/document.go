// Package apidoc defines the public Gateway API documentation.
package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func Document() *openapi3.T {
	return &openapi3.T{
		OpenAPI: "3.1.0",
		Info: &openapi3.Info{
			Title:       "cowork Voice API",
			Version:     "20261113.0",
			Description: "음성 통화와 라이브 방송을 관리하고 LiveKit 접속 토큰을 발급합니다.",
		},
		Servers: openapi3.Servers{{URL: "/api/voice", Description: "Gateway"}},
		Tags: openapi3.Tags{
			{Name: "voice", Description: "음성 채널 입장과 퇴장, 참여자와 세션 조회를 처리합니다."},
			{Name: "live", Description: "라이브 방송 시작, 시청 참여와 퇴장, 방송 상태 조회를 처리합니다."},
			{Name: "webhook", Description: "LiveKit의 통화와 방송 이벤트를 수신합니다."},
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
