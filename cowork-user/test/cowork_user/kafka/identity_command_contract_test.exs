defmodule CoworkUser.Kafka.IdentityCommandContractTest do
  use ExUnit.Case, async: true

  alias CoworkUser.Kafka.{IdentityCommand, IdentityCommandContract}

  test "accepts the complete v1 owner command and rejects key or field drift" do
    operation_id = "00000000-0000-4000-8000-000000000001"

    payload = %{
      "schemaVersion" => 1,
      "operationId" => operation_id,
      "idempotencyKey" => operation_id,
      "commandType" => "UPSERT",
      "userId" => 7,
      "name" => "User",
      "email" => "user@example.com",
      "sex" => "MAN",
      "grade" => 1,
      "classNumber" => 2,
      "studentNumberInClass" => 3,
      "major" => "SW_DEVELOPMENT",
      "role" => "GENERAL_STUDENT",
      "githubId" => nil,
      "dataGSMStudentId" => 70,
      "requestedBy" => 7,
      "occurredAt" => "2026-08-27T01:02:03.000000Z"
    }

    assert {:ok, command} = IdentityCommandContract.parse(payload, "7")
    assert command.user_id == 7
    assert command.student_number_in_class == 3

    assert {:error, :invalid_user_identity_command} =
             IdentityCommandContract.parse(payload, "8")

    assert {:error, :invalid_user_identity_command} =
             IdentityCommandContract.parse(Map.put(payload, "legacyField", true), "7")
  end

  test "recovers only a canonical operation id from an otherwise invalid command" do
    operation_id = "abcdefab-cdef-4abc-8def-abcdefabcdef"

    assert {:ok, ^operation_id} =
             IdentityCommandContract.correlation_operation_id(%{
               "operationId" => operation_id,
               "schemaVersion" => 999
             })

    assert :error =
             IdentityCommandContract.correlation_operation_id(%{
               "operationId" => String.upcase(operation_id)
             })

    assert :error = IdentityCommandContract.correlation_operation_id(%{})
    assert :error = IdentityCommandContract.correlation_operation_id("not-an-object")
  end

  test "classifies malformed commands as terminal with the recoverable operation id" do
    operation_id = "abcdefab-cdef-4abc-8def-abcdefabcdef"
    malformed = %{"operationId" => operation_id, "schemaVersion" => 999}

    assert {:reject, ^operation_id, :invalid_user_identity_command} =
             IdentityCommand.process(malformed, "7", "user.identity.command-result")

    assert {:reject, nil, :invalid_user_identity_command} =
             IdentityCommand.process(%{"schemaVersion" => 999}, "7", "result-topic")
  end
end
