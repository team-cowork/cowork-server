defmodule CoworkUser.OpenAPI do
  alias CoworkUser.Accounts.Account

  def spec do
    %{
      openapi: "3.0.3",
      info: %{
        title: "cowork-user API",
        version: "v1",
        description: "cowork-user 사용자 관리 서비스 API"
      },
      servers: [%{url: "/api/user", description: "Gateway"}],
      security: [%{"BearerAuth" => []}],
      paths: %{
        "/users/me" => %{
          get: %{summary: "내 프로필 조회", responses: responses(user_schema())},
          patch: %{
            summary: "내 프로필 수정",
            requestBody: json_body(update_profile_schema()),
            responses: responses(user_schema())
          }
        },
        "/users/me/status" => %{
          patch: %{
            summary: "커스텀 상태 및 상태 메시지 설정",
            requestBody: json_body(update_status_schema()),
            responses: responses(user_schema())
          }
        },
        "/users/me/profile-image/presigned" => %{
          post: %{
            summary: "프로필 이미지 업로드 URL 발급",
            requestBody:
              json_body(%{
                type: "object",
                required: ["content_type"],
                properties: %{content_type: %{type: "string"}}
              }),
            responses:
              responses(%{
                type: "object",
                properties: %{upload_url: %{type: "string"}, object_key: %{type: "string"}}
              })
          }
        },
        "/users/me/profile-image/confirm" => %{
          post: %{
            summary: "프로필 이미지 업로드 확인",
            requestBody:
              json_body(%{
                type: "object",
                required: ["object_key"],
                properties: %{object_key: %{type: "string"}}
              }),
            responses: %{"200" => %{description: "OK"}}
          }
        },
        "/users/me/profile-image" => %{
          delete: %{summary: "프로필 이미지 삭제", responses: %{"204" => %{description: "No Content"}}}
        },
        "/users/{user_id}" => %{
          get: %{
            summary: "사용자 조회",
            parameters: [path_user_id()],
            responses: responses(user_schema())
          }
        },
        "/users/batch" => %{
          get: %{
            summary: "사용자 표시 이름 일괄 조회",
            parameters: [
              %{
                in: "query",
                name: "ids",
                required: true,
                schema: %{type: "string"},
                description: "쉼표로 구분한 사용자 ID"
              }
            ],
            responses:
              responses(%{
                type: "object",
                properties: %{
                  users: %{type: "array", items: display_name_schema()}
                }
              })
          }
        },
        "/users/search" => %{
          get: %{
            summary: "사용자 검색",
            parameters: [
              %{
                in: "query",
                name: "teamId",
                schema: %{type: "integer", minimum: 1},
                description: "활성 멤버인 팀의 사용자로 검색 범위를 제한"
              },
              %{in: "query", name: "q", schema: %{type: "string"}},
              %{
                in: "query",
                name: "query",
                schema: %{type: "string"},
                description: "q의 호환 alias"
              },
              %{in: "query", name: "name", schema: %{type: "string"}},
              %{in: "query", name: "nickname", schema: %{type: "string"}},
              %{in: "query", name: "major", schema: %{type: "string"}},
              %{in: "query", name: "student_role", schema: %{type: "string"}},
              %{
                in: "query",
                name: "status",
                schema: %{type: "string", enum: ["online", "offline"]}
              },
              %{
                in: "query",
                name: "custom_status",
                schema: %{type: "string", maxLength: Account.custom_status_max_length()}
              },
              %{in: "query", name: "role", schema: %{type: "string"}},
              %{in: "query", name: "page", schema: %{type: "integer", default: 1}},
              %{in: "query", name: "page_size", schema: %{type: "integer", default: 20}},
              %{in: "query", name: "sort_by", schema: %{type: "string", default: "id"}},
              %{
                in: "query",
                name: "sort_order",
                schema: %{type: "string", enum: ["asc", "desc"], default: "asc"}
              }
            ],
            responses:
              responses(search_schema())
              |> Map.merge(%{
                "400" => gateway_error_response(400, "BAD_REQUEST", "잘못된 검색 조건"),
                "403" => gateway_error_response(403, "FORBIDDEN", "팀 멤버가 아님"),
                "503" => gateway_error_response(503, "SERVICE_UNAVAILABLE", "팀 투영 동기화/조회 실패")
              })
          }
        }
      },
      components: %{
        securitySchemes: %{
          "BearerAuth" => %{
            type: "http",
            scheme: "bearer",
            bearerFormat: "JWT",
            description: "Gateway에서 검증할 Cowork JWT"
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
        content: %{"application/json" => %{schema: common_api_response_schema(schema)}}
      }
    }
  end

  defp common_api_response_schema(data_schema) do
    %{
      type: "object",
      required: ["status", "code", "message", "data"],
      properties: %{
        status: %{type: "string", enum: ["OK"], example: "OK"},
        code: %{type: "integer", enum: [200], example: 200},
        message: %{type: "string", example: "OK"},
        data: data_schema
      }
    }
  end

  defp gateway_error_response(code, status, description) do
    %{
      description: description,
      content: %{
        "application/json" => %{
          schema: %{
            type: "object",
            required: ["status", "code", "message"],
            properties: %{
              status: %{type: "string", enum: [status]},
              code: %{type: "integer", enum: [code]},
              message: %{type: "string"}
            }
          }
        }
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
        name: %{type: "string"},
        description: %{type: "string"},
        github_id: %{type: "string", nullable: true},
        roles: %{type: "array", items: %{type: "string"}}
      }
    }
  end

  defp update_status_schema do
    %{
      type: "object",
      required: ["custom_status"],
      properties: %{
        custom_status: %{
          type: "string",
          maxLength: Account.custom_status_max_length(),
          example: "DO_NOT_DISTURB"
        },
        message: %{
          type: "string",
          maxLength: Account.status_message_max_length(),
          nullable: true,
          example: "집중 중"
        },
        expiresAt: %{
          type: "string",
          format: "date-time",
          nullable: true,
          example: "2026-05-26T18:00:00Z"
        }
      }
    }
  end

  defp display_name_schema do
    %{
      type: "object",
      properties: %{
        id: %{type: "integer"},
        name: %{type: "string"},
        nickname: %{type: "string", nullable: true}
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
        status: %{type: "string", enum: ["online", "offline"]},
        custom_status: %{
          type: "string",
          maxLength: Account.custom_status_max_length(),
          nullable: true,
          example: "DO_NOT_DISTURB"
        },
        status_message: %{
          type: "string",
          maxLength: Account.status_message_max_length(),
          nullable: true
        },
        status_expires_at: %{type: "string", format: "date-time", nullable: true},
        profile_image_url: %{type: "string", nullable: true},
        nickname: %{type: "string", nullable: true},
        roles: %{type: "array", items: %{type: "string"}},
        description: %{type: "string", nullable: true}
      }
    }
  end
end
