package apidoc

import (
	"context"
	"encoding/json"
	"fmt"
	"html"
	"net/http"

	"github.com/getkin/kin-openapi/openapi3"
)

// Swagger UI renders OpenAPI 3.2 documents from 5.32.0. swaggo/files v2.0.2 still bundles 5.18.2, so this
// package pins the Swagger UI version itself and loads it from the CDN. The integrity hashes pin the CDN files
// to this version and must change with it.
// TODO: Return to swaggo/http-swagger and remove this pinned CDN version when swaggo/files bundles Swagger UI
// 5.32.0 or later.
const (
	swaggerUIVersion   = "5.34.0"
	swaggerUICSSSRI    = "sha384-4c3vsE5f+trhFyHrFrLM1fFGUn531NIaC7dcqEWIJdoRQJ5CkJbSuSCwXLpaS1KF"
	swaggerUIBundleSRI = "sha384-9Td7MKiIr2S83E1kZyyVi8KdJw/S8CpAvu0llLyMeg0xq/hDGiZJfVn0fV4llCmK"
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
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@%[2]s/swagger-ui.css" integrity="%[3]s" crossorigin="anonymous" />
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@%[2]s/swagger-ui-bundle.js" integrity="%[4]s" crossorigin="anonymous"></script>
  <script>
    window.ui = SwaggerUIBundle({
      url: "/v3/api-docs",
      dom_id: "#swagger-ui"
    });
  </script>
</body>
</html>
`, html.EscapeString(title), swaggerUIVersion, swaggerUICSSSRI, swaggerUIBundleSRI)
}
