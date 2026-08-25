defmodule CoworkUser.OpenAPITest do
  use ExUnit.Case, async: true

  alias CoworkUser.OpenAPI

  test "Gateway canonical server와 공개 user operation만 문서화한다" do
    spec = OpenAPI.spec()

    assert spec.servers == [%{url: "/api/user", description: "Gateway"}]
    assert spec.security == [%{"BearerAuth" => []}]
    assert get_in(spec, [:components, :securitySchemes, "BearerAuth", :scheme]) == "bearer"
    assert get_in(spec, [:paths, "/users/batch", :get, :summary])
    assert get_in(spec, [:paths, "/users/{user_id}", :get, :summary])
    refute get_in(spec, [:paths, "/users/{user_id}", :put])
    refute Map.has_key?(spec.paths, "/users/{user_id}/status")
  end
end
