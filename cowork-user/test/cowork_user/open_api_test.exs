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

  test "team-scoped search query와 Gateway error envelope 계약을 문서화한다" do
    operation = OpenAPI.spec().paths["/users/search"].get
    parameters = Map.new(operation.parameters, &{&1.name, &1})

    assert parameters["teamId"].schema == %{type: "integer", minimum: 1}
    assert parameters["q"].schema.type == "string"
    assert parameters["query"].schema.type == "string"
    assert parameters["status"].schema.enum == ["online", "offline"]

    Enum.each([{"400", 400}, {"403", 403}, {"503", 503}], fn {key, code} ->
      schema = operation.responses[key].content["application/json"].schema
      assert schema.required == ["status", "code", "message"]
      assert schema.properties.code.enum == [code]
    end)
  end
end
