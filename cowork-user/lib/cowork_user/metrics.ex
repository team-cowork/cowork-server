defmodule CoworkUser.Metrics do
  alias CoworkUser.Metrics.Store

  def record(uri, status, duration_native), do: Store.record(uri, status, duration_native)

  def render_prometheus do
    http_metrics =
      Store.snapshot()
      |> Enum.map(fn {{uri, status}, values} ->
        [
          ~s(http_server_requests_seconds_count{application="cowork-user",uri="#{uri}",status="#{status}"} #{values.count}),
          ~s(http_server_requests_seconds_sum{application="cowork-user",uri="#{uri}",status="#{status}"} #{Float.round(values.sum_seconds, 6)})
        ]
      end)
      |> List.flatten()

    beam_metrics = [
      ~s(beam_memory_used_bytes{application="cowork-user"} #{:erlang.memory(:total)}),
      ~s(beam_process_count{application="cowork-user"} #{:erlang.system_info(:process_count)}),
      ~s(beam_run_queue{application="cowork-user"} #{:erlang.statistics(:run_queue)})
    ]

    Enum.join(
      [
        "# TYPE http_server_requests_seconds_count counter",
        "# TYPE http_server_requests_seconds_sum counter"
        | http_metrics ++ beam_metrics
      ],
      "\n"
    ) <> "\n"
  end
end
