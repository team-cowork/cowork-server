package apidoc

import "github.com/getkin/kin-openapi/openapi3"

func schemas() openapi3.Schemas {
	return openapi3.Schemas{
		"github_com_cowork_cowork-notification_internal_apidoc.Error": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"code":    &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"integer"}}},
			"message": &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
			"status":  &openapi3.SchemaRef{Value: &openapi3.Schema{Type: &openapi3.Types{"string"}}},
		},
			Required: []string{"code", "message", "status"},
			Type:     &openapi3.Types{"object"}}},
		"internal_domain_installation.registerInstallationRequest": &openapi3.SchemaRef{Value: &openapi3.Schema{Properties: openapi3.Schemas{
			"fid": &openapi3.SchemaRef{Value: &openapi3.Schema{Example: "cXk9Y2Rl-ZWZnaGlqa2xt_",
				Type: &openapi3.Types{"string"}}},
			"platform": &openapi3.SchemaRef{Value: &openapi3.Schema{Enum: []any{"IOS", "ANDROID", "WEB"},
				Type: &openapi3.Types{"string"}}},
		},
			Type: &openapi3.Types{"object"}}},
	}
}

func schemaRef(name string) *openapi3.SchemaRef {
	return &openapi3.SchemaRef{Ref: "#/components/schemas/" + name}
}
