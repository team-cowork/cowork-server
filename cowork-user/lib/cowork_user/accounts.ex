defmodule CoworkUser.Accounts do
  import Ecto.Query
  require Logger

  alias CoworkUser.Accounts.{Account, Profile, ProfileRole}
  alias CoworkUser.Kafka.{PresenceProjection, ProfileEventPublisher}
  alias CoworkUser.Repo
  alias CoworkUser.Storage.ObjectStorage
  alias CoworkUser.TeamMembershipProjection

  @initial_presence_updated_at ~U[1970-01-01 00:00:00.000000Z]

  def get_my_profile(user_id), do: get_user_profile(user_id)

  def get_user_profile(user_id) do
    case load_profile(user_id) do
      nil -> {:error, :not_found}
      profile -> {:ok, to_user_response(profile)}
    end
  end

  @doc """
  GitHub 로그인명(`tb_accounts.github`, 유니크)으로 cowork 사용자를 역조회한다.

  cowork-project의 GitHub 이슈/PR 댓글 알림에서, 댓글이 달린 이슈/PR의
  GitHub 작성자가 cowork 사용자이면 그 사람에게 알림을 보내기 위해 사용한다.
  """
  def get_by_github(username) do
    case Repo.get_by(Account, github: username) do
      nil -> {:error, :not_found}
      account -> get_user_profile(account.id)
    end
  end

  @display_name_cache_ttl_seconds 60
  @display_name_not_found_ttl_seconds 30
  @not_found_marker "__not_found__"
  @max_raw_csv_tokens 1000

  @doc """
  여러 사용자의 표시 이름(name/nickname)만 일괄 조회한다.

  N개의 user_id에 대해 N번 `GET /users/:id`를 호출하던 N+1 패턴(예: chat 서비스의
  파일 업로더 이름 조회)을 대체하기 위한 배치 조회 API.
  `to_user_response/1`(전체 프로필 + profile_roles preload + 프로필 이미지 presigned URL 생성)을
  N명분 재사용하면 배치 호출 1번이 오히려 더 무거워지므로, JOIN 1번으로 필요한 컬럼만 조회한다.

  Redis에 `#{@display_name_cache_ttl_seconds}`초 TTL로 캐시한다. 이름/닉네임은
  자주 바뀌지 않고 반복 조회가 많은 데이터라 캐시 적중률이 높다. Redis 조회가 실패해도
  (연결 끊김 등) 전체를 캐시 미스로 간주해 DB로 폴백한다.

  DB에 존재하지 않는 user_id는 `#{@display_name_not_found_ttl_seconds}`초 TTL로 별도
  마킹해 캐시한다(negative caching). 존재하지 않는 id가 반복 조회되는 캐시 관통을 막기
  위함이며, 실존 데이터보다 짧은 TTL을 둬서 새로 생성된 사용자가 오래 숨겨지지 않게 한다.
  """
  def get_display_names(ids) when is_list(ids) do
    cached = fetch_cached_display_names(ids)
    missing_ids = Enum.reject(ids, &Map.has_key?(cached, &1))

    fresh = query_display_names(missing_ids)
    found_ids = MapSet.new(fresh, & &1.id)
    not_found_ids = Enum.reject(missing_ids, &MapSet.member?(found_ids, &1))

    cache_display_names(fresh)
    cache_not_found(not_found_ids)

    results = cached |> Map.values() |> Enum.reject(&(&1 == :not_found))
    {:ok, results ++ fresh}
  end

  def update_my_profile(user_id, attrs) do
    roles = attrs["roles"] |> normalize_roles()
    account_update_attrs = build_profile_account_attrs(attrs, user_id)

    Repo.transaction(fn ->
      with %Account{} = account <- lock_account(user_id),
           %Profile{} = profile <- lock_profile(user_id),
           {:ok, _profile} <-
             profile
             |> Profile.changeset(%{
               nickname: Map.get(attrs, "nickname"),
               description: Map.get(attrs, "description"),
               last_modified_by: user_id
             })
             |> Repo.update(),
           :ok <- replace_roles_in_transaction(profile.id, roles),
           {:ok, _account} <- maybe_update_profile_account(account, account_update_attrs) do
        ProfileEventPublisher.enqueue_current_upsert!(Repo, user_id)
        :ok
      else
        nil -> Repo.rollback(:not_found)
        {:error, %Ecto.Changeset{} = changeset} -> Repo.rollback({:validation, changeset})
      end
    end)
    |> case do
      {:ok, :ok} ->
        get_my_profile(user_id)

      {:error, :not_found} ->
        {:error, :not_found}

      {:error, {:validation, changeset}} ->
        {:error, {:validation, format_changeset_errors(changeset)}}
    end
  end

  defp maybe_update_profile_account(account, attrs) when map_size(attrs) > 1 do
    account
    |> Account.profile_update_changeset(attrs)
    |> Repo.update()
  end

  defp maybe_update_profile_account(account, _attrs), do: {:ok, account}

  defp replace_roles_in_transaction(profile_id, roles) do
    Repo.delete_all(from(pr in ProfileRole, where: pr.profile_id == ^profile_id))

    if roles != [] do
      Repo.insert_all(ProfileRole, Enum.map(roles, &%{profile_id: profile_id, role: &1}))
    end

    :ok
  end

  defp lock_account(user_id) do
    Account
    |> where([account], account.id == ^user_id)
    |> lock("FOR UPDATE")
    |> Repo.one()
  end

  defp lock_profile(user_id) do
    Profile
    |> where([profile], profile.account_id == ^user_id)
    |> lock("FOR UPDATE")
    |> Repo.one()
  end

  def update_my_status(user_id, attrs) do
    if Map.has_key?(attrs, "custom_status") do
      case load_profile(user_id) do
        nil ->
          {:error, :not_found}

        profile ->
          status_attrs = custom_status_attrs(user_id, attrs)

          profile.account
          |> Account.custom_status_changeset(status_attrs)
          |> Repo.update()
          |> case do
            {:ok, _} -> get_my_profile(user_id)
            {:error, changeset} -> {:error, {:validation, format_changeset_errors(changeset)}}
          end
      end
    else
      {:error, {:validation, "custom_status 필드는 필수입니다."}}
    end
  end

  @doc false
  def custom_status_attrs(user_id, attrs) do
    Enum.reduce(
      [
        {"custom_status", :custom_status},
        {"message", :status_message},
        {"expiresAt", :status_expires_at}
      ],
      %{last_modified_by: user_id},
      fn {key, field}, acc ->
        if Map.has_key?(attrs, key), do: Map.put(acc, field, attrs[key]), else: acc
      end
    )
  end

  def generate_presigned_url(user_id, %{"content_type" => content_type}) do
    object_key = ObjectStorage.build_object_key(user_id, content_type)

    with %Profile{} <- load_profile(user_id),
         :ok <- ObjectStorage.validate_content_type(content_type),
         {:ok, upload_url} <- ObjectStorage.presigned_put_url(object_key, content_type) do
      {:ok,
       %{
         upload_url: upload_url,
         object_key: object_key
       }}
    else
      nil -> {:error, :not_found}
      {:error, reason} -> {:error, {:validation, reason}}
    end
  end

  def generate_presigned_url(_user_id, _attrs),
    do: {:error, {:validation, "content_type 값이 필요합니다."}}

  def confirm_upload(user_id, %{"object_key" => object_key}) do
    with %Profile{} = profile <- load_profile(user_id),
         :ok <- ObjectStorage.verify_upload(user_id, object_key),
         previous_key = profile.profile_image_key,
         {:ok, _} <-
           profile
           |> Profile.changeset(%{profile_image_key: object_key, last_modified_by: user_id})
           |> Repo.update() do
      if previous_key && previous_key != object_key, do: ObjectStorage.delete_object(previous_key)
      :ok
    else
      nil -> {:error, :not_found}
      {:error, reason} -> {:error, reason}
    end
  end

  def confirm_upload(_user_id, _attrs), do: {:error, {:validation, "object_key 값이 필요합니다."}}

  def delete_profile_image(user_id) do
    with %Profile{} = profile <- load_profile(user_id),
         previous_key = profile.profile_image_key,
         {:ok, _} <-
           profile
           |> Profile.changeset(%{profile_image_key: nil, last_modified_by: user_id})
           |> Repo.update() do
      if previous_key, do: ObjectStorage.delete_object(previous_key)
      :ok
    else
      nil -> {:error, :not_found}
      {:error, reason} -> {:error, reason}
    end
  end

  def upsert_user(user_id, attrs) do
    with :ok <- validate_upsert(attrs) do
      Repo.transaction(fn ->
        existing = lock_account(user_id)

        upsert_attrs =
          Map.merge(account_attrs(user_id, attrs), presence_attrs_for_upsert(existing))

        account =
          existing
          |> case do
            nil ->
              %Account{}
              |> Account.changeset(upsert_attrs)
              |> Repo.insert!()

            existing ->
              existing
              |> Account.changeset(upsert_attrs)
              |> Repo.update!()
          end

        profile =
          lock_profile(user_id)
          |> case do
            nil ->
              %Profile{}
              |> Profile.changeset(%{account_id: account.id})
              |> Repo.insert!()

            profile ->
              profile
          end

        ProfileEventPublisher.enqueue_current_upsert!(Repo, user_id)
        profile
      end)
      |> case do
        {:ok, _} ->
          reconcile_presence_and_get_profile(user_id)

        {:error, %Ecto.Changeset{} = changeset} ->
          {:error, {:validation, format_changeset_errors(changeset)}}

        {:error, reason} ->
          {:error, {:validation, inspect(reason)}}
      end
    end
  end

  def upsert_user_from_sync_event(event) when is_map(event) do
    user_id = Map.get(event, "user_id") || Map.get(event, "userId")

    attrs = %{
      "name" => Map.get(event, "name"),
      "email" => Map.get(event, "email"),
      "sex" => Map.get(event, "sex"),
      "github_id" => Map.get(event, "github_id") || Map.get(event, "github"),
      "student_role" =>
        Map.get(event, "student_role") || Map.get(event, "st_role") || Map.get(event, "stRole"),
      "student_number" =>
        Map.get(event, "student_number") || Map.get(event, "st_num") || Map.get(event, "stNum"),
      "datagsm_student_id" => Map.get(event, "datagsm_student_id"),
      "major" => Map.get(event, "major"),
      "role" => Map.get(event, "role"),
      "specialty" => Map.get(event, "specialty") || Map.get(event, "spe"),
      "account_description" =>
        Map.get(event, "account_description") || Map.get(event, "description")
    }

    Repo.transaction(fn ->
      existing = lock_account(user_id)

      upsert_attrs =
        account_attrs(user_id, attrs)
        |> Map.merge(presence_attrs_for_upsert(existing))
        |> Map.put(:description, Map.get(attrs, "account_description"))

      account =
        existing
        |> case do
          nil ->
            %Account{}
            |> Account.changeset(upsert_attrs)
            |> Repo.insert!()

          existing ->
            existing
            |> Account.changeset(upsert_attrs)
            |> Repo.update!()
        end

      profile =
        lock_profile(account.id) ||
          Repo.insert!(Profile.changeset(%Profile{}, %{account_id: account.id}))

      ProfileEventPublisher.enqueue_current_upsert!(Repo, user_id)
      profile
    end)
    |> case do
      {:ok, _profile} ->
        reconcile_presence_and_get_profile(user_id)

      error ->
        error
    end
  rescue
    exception in [Ecto.InvalidChangesetError] ->
      {:error, {:validation, format_changeset_errors(exception.changeset)}}

    exception in [Ecto.ConstraintError] ->
      {:error, {:validation, Exception.message(exception)}}

    exception ->
      {:error, {:transient, Exception.message(exception)}}
  end

  @doc """
  DataGSM webhook에서 전달된 student.updated 이벤트를 반영한다.

  전체 upsert(`upsert_user_from_sync_event/1`)와 달리 불변인 `datagsm_student_id`로 기존
  account를 찾아 학생 정보를 부분 갱신한다. 이메일은 변경될 수 있어 조인 키로 쓰지 않는다.
  접속 상태(status)는 Cowork 내부 상태이므로 보존한다.
  cowork에 아직 가입(로그인)하지 않은 사용자(account 없음)는 갱신 대상이 없으므로 skip한다.
  """
  def apply_student_event(%{"datagsm_student_id" => student_id} = event)
      when is_integer(student_id) do
    student_role = Map.get(event, "student_role") || Map.get(event, "role")

    with false <- blank?(student_role),
         {:ok, occurred_at} <- parse_student_event_time(event) do
      Repo.transaction(fn ->
        account =
          Account
          |> where([account], account.datagsm_student_id == ^student_id)
          |> lock("FOR UPDATE")
          |> Repo.one()

        cond do
          is_nil(account) ->
            {:skip, :account_not_found}

          not student_event_newer?(account.datagsm_updated_at, occurred_at) ->
            {:skip, :stale_student_event}

          true ->
            attrs =
              event
              |> Map.put("student_role", student_role)
              |> student_event_account_attrs()
              |> Map.put(:datagsm_updated_at, occurred_at)

            case account |> Account.datagsm_sync_changeset(attrs) |> Repo.update() do
              {:ok, updated} ->
                ProfileEventPublisher.enqueue_current_upsert!(Repo, updated.id)
                {:updated, updated.id}

              {:error, changeset} ->
                Repo.rollback({:validation, changeset})
            end
        end
      end)
      |> case do
        {:ok, {:updated, _account_id}} ->
          :ok

        {:ok, {:skip, reason}} ->
          {:skip, reason}

        {:error, {:validation, changeset}} ->
          {:error, {:validation, format_changeset_errors(changeset)}}

        {:error, reason} ->
          {:error, {:transient, inspect(reason)}}
      end
    else
      true -> {:error, :invalid_student_event}
      {:error, :invalid_occurred_at} -> {:error, :invalid_student_event}
    end
  rescue
    exception in [Ecto.ConstraintError] -> {:error, {:validation, Exception.message(exception)}}
    exception -> {:error, {:transient, Exception.message(exception)}}
  end

  def apply_student_event(_event), do: {:error, :invalid_student_event}

  @doc """
  DataGSM 상태 이벤트의 last-write-wins 적용 여부를 결정한다.

  동일 시각 이벤트는 재전달일 수 있으므로 적용하지 않으며, 저장된 시각이 없는
  기존 계정에는 첫 상태 이벤트를 적용한다.
  """
  def student_event_newer?(nil, %DateTime{}), do: true

  def student_event_newer?(%DateTime{} = current, %DateTime{} = incoming) do
    DateTime.compare(incoming, current) == :gt
  end

  def student_event_newer?(_, _), do: false

  def search_users(requester_user_id, params) do
    with :ok <- validate_search_filters(params),
         {:ok, params} <- authorize_team_search(requester_user_id, params) do
      page = parse_positive_int(Map.get(params, "page"), 1)
      page_size = parse_positive_int(Map.get(params, "page_size"), 20) |> min(100)
      sort_by = Map.get(params, "sort_by", "id")
      sort_order = Map.get(params, "sort_order", "asc")

      base_query = profile_with_account_query()

      filtered_query =
        base_query
        |> maybe_like(:name, Map.get(params, "name"), :account)
        |> maybe_like(:nickname, Map.get(params, "nickname"), :profile)
        |> maybe_equals(:major, Map.get(params, "major"), :account)
        |> maybe_equals(:student_role, Map.get(params, "student_role"), :account)
        |> maybe_equals(:status, Map.get(params, "status"), :account)
        |> maybe_equals(:custom_status, Map.get(params, "custom_status"), :account)
        |> maybe_role(Map.get(params, "role"))
        |> maybe_query(Map.get(params, "q") || Map.get(params, "query"))
        |> maybe_user_ids(Map.get(params, "user_ids"))

      total_count =
        filtered_query
        |> exclude(:preload)
        |> exclude(:order_by)
        |> distinct(true)
        |> Repo.aggregate(:count, :id)

      items =
        filtered_query
        |> order_by(^sort_clause(sort_by, sort_order))
        |> limit(^(page_size + 1))
        |> offset(^((page - 1) * page_size))
        |> Repo.all()
        |> Repo.preload([:account, :profile_roles])

      has_next = length(items) > page_size

      {:ok,
       %{
         items: items |> Enum.take(page_size) |> Enum.map(&to_user_response/1),
         page: page,
         page_size: page_size,
         total_count: total_count,
         has_next: has_next
       }}
    end
  end

  @doc false
  def validate_search_filters(params) do
    with :ok <- validate_presence_status_filter(Map.get(params, "status")),
         :ok <- validate_custom_status_filter(Map.get(params, "custom_status")) do
      :ok
    end
  end

  defp validate_presence_status_filter(status) do
    case status do
      nil -> :ok
      status when status in ["online", "offline"] -> :ok
      _invalid -> {:error, {:validation, "status 값은 online 또는 offline이어야 합니다."}}
    end
  end

  defp validate_custom_status_filter(nil), do: :ok

  defp validate_custom_status_filter(custom_status) when is_binary(custom_status) do
    if String.length(custom_status) <= Account.custom_status_max_length() do
      :ok
    else
      {:error, {:validation, "custom_status 값은 #{Account.custom_status_max_length()}자 이하여야 합니다."}}
    end
  end

  defp validate_custom_status_filter(_invalid) do
    {:error, {:validation, "custom_status 값은 문자열이어야 합니다."}}
  end

  @doc false
  def authorize_team_search(
        requester_user_id,
        params,
        projection \\ TeamMembershipProjection
      ) do
    case Map.get(params, "teamId") do
      nil ->
        {:ok, params}

      team_id ->
        case projection.member_ids_for_requester(team_id, requester_user_id) do
          {:ok, ids} ->
            {:ok, Map.put(params, "user_ids", ids)}

          {:error, :forbidden} ->
            {:error, :forbidden}

          {:error, :invalid_team_id} ->
            {:error, {:validation, "teamId 값이 올바르지 않습니다."}}

          {:error, :invalid_requester_user_id} ->
            {:error, {:validation, "X-User-Id 값이 올바르지 않습니다."}}

          {:error, reason} ->
            {:error, {:team_projection, reason}}
        end
    end
  end

  defp build_profile_account_attrs(attrs, user_id) do
    Enum.reduce([{"name", :name}, {"github_id", :github}], %{last_modified_by: user_id}, fn {key,
                                                                                             field},
                                                                                            acc ->
      if Map.has_key?(attrs, key) do
        Map.put(acc, field, attrs[key])
      else
        acc
      end
    end)
  end

  defp account_attrs(user_id, attrs) do
    %{
      id: user_id,
      name: Map.get(attrs, "name"),
      email: Map.get(attrs, "email"),
      sex: Map.get(attrs, "sex"),
      github: Map.get(attrs, "github_id"),
      description: Map.get(attrs, "account_description"),
      student_role: Map.get(attrs, "student_role") || Map.get(attrs, "role"),
      student_number:
        normalize_student_number(Map.get(attrs, "student_number")) || build_student_number(attrs),
      datagsm_student_id: Map.get(attrs, "datagsm_student_id"),
      major: Map.get(attrs, "major"),
      specialty: Map.get(attrs, "specialty")
    }
  end

  @doc false
  def presence_attrs_for_upsert(nil) do
    %{status: "offline", presence_updated_at: @initial_presence_updated_at}
  end

  def presence_attrs_for_upsert(%{status: status, presence_updated_at: occurred_at}) do
    %{status: status, presence_updated_at: occurred_at}
  end

  defp reconcile_presence_and_get_profile(user_id) do
    case PresenceProjection.reconcile_account(user_id) do
      :ok -> get_user_profile(user_id)
      {:error, reason} -> {:error, {:transient, inspect(reason)}}
    end
  end

  defp student_event_account_attrs(event) do
    mappings = [
      {"name", :name},
      {"email", :email},
      {"sex", :sex},
      {"github_id", :github},
      {"student_role", :student_role},
      {"major", :major},
      {"specialty", :specialty}
    ]

    Enum.reduce(mappings, %{}, fn {key, field}, acc ->
      if Map.has_key?(event, key) do
        Map.put(acc, field, Map.get(event, key))
      else
        acc
      end
    end)
    |> maybe_put_student_number(event)
  end

  defp parse_student_event_time(event) do
    with occurred_at when is_binary(occurred_at) <-
           Map.get(event, "occurred_at") || Map.get(event, "occurredAt"),
         {:ok, parsed, _offset} <- DateTime.from_iso8601(occurred_at) do
      {:ok, DateTime.truncate(parsed, :microsecond)}
    else
      _ -> {:error, :invalid_occurred_at}
    end
  end

  defp maybe_put_student_number(attrs, event) do
    cond do
      not is_nil(Map.get(event, "student_number")) ->
        Map.put(
          attrs,
          :student_number,
          normalize_student_number(Map.get(event, "student_number"))
        )

      has_student_number_parts?(event) ->
        Map.put(attrs, :student_number, build_student_number(event))

      Map.has_key?(event, "student_number") ->
        Map.put(attrs, :student_number, nil)

      true ->
        attrs
    end
  end

  defp build_student_number(attrs) do
    with {:ok, grade} <- get_int(attrs, "grade"),
         {:ok, class_number} <- get_int(attrs, "class_number", "class_num"),
         {:ok, student_number_in_class} <- get_int(attrs, "student_number_in_class", "number") do
      "#{grade}#{class_number}#{student_number_in_class |> Integer.to_string() |> String.pad_leading(2, "0")}"
    else
      _ -> nil
    end
  end

  defp has_student_number_parts?(event) do
    Map.has_key?(event, "grade") and
      (Map.has_key?(event, "class_number") or Map.has_key?(event, "class_num")) and
      (Map.has_key?(event, "student_number_in_class") or Map.has_key?(event, "number"))
  end

  defp normalize_student_number(nil), do: nil
  defp normalize_student_number(value) when is_integer(value), do: Integer.to_string(value)
  defp normalize_student_number(value) when is_binary(value), do: value
  defp normalize_student_number(value), do: to_string(value)

  defp get_int(map, key), do: get_int(map, key, nil)

  defp get_int(map, key, fallback_key) do
    value =
      case Map.fetch(map, key) do
        {:ok, value} -> value
        :error when is_binary(fallback_key) -> Map.get(map, fallback_key)
        :error -> nil
      end

    case value do
      nil ->
        {:error, :missing}

      value when is_integer(value) ->
        {:ok, value}

      value when is_binary(value) ->
        case Integer.parse(value) do
          {parsed, ""} -> {:ok, parsed}
          _ -> {:error, :invalid}
        end

      _ ->
        {:error, :invalid}
    end
  end

  defp query_display_names([]), do: []

  defp query_display_names(ids) do
    profile_with_account_query()
    |> where([_p, a], a.id in ^ids)
    |> select([p, a], %{id: a.id, name: a.name, nickname: p.nickname})
    |> Repo.all()
  end

  defp profile_with_account_query do
    from(p in Profile, join: a in assoc(p, :account))
  end

  defp fetch_cached_display_names([]), do: %{}

  defp fetch_cached_display_names(ids) do
    keys = Enum.map(ids, &display_name_cache_key/1)

    case Redix.command(:redix, ["MGET" | keys]) do
      {:ok, values} ->
        ids
        |> Enum.zip(values)
        |> Enum.reduce(%{}, fn {id, json}, acc -> put_if_cached(acc, id, json) end)

      {:error, _reason} ->
        %{}
    end
  end

  defp put_if_cached(acc, _id, nil), do: acc

  defp put_if_cached(acc, id, @not_found_marker), do: Map.put(acc, id, :not_found)

  defp put_if_cached(acc, id, json) do
    case Jason.decode(json) do
      {:ok, %{"name" => name, "nickname" => nickname}} ->
        Map.put(acc, id, %{id: id, name: name, nickname: nickname})

      _ ->
        acc
    end
  end

  defp cache_display_names(rows) do
    rows
    |> Enum.map(fn row ->
      payload = Jason.encode!(%{name: row.name, nickname: row.nickname})

      [
        "SET",
        display_name_cache_key(row.id),
        payload,
        "EX",
        Integer.to_string(@display_name_cache_ttl_seconds)
      ]
    end)
    |> persist_cache_commands()
  end

  defp cache_not_found(ids) do
    ids
    |> Enum.map(fn id ->
      [
        "SET",
        display_name_cache_key(id),
        @not_found_marker,
        "EX",
        Integer.to_string(@display_name_not_found_ttl_seconds)
      ]
    end)
    |> persist_cache_commands()
  end

  defp persist_cache_commands([]), do: :ok

  defp persist_cache_commands(commands) do
    case Redix.pipeline(:redix, commands) do
      {:ok, _results} ->
        :ok

      {:error, reason} ->
        Logger.warning("표시 이름 캐시 저장 실패: #{inspect(reason)}")
        :ok
    end
  end

  defp display_name_cache_key(id), do: "user:display_name:#{id}"

  defp load_profile(user_id) do
    Profile
    |> Repo.get_by(account_id: user_id)
    |> case do
      nil -> nil
      profile -> Repo.preload(profile, [:account, :profile_roles])
    end
  end

  defp normalize_roles(nil), do: []

  defp normalize_roles(roles) when is_list(roles) do
    roles
    |> Enum.map(&to_string/1)
    |> Enum.map(&String.trim/1)
    |> Enum.reject(&(&1 == ""))
    |> Enum.uniq()
    |> Enum.sort()
  end

  defp validate_upsert(attrs) do
    required = ~w(name email sex major role)a

    case Enum.find(required, fn key -> blank?(Map.get(attrs, Atom.to_string(key))) end) do
      nil -> :ok
      key -> {:error, {:validation, "#{key} 값이 필요합니다."}}
    end
  end

  defp blank?(nil), do: true
  defp blank?(""), do: true
  defp blank?(_), do: false

  defp maybe_like(query, _field, value, _source) when value in [nil, ""], do: query

  defp maybe_like(query, field, value, :account) do
    from([p, a] in query, where: like(field(a, ^field), ^"%#{value}%"))
  end

  defp maybe_like(query, field, value, :profile) do
    from([p, _a] in query, where: like(field(p, ^field), ^"%#{value}%"))
  end

  defp maybe_equals(query, _field, value, _source) when value in [nil, ""], do: query

  defp maybe_equals(query, field, value, :account) do
    from([p, a] in query, where: field(a, ^field) == ^value)
  end

  defp maybe_query(query, value) when value in [nil, ""], do: query

  defp maybe_query(query, q) do
    escaped = String.replace(q, ~r/[%_\\]/, &("\\" <> &1))
    pattern = "%#{escaped}%"
    from([p, a] in query, where: ilike(a.name, ^pattern) or ilike(p.nickname, ^pattern))
  end

  defp maybe_user_ids(query, nil), do: query

  defp maybe_user_ids(query, []), do: from([_p, a] in query, where: false)

  defp maybe_user_ids(query, ids) when is_list(ids) do
    from([_p, a] in query, where: a.id in ^ids)
  end

  defp maybe_user_ids(query, ids_str) when is_binary(ids_str) do
    case parse_int_csv(ids_str) do
      [] -> from([_p, a] in query, where: false)
      ids -> from([_p, a] in query, where: a.id in ^ids)
    end
  end

  @doc """
  쉼표로 구분된 정수 ID 목록 문자열을 파싱한다.

  유효하지 않은 토큰(빈 문자열, 0 이하, 정수가 아닌 값)은 무시한다.
  원본 토큰 수가 #{@max_raw_csv_tokens}개를 넘으면 초과분은 파싱하지 않고 버린다
  (`ids=1,1,1,...`처럼 중복 토큰을 대량으로 보내 `split`/`Integer.parse`을
  불필요하게 반복시키는 것을 방지).
  `user_ids` 검색 필터(`maybe_user_ids/2`)와 `GET /users/batch`(router의 `parse_ids/1`)가 공유한다.
  """
  def parse_int_csv(ids_str) when is_binary(ids_str) do
    ids_str
    |> String.split(",", parts: @max_raw_csv_tokens + 1)
    |> Enum.flat_map(fn s ->
      case Integer.parse(String.trim(s)) do
        {n, ""} when n > 0 -> [n]
        _ -> []
      end
    end)
  end

  defp maybe_role(query, nil), do: query
  defp maybe_role(query, ""), do: query

  defp maybe_role(query, role) do
    from([p, _a] in query,
      join: pr in ProfileRole,
      on: pr.profile_id == p.id,
      where: pr.role == ^role
    )
  end

  defp sort_clause("name", "desc"), do: [desc: dynamic([_p, a], a.name)]
  defp sort_clause("name", _), do: [asc: dynamic([_p, a], a.name)]
  defp sort_clause("nickname", "desc"), do: [desc: dynamic([p, _a], p.nickname)]
  defp sort_clause("nickname", _), do: [asc: dynamic([p, _a], p.nickname)]
  defp sort_clause("major", "desc"), do: [desc: dynamic([_p, a], a.major)]
  defp sort_clause("major", _), do: [asc: dynamic([_p, a], a.major)]
  defp sort_clause(_, "desc"), do: [desc: dynamic([p, _a], p.account_id)]
  defp sort_clause(_, _), do: [asc: dynamic([p, _a], p.account_id)]

  defp to_user_response(profile) do
    image_url =
      case profile.profile_image_key do
        nil ->
          nil

        key ->
          case ObjectStorage.presigned_get_url(key) do
            {:ok, url} -> url
            {:error, _reason} -> nil
          end
      end

    %{
      id: profile.account.id,
      name: profile.account.name,
      email: profile.account.email,
      sex: profile.account.sex,
      github_id: profile.account.github,
      account_description: profile.account.description,
      student_role: profile.account.student_role,
      student_number: profile.account.student_number,
      major: profile.account.major,
      specialty: profile.account.specialty,
      status: profile.account.status,
      custom_status: profile.account.custom_status,
      status_message: profile.account.status_message,
      status_expires_at: profile.account.status_expires_at,
      profile_image_url: image_url,
      nickname: profile.nickname,
      roles: profile.profile_roles |> Enum.map(& &1.role) |> Enum.uniq() |> Enum.sort(),
      description: profile.description || profile.account.description
    }
  end

  defp format_changeset_errors(changeset) do
    Ecto.Changeset.traverse_errors(changeset, fn {message, _opts} -> message end)
    |> Enum.map(fn {field, messages} -> "#{field} #{Enum.join(messages, ", ")}" end)
    |> Enum.join("; ")
  end

  defp parse_positive_int(nil, default), do: default
  defp parse_positive_int(value, _default) when is_integer(value) and value > 0, do: value

  defp parse_positive_int(value, default) do
    case Integer.parse(to_string(value)) do
      {parsed, ""} when parsed > 0 -> parsed
      _ -> default
    end
  end
end
