package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func schemas() openapi3.Schemas {
	return openapi3.Schemas{
		"github_com_cowork_cowork-voice_internal_apidoc.Error": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"code":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"message": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"status":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"code", "message", "status"},
			Type:     &openapi3.Types{"object"}}},
		"github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_liveroom_JoinResponse":          successSchema(schemaRef("internal_domain_liveroom.JoinResponse")),
		"github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_liveroom_StartResponse":         successSchema(schemaRef("internal_domain_liveroom.StartResponse")),
		"github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_liveroom_StatusResponse":        successSchema(schemaRef("internal_domain_liveroom.StatusResponse")),
		"github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_voiceroom_JoinResponse":         successSchema(schemaRef("internal_domain_voiceroom.JoinResponse")),
		"github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_voiceroom_ParticipantsResponse": successSchema(schemaRef("internal_domain_voiceroom.ParticipantsResponse")),
		"github_com_cowork_cowork-voice_internal_apidoc.Success-internal_domain_voiceroom_SessionResponse":      successSchema(schemaRef("internal_domain_voiceroom.SessionResponse")),
		"internal_domain_liveroom.JoinResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"host_user_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 1,
				Type: &openapi3.Types{"integer"}}},
			"livekit_url": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "wss://livekit.example.com",
				Type: &openapi3.Types{"string"}}},
			"room_name": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "live-42-550e8400",
				Type: &openapi3.Types{"string"}}},
			"session_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "550e8400-e29b-41d4-a716-446655440000",
				Type: &openapi3.Types{"string"}}},
			"token": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "eyJhbGciOiJIUzI1NiJ9...",
				Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_domain_liveroom.StartResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"livekit_url": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "wss://livekit.example.com",
				Type: &openapi3.Types{"string"}}},
			"room_name": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "live-42-550e8400",
				Type: &openapi3.Types{"string"}}},
			"session_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "550e8400-e29b-41d4-a716-446655440000",
				Type: &openapi3.Types{"string"}}},
			"token": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "eyJhbGciOiJIUzI1NiJ9...",
				Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_domain_liveroom.StatusResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"host_user_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 1,
				Type: &openapi3.Types{"integer"}}},
			"live": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: true,
				Type: &openapi3.Types{"boolean"}}},
			"session_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "550e8400-e29b-41d4-a716-446655440000",
				Type: &openapi3.Types{"string"}}},
			"started_at": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "2024-01-01T00:00:00Z",
				Type: &openapi3.Types{"string"}}},
			"viewer_count": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 3,
				Type: &openapi3.Types{"integer"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_domain_voiceroom.JoinResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"bitrate": &openapi3.SchemaRef{Value: &openapi3.Schema{Description: "Bitrate는 클라이언트가 오디오 publish에 적용할 bps 값이다. null이면 클라이언트 기본값을 쓴다.",
				Example: 64000,
				Type:    &openapi3.Types{"integer"}}},
			"livekit_url": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "wss://livekit.example.com",
				Type: &openapi3.Types{"string"}}},
			"room_name": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "channel-42",
				Type: &openapi3.Types{"string"}}},
			"session_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "550e8400-e29b-41d4-a716-446655440000",
				Type: &openapi3.Types{"string"}}},
			"token": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "eyJhbGciOiJIUzI1NiJ9...",
				Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_domain_voiceroom.ParticipantResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"joined_at": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "2024-01-01T00:00:00Z",
				Type: &openapi3.Types{"string"}}},
			"user_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 1,
				Type: &openapi3.Types{"integer"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_domain_voiceroom.ParticipantsResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"channel_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 42,
				Type: &openapi3.Types{"integer"}}},
			"participants": &openapi3.SchemaRef{Value: &openapi3.Schema{Items: schemaRef("internal_domain_voiceroom.ParticipantResponse"),
				Type: &openapi3.Types{"array"}}},
			"room_name": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "channel-42",
				Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
		"internal_domain_voiceroom.SessionResponse": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"channel_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 42,
				Type: &openapi3.Types{"integer"}}},
			"ended_at": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "2024-01-01T01:00:00Z",
				Type: &openapi3.Types{"string"}}},
			"session_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "550e8400-e29b-41d4-a716-446655440000",
				Type: &openapi3.Types{"string"}}},
			"started_at": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "2024-01-01T00:00:00Z",
				Type: &openapi3.Types{"string"}}},
			"status": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "active",
				Type: &openapi3.Types{"string"}}},
			"team_id": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: 1,
				Type: &openapi3.Types{"integer"}}},
		},
			Type: &openapi3.Types{"object"}}},
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
