defmodule CoworkUser.OpenAPI do
  def spec do
    %{
      openapi: "3.0.3",
      info: %{
        title: "cowork-user API",
        version: "v1",
        description: "cowork-user 사용자 관리 서비스 API"
      },
      paths: %{
        "/users/me" => %{
          get: %{summary: "내 프로필 조회", responses: responses(user_schema())},
          patch: %{summary: "내 프로필 수정", requestBody: json_body(update_profile_schema()), responses: responses(user_schema())}
        },
        "/users/me/profile-image/presigned" => %{
          post: %{summary: "프로필 이미지 업로드 URL 발급", requestBody: json_body(%{type: "object", required: ["content_type"], properties: %{content_type: %{type: "string"}}}), responses: responses(%{type: "object", properties: %{upload_url: %{type: "string"}, object_key: %{type: "string"}}})}
        },
        "/users/me/profile-image/confirm" => %{
          post: %{summary: "프로필 이미지 업로드 확인", requestBody: json_body(%{type: "object", required: ["object_key"], properties: %{object_key: %{type: "string"}}}), responses: %{"200" => %{description: "OK"}}}
        },
        "/users/me/profile-image" => %{
          delete: %{summary: "프로필 이미지 삭제", responses: %{"204" => %{description: "No Content"}}}
        },
        "/users/{user_id}" => %{
          get: %{summary: "사용자 조회", parameters: [path_user_id()], responses: responses(user_schema())},
          put: %{summary: "사용자 upsert", parameters: [path_user_id()], requestBody: json_body(upsert_schema()), responses: responses(user_schema())}
        },
        "/users/search" => %{
          get: %{
            summary: "사용자 검색",
            parameters: [
              %{in: "query", name: "name", schema: %{type: "string"}},
              %{in: "query", name: "nickname", schema: %{type: "string"}},
              %{in: "query", name: "major", schema: %{type: "string"}},
              %{in: "query", name: "student_role", schema: %{type: "string"}},
              %{in: "query", name: "status", schema: %{type: "string"}},
              %{in: "query", name: "role", schema: %{type: "string"}},
              %{in: "query", name: "page", schema: %{type: "integer", default: 1}},
              %{in: "query", name: "page_size", schema: %{type: "integer", default: 20}},
              %{in: "query", name: "sort_by", schema: %{type: "string", default: "id"}},
              %{in: "query", name: "sort_order", schema: %{type: "string", enum: ["asc", "desc"], default: "asc"}}
            ],
            responses: responses(search_schema())
          }
        }
      }
    }
  end

  def swagger_ui_html do
    """
    <!doctype html>
    <html lang="ko">
    <head>
      <meta charset="utf-8" />
      <meta name="viewport" content="width=device-width, initial-scale=1" />
      <title>cowork-user Swagger UI</title>
      <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css" />
    </head>
    <body>
      <div id="swagger-ui"></div>
      <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
      <script>
        window.ui = SwaggerUIBundle({
          url: "/v3/api-docs",
          dom_id: "#swagger-ui"
        });
      </script>
    </body>
    </html>
    """
  end

  defp responses(schema) do
    %{
      "200" => %{
        description: "OK",
        content: %{"application/json" => %{schema: schema}}
      }
    }
  end

  defp json_body(schema) do
    %{
      required: true,
      content: %{"application/json" => %{schema: schema}}
    }
  end

  defp path_user_id do
    %{in: "path", name: "user_id", required: true, schema: %{type: "integer"}}
  end

  defp update_profile_schema do
    %{
      type: "object",
      properties: %{
        nickname: %{type: "string"},
        description: %{type: "string"},
        roles: %{type: "array", items: %{type: "string"}}
      }
    }
  end

  defp upsert_schema do
    %{
      type: "object",
      required: ["name", "email", "sex", "major", "role"],
      properties: %{
        name: %{type: "string"},
        email: %{type: "string"},
        sex: %{type: "string"},
        grade: %{type: "integer"},
        class_number: %{type: "integer"},
        student_number_in_class: %{type: "integer"},
        major: %{type: "string"},
        role: %{type: "string"},
        github_id: %{type: "string"}
      }
    }
  end

  defp search_schema do
    %{
      type: "object",
      properties: %{
        items: %{type: "array", items: user_schema()},
        page: %{type: "integer"},
        page_size: %{type: "integer"},
        total_count: %{type: "integer"},
        has_next: %{type: "boolean"}
      }
    }
  end

  defp user_schema do
    %{
      type: "object",
      properties: %{
        id: %{type: "integer"},
        name: %{type: "string"},
        email: %{type: "string"},
        sex: %{type: "string"},
        github_id: %{type: "string", nullable: true},
        account_description: %{type: "string", nullable: true},
        student_role: %{type: "string", nullable: true},
        student_number: %{type: "string", nullable: true},
        major: %{type: "string", nullable: true},
        specialty: %{type: "string", nullable: true},
        status: %{type: "string"},
        profile_image_url: %{type: "string", nullable: true},
        nickname: %{type: "string", nullable: true},
        roles: %{type: "array", items: %{type: "string"}},
        description: %{type: "string", nullable: true}
      }
    }
  end
end
