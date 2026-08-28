defmodule CoworkUser.Kafka.ProducerTest do
  use ExUnit.Case, async: false

  alias CoworkUser.Kafka.Producer

  defmodule FakeBroker do
    use Agent

    def start_link(opts) do
      Agent.start_link(fn -> Map.new(opts) end, name: __MODULE__)
    end

    def ensure_started do
      {:ok, []}
    end

    def start_client(_endpoints, _client_id, _options) do
      pop_result(:start_results, :ok, :start_client)
    end

    def produce_sync(_client_id, topic, :hash, key, value) do
      result = pop_result(:produce_results, :ok, {:produce, topic, key, value})
      result
    end

    def produce_sync(_client_id, topic, partition, key, value) when is_integer(partition) do
      result =
        pop_result(
          :produce_results,
          :ok,
          {:produce_partition, topic, partition, key, value}
        )

      result
    end

    def get_partitions_count(_client_id, topic) do
      Agent.get(__MODULE__, fn state ->
        send(state.owner, {:partition_count, topic})
        {:ok, Map.get(state, :partition_count, 1)}
      end)
    end

    def stop_client(_client_id), do: :ok

    defp pop_result(result_key, default, notification) do
      Agent.get_and_update(__MODULE__, fn state ->
        {result, remaining} = pop(Map.get(state, result_key, []), default)
        send(state.owner, {notification, result})
        {result, Map.put(state, result_key, remaining)}
      end)
    end

    defp pop([result | remaining], _default), do: {result, remaining}
    defp pop([], default), do: {default, []}
  end

  setup do
    on_exit(fn ->
      if pid = Process.whereis(Producer), do: GenServer.stop(pid)
    end)

    :ok
  end

  test "startup client 연결 실패 뒤 백오프로 재연결하고 이벤트를 발행한다" do
    start_supervised!(
      {FakeBroker,
       owner: self(), start_results: [{:error, :broker_unavailable}, :ok], produce_results: [:ok]}
    )

    start_supervised!(
      {Producer,
       config: enabled_config(),
       broker: FakeBroker,
       initial_backoff_ms: 5,
       max_backoff_ms: 10,
       retry_base_ms: 5}
    )

    assert_receive {:start_client, {:error, :broker_unavailable}}
    assert_receive {:start_client, :ok}

    assert :ok = Producer.publish("user.profile.event", 42, %{eventType: "UPSERT"})

    assert_receive {{:produce, "user.profile.event", "42", encoded}, :ok}
    assert Jason.decode!(encoded) == %{"eventType" => "UPSERT"}
  end

  test "실행 중 client down을 감지하면 client를 재시작하고 발행을 재시도한다" do
    start_supervised!(
      {FakeBroker,
       owner: self(), start_results: [:ok, :ok], produce_results: [{:error, :client_down}, :ok]}
    )

    producer =
      start_supervised!(
        {Producer,
         config: enabled_config(),
         broker: FakeBroker,
         initial_backoff_ms: 5,
         max_backoff_ms: 10,
         retry_base_ms: 5}
      )

    assert_receive {:start_client, :ok}
    assert :ok = Producer.publish("user.profile.event", 42, %{eventType: "DELETE"})
    assert_receive {{:produce, "user.profile.event", "42", _encoded}, {:error, :client_down}}

    assert_receive {:start_client, :ok}
    assert_receive {{:produce, "user.profile.event", "42", _encoded}, :ok}
    assert Process.alive?(producer)
  end

  test "연결 직후 broker client가 종료돼도 감시하여 재연결한다" do
    client =
      spawn(fn ->
        receive do
          :stop -> :ok
        end
      end)

    Process.register(client, :cowork_user_profile_producer)

    start_supervised!({FakeBroker, owner: self(), start_results: [:ok, :ok]})

    start_supervised!(
      {Producer,
       config: enabled_config(),
       broker: FakeBroker,
       initial_backoff_ms: 5,
       max_backoff_ms: 10,
       retry_base_ms: 5}
    )

    assert_receive {:start_client, :ok}
    assert is_reference(:sys.get_state(Producer).client_ref)
    send(client, :stop)
    assert_receive {:start_client, :ok}
  end

  test "장기 발행 오류도 횟수 제한으로 유실하지 않고 성공할 때까지 재시도한다" do
    failures = List.duplicate({:error, :timeout}, 10)

    start_supervised!(
      {FakeBroker, owner: self(), start_results: [:ok], produce_results: failures ++ [:ok]}
    )

    start_supervised!(
      {Producer,
       config: enabled_config(),
       broker: FakeBroker,
       initial_backoff_ms: 5,
       max_backoff_ms: 10,
       retry_base_ms: 1}
    )

    assert_receive {:start_client, :ok}
    assert :ok = Producer.publish("user.profile.event", 42, %{eventType: "DELETE"})

    Enum.each(1..10, fn _ ->
      assert_receive {{:produce, "user.profile.event", "42", _encoded}, {:error, :timeout}}, 500
    end)

    assert_receive {{:produce, "user.profile.event", "42", _encoded}, :ok}, 500
  end

  test "outbox 동기 발행은 저장된 JSON을 다시 인코딩하지 않고 broker 확인을 반환한다" do
    start_supervised!({FakeBroker, owner: self(), start_results: [:ok], produce_results: [:ok]})

    start_supervised!(
      {Producer,
       config: enabled_config(),
       broker: FakeBroker,
       initial_backoff_ms: 5,
       max_backoff_ms: 10,
       retry_base_ms: 5}
    )

    assert_receive {:start_client, :ok}
    encoded = ~s({"eventType":"UPSERT","userId":42})

    assert :ok = Producer.publish_encoded_sync("user.profile.event", 42, encoded)
    assert_receive {{:produce, "user.profile.event", "42", ^encoded}, :ok}
  end

  test "snapshot marker는 요청한 partition에 직접 발행하고 partition 수를 조회한다" do
    start_supervised!(
      {FakeBroker,
       owner: self(), start_results: [:ok], produce_results: [:ok], partition_count: 3}
    )

    start_supervised!(
      {Producer,
       config: enabled_config(),
       broker: FakeBroker,
       initial_backoff_ms: 5,
       max_backoff_ms: 10,
       retry_base_ms: 5}
    )

    assert_receive {:start_client, :ok}
    assert {:ok, 3} = Producer.partition_count("user.profile.event")
    assert_receive {:partition_count, "user.profile.event"}

    encoded = ~s({"eventType":"PROJECTION_SNAPSHOT_COMPLETED","partition":2})

    assert :ok =
             Producer.publish_encoded_sync(
               "user.profile.event",
               "__cowork_projection_snapshot_complete__:2",
               encoded,
               2
             )

    assert_receive {{:produce_partition, "user.profile.event", 2,
                     "__cowork_projection_snapshot_complete__:2", ^encoded}, :ok}
  end

  defp enabled_config do
    %{kafka_enabled: true, kafka_bootstrap_servers: "kafka:9092"}
  end
end
