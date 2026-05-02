defmodule CoworkUser.Accounts do
  import Ecto.Query

  alias Ecto.Multi
  alias CoworkUser.Accounts.{Account, Profile, ProfileRole}
  alias CoworkUser.Repo
  alias CoworkUser.Storage.Minio

  def get_my_profile(user_id), do: get_user_profile(user_id)

  def get_user_profile(user_id) do
    case load_profile(user_id) do
      nil -> {:error, :not_found}
      profile -> {:ok, to_user_response(profile)}
    end
  end

  def update_my_profile(user_id, attrs) do
    with %Profile{} = profile <- load_profile(user_id) do
      roles = attrs["roles"] |> normalize_roles()

      Multi.new()
      |> Multi.update(:profile, Profile.changeset(profile, %{
        nickname: Map.get(attrs, "nickname"),
        description: Map.get(attrs, "description"),
        last_modified_by: user_id
      }))
      |> replace_roles(profile.id, roles)
      |> Repo.transaction()
      |> case do
        {:ok, _} -> get_my_profile(user_id)
        {:error, _step, changeset, _} -> {:error, {:validation, format_changeset_errors(changeset)}}
      end
    else
      nil -> {:error, :not_found}
    end
  end

  def generate_presigned_url(user_id, %{"content_type" => content_type}) do
    object_key = Minio.build_object_key(user_id, content_type)

    with %Profile{} <- load_profile(user_id),
         :ok <- Minio.validate_content_type(content_type),
         {:ok, upload_url} <- Minio.presigned_put_url(object_key, content_type) do

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

  def generate_presigned_url(_user_id, _attrs), do: {:error, {:validation, "content_type 값이 필요합니다."}}

  def confirm_upload(user_id, %{"object_key" => object_key}) do
    with %Profile{} = profile <- load_profile(user_id),
         :ok <- Minio.verify_upload(user_id, object_key),
         previous_key = profile.profile_image_key,
         {:ok, _} <-
           profile
           |> Profile.changeset(%{profile_image_key: object_key, last_modified_by: user_id})
           |> Repo.update() do
      if previous_key && previous_key != object_key, do: Minio.delete_object(previous_key)
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
      if previous_key, do: Minio.delete_object(previous_key)
      :ok
    else
      nil -> {:error, :not_found}
      {:error, reason} -> {:error, reason}
    end
  end

  def upsert_user(user_id, attrs) do
    with :ok <- validate_upsert(attrs) do
      Repo.transaction(fn ->
        account =
          Repo.get(Account, user_id)
          |> case do
            nil ->
              %Account{}
              |> Account.changeset(account_attrs(user_id, attrs))
              |> Repo.insert!()

            existing ->
              existing
              |> Account.changeset(account_attrs(user_id, attrs))
              |> Repo.update!()
          end

        Repo.get_by(Profile, account_id: user_id)
        |> case do
          nil ->
            %Profile{}
            |> Profile.changeset(%{account_id: account.id})
            |> Repo.insert!()

          profile ->
            profile
        end
      end)
      |> case do
        {:ok, _} -> get_user_profile(user_id)
        {:error, %Ecto.Changeset{} = changeset} -> {:error, {:validation, format_changeset_errors(changeset)}}
        {:error, reason} -> {:error, {:validation, inspect(reason)}}
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
      "student_role" => Map.get(event, "student_role") || Map.get(event, "st_role") || Map.get(event, "stRole"),
      "student_number" => Map.get(event, "student_number") || Map.get(event, "st_num") || Map.get(event, "stNum"),
      "major" => Map.get(event, "major"),
      "role" => Map.get(event, "role"),
      "specialty" => Map.get(event, "specialty") || Map.get(event, "spe"),
      "status" => Map.get(event, "status") || "offline",
      "account_description" => Map.get(event, "account_description") || Map.get(event, "description")
    }

    Repo.transaction(fn ->
      account =
        Repo.get(Account, user_id)
        |> case do
          nil ->
            %Account{}
            |> Account.changeset(Map.put(account_attrs(user_id, attrs), :description, Map.get(attrs, "account_description")))
            |> Repo.insert!()

          existing ->
            existing
            |> Account.changeset(Map.put(account_attrs(existing.id, attrs), :description, Map.get(attrs, "account_description")))
            |> Repo.update!()
        end

      Repo.get_by(Profile, account_id: account.id) ||
        Repo.insert!(Profile.changeset(%Profile{}, %{account_id: account.id}))
    end)
  rescue
    exception in [Ecto.InvalidChangesetError] ->
      {:error, {:validation, format_changeset_errors(exception.changeset)}}

    exception in [Ecto.ConstraintError] ->
      {:error, {:validation, Exception.message(exception)}}

    exception ->
      {:error, {:transient, Exception.message(exception)}}
  end

  def search_users(params) do
    page = parse_positive_int(Map.get(params, "page"), 1)
    page_size = parse_positive_int(Map.get(params, "page_size"), 20) |> min(100)
    sort_by = Map.get(params, "sort_by", "id")
    sort_order = Map.get(params, "sort_order", "asc")

    base_query =
      from p in Profile,
        join: a in assoc(p, :account)

    filtered_query =
      base_query
      |> maybe_like(:name, Map.get(params, "name"), :account)
      |> maybe_like(:nickname, Map.get(params, "nickname"), :profile)
      |> maybe_equals(:major, Map.get(params, "major"), :account)
      |> maybe_equals(:student_role, Map.get(params, "student_role"), :account)
      |> maybe_equals(:status, Map.get(params, "status"), :account)
      |> maybe_role(Map.get(params, "role"))

    total_count =
      filtered_query
      |> exclude(:preload)
      |> exclude(:order_by)
      |> distinct(true)
      |> Repo.aggregate(:count, :id)

    items =
      filtered_query
      |> order_by(^sort_clause(sort_by, sort_order))
      |> limit(^ (page_size + 1))
      |> offset(^ ((page - 1) * page_size))
      |> Repo.all()
      |> Repo.preload([:account, :profile_roles])

    has_next = length(items) > page_size

    %{
      items: items |> Enum.take(page_size) |> Enum.map(&to_user_response/1),
      page: page,
      page_size: page_size,
      total_count: total_count,
      has_next: has_next
    }
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
      student_number: Map.get(attrs, "student_number") || build_student_number(attrs),
      major: Map.get(attrs, "major"),
      specialty: Map.get(attrs, "specialty"),
      status: Map.get(attrs, "status", "offline")
    }
  end

  defp build_student_number(attrs) do
    with {:ok, grade} <- get_int(attrs, "grade"),
         {:ok, class_number} <- get_int(attrs, "class_number"),
         {:ok, student_number_in_class} <- get_int(attrs, "student_number_in_class") do
      "#{grade}#{class_number}#{student_number_in_class |> Integer.to_string() |> String.pad_leading(2, "0")}"
    else
      _ -> nil
    end
  end

  defp get_int(map, key) do
    case Map.get(map, key) do
      nil -> {:error, :missing}
      value when is_integer(value) -> {:ok, value}
      value when is_binary(value) ->
        case Integer.parse(value) do
          {parsed, ""} -> {:ok, parsed}
          _ -> {:error, :invalid}
        end

      _ ->
        {:error, :invalid}
    end
  end

  defp load_profile(user_id) do
    Profile
    |> Repo.get_by(account_id: user_id)
    |> case do
      nil -> nil
      profile -> Repo.preload(profile, [:account, :profile_roles])
    end
  end

  defp replace_roles(multi, profile_id, roles) do
    multi
    |> Multi.delete_all(:delete_roles, from(pr in ProfileRole, where: pr.profile_id == ^profile_id))
    |> Multi.run(:insert_roles, fn repo, _changes ->
      if roles == [] do
        {:ok, {0, nil}}
      else
        {:ok, repo.insert_all(ProfileRole, Enum.map(roles, &%{profile_id: profile_id, role: &1}))}
      end
    end)
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
    from [p, a] in query, where: like(field(a, ^field), ^"%#{value}%")
  end

  defp maybe_like(query, field, value, :profile) do
    from [p, _a] in query, where: like(field(p, ^field), ^"%#{value}%")
  end

  defp maybe_equals(query, _field, value, _source) when value in [nil, ""], do: query

  defp maybe_equals(query, field, value, :account) do
    from [p, a] in query, where: field(a, ^field) == ^value
  end

  defp maybe_role(query, nil), do: query
  defp maybe_role(query, ""), do: query

  defp maybe_role(query, role) do
    from [p, _a] in query,
      join: pr in ProfileRole,
      on: pr.profile_id == p.id,
      where: pr.role == ^role
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
        nil -> nil
        key ->
          case Minio.presigned_get_url(key) do
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
