package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func paths() *openapi3.Paths {
	paths := openapi3.NewPaths()
	paths.Set("/live/channels/{channel_id}", &openapi3.PathItem{Get: &openapi3.Operation{
		Summary:     "라이브 상태 조회",
		Description: "채널의 현재 라이브 진행 여부와 시청자 수를 반환합니다.",
		Tags:        []string{"live"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("방송 상태와 시청자 수", "github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_liveroom_StatusResponse")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/live/channels/{channel_id}/join", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "라이브 시청 참여",
		Description: "시청자는 방송을 수신하며 screen_share 권한이 있으면 카메라도 켤 수 있습니다. 시청자는 마이크와 화면을 공유할 수 없습니다. 호스트가 다시 입장하면 호스트 권한으로 토큰을 발급합니다.",
		Tags:        []string{"live"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("접속 토큰과 세션 정보", "github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_liveroom_JoinResponse")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("404", jsonResponse("진행 중인 라이브 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/live/channels/{channel_id}/leave", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "라이브 퇴장",
		Description: "라이브에서 퇴장합니다. 호스트가 퇴장하면 방송 전체가 종료되고 시청자도 모두 퇴장합니다.",
		Tags:        []string{"live"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("204", openapi3.NewResponse().WithDescription("퇴장 완료. 응답 본문은 없습니다.")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("404", jsonResponse("진행 중인 라이브 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/live/channels/{channel_id}/start", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "라이브 방송 시작",
		Description: "live_broadcast 권한이 있는 사용자가 호스트가 됩니다. 채널마다 한 번에 하나의 방송만 열 수 있습니다. 마이크에는 voice_speak 권한이 필요하고 카메라와 화면 공유에는 screen_share 권한이 필요합니다.",
		Tags:        []string{"live"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("호스트 접속 토큰과 세션 정보", "github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_liveroom_StartResponse")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 또는 방송 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("409", jsonResponse("이미 라이브 진행 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/voice/channels/{channel_id}/join", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "음성 채널 입장",
		Description: "채널에 입장합니다. LiveKit 토큰과 연결 URL을 반환합니다. 기존 세션이 없으면 새 세션을 만듭니다. 마이크에는 voice_speak 권한이 필요하고 카메라와 화면 공유에는 screen_share 권한이 필요합니다.",
		Tags:        []string{"voice"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("접속 토큰과 세션 정보", "github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_voiceroom_JoinResponse")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("409", jsonResponse("음성 채널 정원 초과", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/voice/channels/{channel_id}/leave", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "음성 채널 퇴장",
		Description: "마지막 참여자가 퇴장하면 세션이 종료됩니다.",
		Tags:        []string{"voice"},
		Security:    &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("204", openapi3.NewResponse().WithDescription("퇴장 완료. 응답 본문은 없습니다.")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("404", jsonResponse("활성 세션 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/voice/channels/{channel_id}/participants", &openapi3.PathItem{Get: &openapi3.Operation{
		Summary:  "채널 참여자 목록 조회",
		Tags:     []string{"voice"},
		Security: &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "channel_id",
				Description: "채널 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("현재 참여자 목록", "github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_voiceroom_ParticipantsResponse")),
			openapi3.WithName("400", jsonResponse("채널 ID 형식 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/voice/sessions/{session_id}", &openapi3.PathItem{Get: &openapi3.Operation{
		Summary:  "세션 조회",
		Tags:     []string{"voice"},
		Security: &openapi3.SecurityRequirements{{"BearerAuth": {}}},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "path",
				Name:        "session_id",
				Description: "세션 ID",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			}},
		},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", jsonResponse("음성 세션 정보", "github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_voiceroom_SessionResponse")),
			openapi3.WithName("401", jsonResponse("인증 필요", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("403", jsonResponse("음성 채널 접근 권한 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("404", jsonResponse("세션 없음", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("429", jsonResponse("요청 한도 초과", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("503", jsonResponse("채널 권한 동기화 중", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	paths.Set("/voice/webhook", &openapi3.PathItem{Post: &openapi3.Operation{
		Summary:     "LiveKit 웹훅 수신",
		Description: "LiveKit 서버가 전송하는 participant_joined, participant_left, room_finished 이벤트를 처리합니다. 이 경로는 LiveKit 서버에서 호출합니다.",
		Tags:        []string{"webhook"},
		Parameters: openapi3.Parameters{
			{Value: &openapi3.Parameter{
				In:          "header",
				Name:        "Authorization",
				Description: "LiveKit 웹훅 서명",
				Required:    true,
				Schema:      &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			}},
		},
		RequestBody: &openapi3.RequestBodyRef{Value: &openapi3.RequestBody{
			Content: openapi3.Content{
				"application/json": &openapi3.MediaType{Schema: &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"object"}}}},
			},
		}},
		Responses: openapi3.NewResponses(
			openapi3.WithName("200", openapi3.NewResponse().WithDescription("수신 완료. 응답 본문은 없습니다.")),
			openapi3.WithName("401", jsonResponse("웹훅 서명 검증 실패", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
			openapi3.WithName("500", jsonResponse("서버 오류", "github_com_cowork_cowork-voice_internal_apidoc.Error")),
		),
	}})
	return paths
}

func jsonResponse(description, name string) *openapi3.Response {
	return openapi3.NewResponse().WithDescription(description).WithJSONSchemaRef(schemaRef(name))
}
