package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func paths() *openapi3.Paths {
	paths := openapi3.NewPaths()
	paths.Set("/auth/dev-signin", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary: "고정 개발 계정 로그인", Tags: []string{"auth"},
		Description: "Gateway가 개발 자격증명을 검증하면 계정의 아이디와 비밀번호로 토큰을 발급합니다.",
		Parameters: openapi3.Parameters{
			{Value: openapi3.NewHeaderParameter("X-Dev-Credential").WithRequired(true).WithSchema(openapi3.NewStringSchema())},
		},
		RequestBody: &openapi3.RequestBodyRef{Value: openapi3.NewRequestBody().WithRequired(true).WithJSONSchema(
			openapi3.NewObjectSchema().WithProperty("id", openapi3.NewStringSchema()).WithProperty("password", &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "password", WriteOnly: true}).WithRequired([]string{"id", "password"}))},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("토큰 발급 완료", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair", "WebSocket 인증 쿠키")),
			openapi3.WithName("400", jsonResponse("잘못된 요청", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("아이디 또는 비밀번호 오류", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("개발 자격증명 오류", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("415", jsonResponse("지원하지 않는 본문 형식", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스 사용 불가", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/authorize", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "로그인 시작",
		Description: "등록된 콜백 URL로 로그인 시도를 생성합니다. provider를 생략하면 DataGSM으로 로그인합니다. 반환된 authorization_url로 이동하고 쿠키를 유지한 채 /auth/callback에 콜백을 전달합니다. 시도는 10분 뒤 만료됩니다.",
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
			openapi3.WithName("400", jsonResponse("등록되지 않은 콜백, 사용할 수 없는 공급자 또는 잘못된 요청", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("허용하지 않은 출처", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("로그인 시도 저장 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/link/authorize", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "로그인 수단 연결 시작",
		Description: "현재 계정에 GitHub 또는 DataGSM 로그인을 연결하는 시도를 생성합니다. 반환된 authorization_url로 이동하고 쿠키를 유지한 채 /auth/link/callback에 콜백을 전달합니다. 시도는 시작한 사용자만 완료할 수 있고 10분 뒤 만료됩니다.",
		Tags:        []string{"auth"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Required: true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: schemaRef("internal_handler.LinkStartRequest")},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("인증 URL 발급 성공", "github_com_cowork_authorization_internal_apidoc.Success-internal_handler_LoginStartResponse", "일회성 연결 세션 쿠키")),
			openapi3.WithName("400", jsonResponse("등록되지 않은 콜백, 연결할 수 없는 공급자 또는 잘못된 요청", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("허용하지 않은 출처", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("연결 시도 저장 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/link/callback", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "로그인 수단 연결 완료",
		Description: "연결 시작 때 받은 쿠키와 콜백의 state, code 또는 error를 전달합니다. 연결해도 세션과 공통 프로필은 바뀌지 않습니다. DataGSM을 연결하면 학생 정보가 추가됩니다. 다른 계정에 연결된 로그인 수단이나 DataGSM 학생 ID, 같은 공급자가 이미 연결된 계정, 학생 정보가 이미 있는 계정에는 연결할 수 없습니다.",
		Tags:        []string{"auth"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Required: true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: schemaRef("internal_handler.LoginCallbackRequest")},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("204", openapi3.NewResponse().WithDescription("연결 완료. 응답 본문은 없습니다.")),
			openapi3.WithName("400", jsonResponse("필수 요청 값이 없거나 형식이 잘못됨", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 실패 또는 다른 사용자가 시작한 연결 시도", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("409", jsonResponse("이미 연결된 로그인 수단", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/callback", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "인가 코드로 토큰 발급",
		Description: "로그인 시작 때 받은 쿠키와 콜백의 state, code 또는 error를 전달합니다. 로그인 시도는 한 번만 사용할 수 있습니다. 처음 GitHub로 로그인하면 계정을 만듭니다. 같은 이메일이나 GitHub 아이디를 쓰는 계정이 있으면 409를 반환하며, 기존 계정으로 로그인한 뒤 GitHub를 연결해야 합니다. 검증된 이메일 중 gsm.hs.kr이 있는 GitHub 계정이 처음 로그인하면 409와 datagsm login required를 반환합니다. 이때는 DataGSM으로 로그인한 뒤 GitHub를 연결해야 합니다. DataGSM 첫 로그인이 학생 정보 없는 기존 계정과 충돌하면 409와 github login required를 반환합니다. 이때는 GitHub로 로그인한 뒤 DataGSM을 연결해야 합니다.",
		Tags:        []string{"auth"},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Description: "공급자 콜백",
			Required:    true,
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: schemaRef("internal_handler.LoginCallbackRequest")},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", cookieResponse("새 토큰 쌍", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_TokenPair", "WebSocket 인증 쿠키(cowork_ws_token, Path=/ws)")),
			openapi3.WithName("400", jsonResponse("필수 요청 값이 없거나 형식이 잘못됨", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인가 코드 검증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("409", jsonResponse("연결되지 않은 기존 계정과 충돌, DataGSM 로그인 필요 또는 GitHub 로그인 필요", "github_com_cowork_authorization_internal_apidoc.Error")),
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
	paths.Set("/auth/agent-tokens", &openapi3.PathItem{
		Post: &openapi3.Operation{
			Summary:     "에이전트 토큰 발급",
			Description: "AI 코딩 에이전트에 줄 토큰을 발급합니다. 토큰 원문은 이 응답에서만 반환합니다. 로그인 액세스 토큰으로만 호출할 수 있습니다.",
			Tags:        []string{"agent-tokens"},
			Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
			RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
				Required: true,
				Content: openapi3.Content{
					"application/json": &openapi3.MediaType{Schema: schemaRef("internal_handler.CreateAgentTokenRequest")},
				},
			}},
			Responses: openapi3.NewResponses(
				openapi3.WithName("200", jsonResponse("발급 성공", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_IssuedAgentToken")),
				openapi3.WithName("400", jsonResponse("이름, scope 또는 유효기간이 잘못됨", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("401", jsonResponse("인증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("403", jsonResponse("에이전트 토큰으로 호출함", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
			),
		},
		Get: &openapi3.Operation{
			Summary:     "에이전트 토큰 목록",
			Description: "내가 발급한 토큰을 최근 발급 순으로 반환합니다. 폐기한 토큰도 포함합니다. 토큰 원문은 반환하지 않습니다.",
			Tags:        []string{"agent-tokens"},
			Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
			Responses: openapi3.NewResponses(
				openapi3.WithName("200", jsonResponse("조회 성공", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_AgentTokenInfoList")),
				openapi3.WithName("401", jsonResponse("인증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("403", jsonResponse("에이전트 토큰으로 호출함", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
				openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
			),
		},
	})
	paths.Set("/auth/agent-tokens/{id}", &openapi3.PathItem{Delete: &openapi3.Operation{
		Summary:     "에이전트 토큰 폐기",
		Description: "폐기한 토큰은 더 이상 교환할 수 없습니다. 이미 교환한 액세스 토큰은 만료될 때까지 유효합니다.",
		Tags:        []string{"agent-tokens"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: openapi3.NewPathParameter("id").WithSchema(openapi3.NewInt64Schema().WithMin(1))},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("204", openapi3.NewResponse().WithDescription("폐기 완료. 응답 본문은 없습니다.")),
			openapi3.WithName("400", jsonResponse("잘못된 토큰 ID", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 실패", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("에이전트 토큰으로 호출함", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("404", jsonResponse("내 토큰이 아니거나 이미 폐기한 토큰", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("인증 서비스를 일시적으로 사용할 수 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
		),
	}})
	paths.Set("/auth/agent-tokens/exchange", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "에이전트 토큰 교환",
		Description: "에이전트 토큰을 액세스 토큰으로 교환합니다. 받은 액세스 토큰은 토큰의 scope에 허용된 API만 호출할 수 있고 에이전트 토큰의 만료 시각을 넘지 않습니다. 토큰은 X-Cowork-Agent-Token 헤더로 보내고 Authorization 헤더와 본문 없이 호출합니다. 같은 토큰의 반복 요청은 토큰마다 따로 제한합니다.",
		Tags:        []string{"agent-tokens"},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "header",
				Name:        "X-Cowork-Agent-Token",
				Description: "발급 응답의 토큰 원문",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}, Format: "password"}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("교환 성공", "github_com_cowork_authorization_internal_apidoc.Success-github_com_cowork_authorization_internal_service_AgentAccessToken")),
			openapi3.WithName("400", jsonResponse("X-Cowork-Agent-Token 헤더 없음", "github_com_cowork_authorization_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("유효하지 않거나 만료되었거나 폐기한 토큰", "github_com_cowork_authorization_internal_apidoc.Error")),
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
