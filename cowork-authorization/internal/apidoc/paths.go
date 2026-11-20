package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func paths() *openapi3.Paths {
	paths := openapi3.NewPaths()
	paths.Set("/auth/dev-signin", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary: "고정 개발 계정 로그인", Tags: []string{"auth"},
		Description: "개발 자격증명은 헤더와 쿼리 중 한 곳에만 전달합니다. Gateway가 자격증명을 검증하면 계정의 아이디와 비밀번호로 토큰을 발급합니다.",
		Parameters: openapi3.Parameters{
			{Value: openapi3.NewHeaderParameter("X-Dev-Credential").WithSchema(openapi3.NewStringSchema())},
			{Value: openapi3.NewQueryParameter("dev_credential").WithSchema(openapi3.NewStringSchema())},
		},
		RequestBody: &openapi3.RequestBodyRef{Value: openapi3.NewRequestBody().WithRequired(true).WithJSONSchema(
			openapi3.NewObjectSchema().WithProperty("id", openapi3.NewStringSchema()).WithProperty("password", &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "password", WriteOnly: true}).WithRequired([]string{"id", "password"}))},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("토큰 발급 완료", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair", "WebSocket 인증 쿠키")),
			openapi3.WithName("400", jsonResponse("잘못된 요청", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("아이디 또는 비밀번호 오류", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("403", openapi3.NewResponse().WithDescription("개발 자격증명 오류")),
			openapi3.WithName("415", jsonResponse("지원하지 않는 본문 형식", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스 사용 불가", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/authorize", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "DataGSM 로그인 시작",
		Description: "등록된 콜백 URL로 로그인 시도를 생성합니다. 반환된 authorization_url로 이동하고 쿠키를 유지한 채 /auth/callback에 콜백을 전달합니다. 시도는 10분 뒤 만료됩니다.",
		Tags:        []string{"auth"},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "등록된 콜백 URL",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: schemaRef("internal_handler.LoginStartRequest")},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("인증 URL 발급 성공", "github_com_cowork_authorization_internal_apidoc.Success-internal_handler_LoginStartResponse", "일회성 로그인 세션 쿠키(Secure, HttpOnly)")),
			openapi3.WithName("400", jsonResponse("등록되지 않은 콜백 또는 잘못된 요청", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("허용하지 않은 출처", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("로그인 시도 저장 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/callback", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "인가 코드로 토큰 발급",
		Description: "로그인 시작 때 받은 쿠키와 콜백의 state, code 또는 error를 전달합니다. 로그인 시도는 한 번만 사용할 수 있습니다.",
		Tags:        []string{"auth"},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "DataGSM 콜백",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: schemaRef("internal_handler.LoginCallbackRequest")},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("새 토큰 쌍", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair", "WebSocket 인증 쿠키(cowork_ws_token, Path=/ws)")),
			openapi3.WithName("400", jsonResponse("필수 요청 값이 없거나 형식이 잘못됨", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인가 코드 검증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/refresh", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "액세스 토큰 갱신",
		Description: "리프레시 토큰을 교환해 새 액세스 토큰과 새 리프레시 토큰을 받습니다.",
		Tags:        []string{"auth"},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "리프레시 토큰",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
					"refresh_token": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
				},
					Title: "body",
					Type:  &openapi3.Types{"object"}}}},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("새 토큰 쌍", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair", "WebSocket 인증 쿠키(cowork_ws_token, Path=/ws)")),
			openapi3.WithName("400", jsonResponse("필수 요청 값이 없거나 형식이 잘못됨", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("유효하지 않거나 만료된 리프레시 토큰", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/signout", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "로그아웃",
		Description: "리프레시 토큰을 무효화하고 WebSocket 인증 쿠키를 삭제합니다.",
		Tags:        []string{"auth"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "리프레시 토큰",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
					"refresh_token": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
				},
					Title: "body",
					Type:  &openapi3.Types{"object"}}}},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("204", openapi3.NewResponse().WithDescription("로그아웃 완료. 응답 본문은 없습니다.")),
			openapi3.WithName("400", jsonResponse("필수 요청 값이 없거나 형식이 잘못됨", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 실패 또는 유효하지 않은 리프레시 토큰", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/events/datagsm", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "DataGSM 웹훅 접수",
		Description: "서명한 이벤트 본문을 전송합니다(최대 1 MiB). 200은 접수를 뜻하며 학생 정보는 나중에 반영됩니다. 같은 ID와 내용은 중복으로 처리하고 내용이 다르면 충돌로 처리합니다. 발생한 지 30일 이상 지났거나 현재보다 5분을 초과해 앞선 이벤트는 처리하지 않습니다.",
		Tags:        []string{"events"},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "header",
				Name:        "X-DataGSM-Signature",
				Description: "sha256=<HMAC-SHA256(secret, body)>",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			}},
		},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "이벤트 ID, 종류, RFC 3339 발생 시각 및 데이터. data.old와 data.new는 같은 index로 변경 전후를 짝지으며 학생의 new.object는 비어 있을 수 없습니다.",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
					"data":      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"object"}}},
					"event":     &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
					"id":        &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
					"timestamp": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
				},
					Title: "body",
					Type:  &openapi3.Types{"object"}}}},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("접수 상태: accepted, duplicate 또는 ignored", "github_com_cowork_authorization_internal_apidoc.Success-internal_handler_WebhookReceipt")),
			openapi3.WithName("400", jsonResponse("잘못된 이벤트 형식 또는 발생 시각", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("웹훅 서명 검증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("409", jsonResponse("같은 이벤트 ID의 내용 불일치", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("413", jsonResponse("본문 크기 제한 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("웹훅 접수 서비스 사용 불가", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	return paths
}

func jsonResponse(description, name string) *openapi3.Response {
	return openapi3.NewResponse().WithDescription(description).WithJSONSchemaRef(schemaRef(name))
}

func cookieResponse(description, name, cookie string) *openapi3.Response {
	response := jsonResponse(description, name)
	response.Headers = openapi3.Headers{
		"Set-Cookie": {Value: &openapi3.Header{Parameter: openapi3.Parameter{
			Description: cookie,
			Schema:      &openapi3.SchemaRef{Value: openapi3.NewStringSchema()},
		}}},
	}
	return response
}
