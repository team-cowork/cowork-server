package apidoc

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"github.com/getkin/kin-openapi/openapi3"
	httpSwagger "github.com/swaggo/http-swagger/v2"
)

func NewHandler() (http.HandlerFunc, error) {
	doc := Document()
	if err := openapi3.NewLoader().ResolveRefsIn(doc, nil); err != nil {
		return nil, fmt.Errorf("resolve OpenAPI references: %w", err)
	}
	if err := doc.Validate(context.Background()); err != nil {
		return nil, fmt.Errorf("validate OpenAPI document: %w", err)
	}
	document, err := json.Marshal(doc)
	if err != nil {
		return nil, fmt.Errorf("encode OpenAPI document: %w", err)
	}
	ui := httpSwagger.Handler(httpSwagger.URL("/swagger/doc.json"))
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/swagger/doc.json" {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			_, _ = w.Write(document)
			return
		}
		ui.ServeHTTP(w, r)
	}, nil
}
