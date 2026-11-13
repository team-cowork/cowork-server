package apidoc

import (
	"context"
	"encoding/json"
	"fmt"
	"html"
	"net/http"

	"github.com/getkin/kin-openapi/openapi3"
)

// swaggerUIVersion must be 5.32.0 or later because earlier Swagger UI releases cannot render OpenAPI 3.2 documents.
const swaggerUIVersion = "5.34.0"

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
	ui := []byte(swaggerUI(doc.Info.Title))
	return func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v3/api-docs":
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			_, _ = w.Write(document)
		case "/swagger", "/swagger/":
			http.Redirect(w, r, "/swagger/index.html", http.StatusMovedPermanently)
		case "/swagger/index.html":
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(ui)
		default:
			http.NotFound(w, r)
		}
	}, nil
}

func swaggerUI(title string) string {
	return fmt.Sprintf(`<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>%[1]s</title>
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@%[2]s/swagger-ui.css" />
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@%[2]s/swagger-ui-bundle.js"></script>
  <script>
    window.ui = SwaggerUIBundle({
      url: "/v3/api-docs",
      dom_id: "#swagger-ui"
    });
  </script>
</body>
</html>
`, html.EscapeString(title), swaggerUIVersion)
}
