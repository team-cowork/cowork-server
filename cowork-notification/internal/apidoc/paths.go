package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func paths() *openapi3.Paths {
	paths := openapi3.NewPaths()
	paths.Set("/notifications/installations", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "Firebase 설치 등록",
		Description: "Firebase 설치 ID(FID)를 현재 계정에 등록합니다. 다른 계정에 등록된 FID는 현재 계정으로 이전합니다.",
		Tags:        []string{"installations"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "설치 등록 요청",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: schemaRef("internal_domain_installation.registerInstallationRequest")},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("201", openapi3.NewResponse().WithDescription("응답 본문 없음")),
			openapi3.WithName("400", jsonResponse("잘못된 요청", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 실패", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("내부 서버 오류", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
		),
	}})
	paths.Set("/notifications/installations/{fid}", &openapi3.PathItem{Delete: &openapi3.Operation{
		Summary:     "Firebase 설치 해제",
		Description: "로그아웃, 계정 전환 또는 FID 변경 시 현재 계정과 FID의 연결을 해제합니다.",
		Tags:        []string{"installations"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "fid",
				Description: "Firebase installation ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("204", openapi3.NewResponse().WithDescription("응답 본문 없음")),
			openapi3.WithName("400", jsonResponse("잘못된 요청", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 실패", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
			openapi3.WithName("404", jsonResponse("설치 없음", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("내부 서버 오류", "github_com_cowork_cowork-notification_internal_apidoc.Error")),
		),
	}})
	paths.Set("/notifications/stream", &openapi3.PathItem{Get: &openapi3.Operation{
		Summary:     "SSE 알림 스트림 구독",
		Description: "연결되면 connected 이벤트를 보내고 이후 알림을 전송합니다. 연결을 유지하기 위해 30초마다 ping 주석을 보냅니다. 같은 알림을 다시 받을 수 있으므로 eventId로 중복을 확인합니다.",
		Tags:        []string{"notifications"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", openapi3.NewResponse().WithDescription("data: {eventId, type, title, body, channelId, teamId}").WithContent(openapi3.Content{
				"application/json":  &openapi3.MediaType{Schema: &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}}},
				"text/event-stream": &openapi3.MediaType{Schema: &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}}},
			})),
			openapi3.WithName("401", openapi3.NewResponse().WithDescription("인증 실패").WithContent(openapi3.Content{
				"application/json":  &openapi3.MediaType{Schema: schemaRef("github_com_cowork_cowork-notification_internal_apidoc.Error")},
				"text/event-stream": &openapi3.MediaType{Schema: schemaRef("github_com_cowork_cowork-notification_internal_apidoc.Error")},
			})),
			openapi3.WithName("500", openapi3.NewResponse().WithDescription("서버에서 SSE를 지원하지 않음").WithContent(openapi3.Content{
				"application/json":  &openapi3.MediaType{Schema: schemaRef("github_com_cowork_cowork-notification_internal_apidoc.Error")},
				"text/event-stream": &openapi3.MediaType{Schema: schemaRef("github_com_cowork_cowork-notification_internal_apidoc.Error")},
			})),
		),
	}})
	return paths
}

func jsonResponse(description, name string) *openapi3.Response {
	return openapi3.NewResponse().WithDescription(description).WithJSONSchemaRef(schemaRef(name))
}
