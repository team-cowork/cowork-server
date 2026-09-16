defmodule CoworkUser.SchemaMigration do
  @moduledoc """
  Applies the bundled MySQL SQL migrations before application children start.

  The history table, checksums and named lock match Flyway 12.8.1 on standalone
  MySQL. Only integer-versioned plain DDL/DML scripts are supported; baselines,
  repeatables, callbacks and Flyway/MySQL-client script directives are rejected.
  MySQL DDL is not transactional: an incomplete migration remains marked failed
  and requires an operator to reconcile the schema before repairing its history.
  """

  require Logger

  defmodule Error do
    defexception [:message]
  end

  @history_table "flyway_schema_history"
  @lock_timeout_seconds 60
  @bootstrap_timeout_ms 900_000
  @connection_timeout_ms 15_000
  @integer_modulus 4_294_967_296
  @filename ~r/\AV([1-9][0-9]*)__([a-z][a-z0-9]*(?:_[a-z0-9]+)*)\.sql\z/
  @sql_literals_and_comments ~r/'(?:''|\\.|[^'\\])*'|"(?:""|\\.|[^"\\])*"|`(?:``|[^`])*`|--(?=[\x00-\x20])[^\r\n]*|\#[^\r\n]*|\/\*.*?\*\//su
  @statement_keywords ~w(CREATE ALTER DROP RENAME TRUNCATE INSERT UPDATE DELETE REPLACE)
  @connection_options [
    :hostname,
    :port,
    :database,
    :username,
    :password,
    :protocol,
    :socket,
    :socket_options,
    :ssl,
    :charset,
    :collation
  ]

  @spec run!(keyword()) :: :ok
  def run!(repo_options) do
    migrations = load_migrations!()

    options =
      repo_options
      |> Keyword.take(@connection_options)
      |> Keyword.merge(
        pool_size: 1,
        checkout_retries: 0,
        connect_timeout: @connection_timeout_ms,
        handshake_timeout: @connection_timeout_ms,
        queue_target: @connection_timeout_ms,
        queue_interval: @connection_timeout_ms,
        show_sensitive_data_on_connection_error: false
      )

    case MyXQL.start_link(options) do
      {:ok, pool} ->
        try do
          # A checked-out reference cannot reconnect and continue without its lock.
          # Its deadline bounds the whole bootstrap, including every SQL result.
          DBConnection.run(pool, &migrate!(&1, migrations), timeout: @bootstrap_timeout_ms)
        after
          stop_connection(pool)
        end

      {:error, _reason} ->
        fail!("Unable to start the migration database connection")
    end
  rescue
    error in Error ->
      reraise error, __STACKTRACE__

    error ->
      fail!(
        "Schema bootstrap failed (#{error_code(error)}); connection and SQL details are hidden"
      )
  catch
    :exit, _reason ->
      fail!("The migration database connection terminated; startup has been stopped")
  end

  defp load_migrations! do
    directory =
      case :code.priv_dir(:cowork_user) do
        path when is_list(path) -> Path.join([List.to_string(path), "db", "migration"])
        {:error, _} -> fail!("The release migration directory is unavailable")
      end

    migrations =
      directory
      |> File.ls!()
      |> Enum.map(fn filename ->
        case Regex.run(@filename, filename) do
          [_, version, description] ->
            if byte_size(version) > 50 or byte_size(description) > 200 do
              fail!("Migration filename exceeds Flyway history limits: #{filename}")
            end

            sql = directory |> Path.join(filename) |> File.read!() |> strip_bom()
            validate_sql!(sql, filename)

            %{
              version: String.to_integer(version),
              description: String.replace(description, "_", " "),
              script: filename,
              checksum: checksum(sql),
              sql: sql
            }

          _ ->
            fail!("Unsupported migration resource: #{filename}; expected V{n}__snake_case.sql")
        end
      end)
      |> Enum.sort_by(& &1.version)

    if migrations == [], do: fail!("No bundled SQL migrations were found")

    versions = Enum.map(migrations, & &1.version)

    if length(versions) != length(Enum.uniq(versions)) do
      fail!("Bundled SQL migrations contain duplicate versions")
    end

    migrations
  end

  defp validate_sql!(sql, filename) do
    unless String.valid?(sql), do: fail!("Migration is not valid UTF-8: #{filename}")

    if String.contains?(sql, "${") or
         Regex.match?(~r/\/\*(?:!|M!)/i, sql) or
         Regex.match?(~r/^\s*(?:--|\#)\s*flyway\s*:/im, sql) do
      fail!("Migration uses unsupported placeholders or executable directives: #{filename}")
    end

    # This masks literals/comments only for the support checks. Execution sends
    # the original complete file to MySQL; it never splits SQL on semicolons.
    code = Regex.replace(@sql_literals_and_comments, sql, " ")

    keywords =
      ~r/(?:\A|;)\s*([a-z]+)/i
      |> Regex.scan(code, capture: :all_but_first)
      |> Enum.map(fn [keyword] -> String.upcase(keyword) end)

    if keywords == [] or Enum.any?(keywords, &(&1 not in @statement_keywords)) or
         Regex.match?(~r/\b(?:DELIMITER|SOURCE|DEFINER|PROCEDURE|FUNCTION|TRIGGER)\b/i, code) or
         Regex.match?(~r/\b(?:CREATE|ALTER|DROP)\s+(?:DATABASE|SCHEMA|EVENT)\b/i, code) or
         Regex.match?(
           ~r/\b(?:GET_LOCK|RELEASE_LOCK|RELEASE_ALL_LOCKS|flyway_schema_history)\b/i,
           sql
         ) do
      fail!(
        "Migration must contain plain DDL/DML without session, lock or history control: #{filename}"
      )
    end
  end

  defp strip_bom(<<0xEF, 0xBB, 0xBF, rest::binary>>), do: rest
  defp strip_bom(sql), do: sql

  defp checksum(sql) do
    sql
    |> String.replace(["\r", "\n"], "")
    |> :erlang.crc32()
    |> signed_integer()
  end

  defp migrate!(connection, migrations) do
    database =
      case query!(connection, "SELECT DATABASE()", [], "read the selected database").rows do
        [[database]] when is_binary(database) and database != "" -> database
        _ -> fail!("A database must be selected before schema bootstrap")
      end

    lock_name = flyway_lock_name(database)

    case query!(
           connection,
           "SELECT GET_LOCK(?, ?)",
           [lock_name, @lock_timeout_seconds],
           "acquire the migration lock"
         ).rows do
      [[1]] -> :ok
      [[0]] -> fail!("Timed out waiting for the migration lock after #{@lock_timeout_seconds}s")
      _ -> fail!("MySQL did not grant the migration lock")
    end

    try do
      query!(connection, "SET SESSION autocommit = 1", [], "enable durable migration history")
      ensure_history!(connection)
      history = load_history!(connection)
      pending = validate_history!(history, migrations)

      if history == [], do: ensure_empty_schema!(connection, true)

      next_rank = history |> Enum.map(& &1.installed_rank) |> Enum.max(fn -> 0 end) |> Kernel.+(1)

      pending
      |> Enum.with_index(next_rank)
      |> Enum.each(fn {migration, rank} -> apply_migration!(connection, migration, rank) end)

      Logger.info(
        "User schema ready (#{length(migrations)} migrations, #{length(pending)} applied)"
      )

      :ok
    after
      release_lock(connection, lock_name)
    end
  end

  defp flyway_lock_name(database) do
    # Flyway 12.8.1: MySQLConnection.lock hashes SchemaObject.toString(), which
    # is `schema`.`flyway_schema_history`. Java hashes UTF-16 code units.
    # Its Database.doQuote wraps identifiers without escaping embedded backticks.
    quoted_table = "`#{database}`.`#{@history_table}`"
    utf16 = :unicode.characters_to_binary(quoted_table, :utf8, {:utf16, :big})

    hash =
      for <<unit::unsigned-big-integer-size(16) <- utf16>>, reduce: 0 do
        acc -> rem(acc * 31 + unit, @integer_modulus)
      end

    "Flyway-#{signed_integer(hash)}"
  end

  defp signed_integer(value) when value >= 2_147_483_648, do: value - @integer_modulus
  defp signed_integer(value), do: value

  defp ensure_history!(connection) do
    result =
      query!(
        connection,
        "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
        [@history_table],
        "inspect migration history"
      )

    case result.rows do
      [] ->
        ensure_empty_schema!(connection, false)

        query!(
          connection,
          """
          CREATE TABLE `flyway_schema_history` (
            `installed_rank` INT NOT NULL,
            `version` VARCHAR(50),
            `description` VARCHAR(200) NOT NULL,
            `type` VARCHAR(20) NOT NULL,
            `script` VARCHAR(1000) NOT NULL,
            `checksum` INT,
            `installed_by` VARCHAR(100) NOT NULL,
            `installed_on` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            `execution_time` INT NOT NULL,
            `success` BOOL NOT NULL,
            CONSTRAINT `flyway_schema_history_pk` PRIMARY KEY (`installed_rank`),
            INDEX `flyway_schema_history_s_idx` (`success`)
          ) ENGINE = InnoDB
          """,
          [],
          "create migration history"
        )

      [["BASE TABLE"]] ->
        :ok

      _ ->
        fail!("The migration history object is not a regular table")
    end
  end

  defp ensure_empty_schema!(connection, history_exists?) do
    result =
      query!(
        connection,
        """
        SELECT
          (SELECT COUNT(*) FROM information_schema.tables
           WHERE TABLE_SCHEMA = DATABASE() AND (? = 0 OR TABLE_NAME <> ?)) +
          (SELECT COUNT(*) FROM information_schema.routines WHERE ROUTINE_SCHEMA = DATABASE()) +
          (SELECT COUNT(*) FROM information_schema.events WHERE EVENT_SCHEMA = DATABASE())
        """,
        [if(history_exists?, do: 1, else: 0), @history_table],
        "check for untracked schema objects"
      )

    unless result.rows == [[0]] do
      fail!(
        "The database contains objects without applied migration history; automatic baselining is disabled"
      )
    end
  end

  defp load_history!(connection) do
    query!(
      connection,
      """
      SELECT installed_rank, version, description, type, script, checksum,
             installed_by, installed_on, execution_time, success
      FROM `flyway_schema_history`
      ORDER BY installed_rank
      """,
      [],
      "read migration history"
    ).rows
    |> Enum.map(fn [rank, version, description, type, script, checksum, _by, _on, time, success] ->
      %{
        installed_rank: rank,
        version: version,
        description: description,
        type: type,
        script: script,
        checksum: checksum,
        execution_time: time,
        success: success
      }
    end)
  end

  defp validate_history!(history, migrations) do
    Enum.reduce(history, 0, fn row, previous_rank ->
      unless row.success in [true, 1] do
        fail!(
          "Migration history contains a failed entry at rank #{row.installed_rank}; reconcile the schema and repair history before restarting"
        )
      end

      unless is_integer(row.installed_rank) and row.installed_rank > previous_rank and
               row.type == "SQL" and is_binary(row.version) and
               Regex.match?(~r/\A[1-9][0-9]*\z/, row.version) and
               is_integer(row.execution_time) and row.execution_time >= 0 do
        fail!(
          "Migration history contains an unsupported or invalid entry; baselines and repeatables are not supported"
        )
      end

      row.installed_rank
    end)

    if length(history) > length(migrations) do
      fail!("Migration history contains versions absent from this release")
    end

    history
    |> Enum.zip(migrations)
    |> Enum.each(fn {row, migration} ->
      if row.version != Integer.to_string(migration.version) do
        fail!(
          "Migration history is not an ordered prefix of the bundled versions; missing, duplicate, future or out-of-order migrations require reconciliation"
        )
      end

      if row.script != migration.script or row.description != migration.description do
        fail!("Migration history metadata differs for #{migration.script}")
      end

      if row.checksum != nil and row.checksum != migration.checksum do
        fail!("Migration checksum differs for #{migration.script}; restore the original file")
      end
    end)

    Enum.drop(migrations, length(history))
  end

  defp apply_migration!(connection, migration, rank) do
    query!(
      connection,
      """
      INSERT INTO `flyway_schema_history`
        (installed_rank, version, description, type, script, checksum,
         installed_by, execution_time, success)
      VALUES (?, ?, ?, 'SQL', ?, ?, SUBSTRING_INDEX(USER(), '@', 1), 0, 0)
      """,
      [
        rank,
        Integer.to_string(migration.version),
        migration.description,
        migration.script,
        migration.checksum
      ],
      "record the pending migration #{migration.script}"
    )

    started_at = System.monotonic_time(:millisecond)
    Logger.info("Applying user schema migration #{migration.script}")

    case MyXQL.query_many(connection, migration.sql, [],
           query_type: :text,
           timeout: @bootstrap_timeout_ms,
           checkout_retries: 0
         ) do
      {:ok, _results} ->
        result =
          query!(
            connection,
            "UPDATE `flyway_schema_history` SET execution_time = ?, success = 1 WHERE installed_rank = ? AND success = 0",
            [elapsed_ms(started_at), rank],
            "mark migration #{migration.script} as successful"
          )

        unless result.num_rows == 1 do
          fail!(
            "Migration history changed while applying #{migration.script}; startup has been stopped"
          )
        end

      {:error, error} ->
        fail!(
          "Migration #{migration.script} failed (#{error_code(error)}); its history remains failed. Reconcile any partial DDL and repair history before restarting"
        )
    end
  end

  defp elapsed_ms(started_at),
    do: min(System.monotonic_time(:millisecond) - started_at, 2_147_483_647)

  defp query!(connection, statement, parameters, operation) do
    case MyXQL.query(connection, statement, parameters,
           timeout: @bootstrap_timeout_ms,
           checkout_retries: 0
         ) do
      {:ok, result} -> result
      {:error, error} -> fail!("Unable to #{operation} (#{error_code(error)})")
    end
  end

  defp release_lock(connection, name) do
    case MyXQL.query(connection, "SELECT RELEASE_LOCK(?)", [name],
           timeout: @connection_timeout_ms,
           checkout_retries: 0
         ) do
      {:ok, %{rows: [[1]]}} ->
        :ok

      _ ->
        Logger.warning(
          "Migration lock release was not confirmed; closing its database connection"
        )
    end
  rescue
    _ ->
      Logger.warning("Migration connection closed before its lock could be explicitly released")
  catch
    :exit, _ -> :ok
  end

  defp stop_connection(pool) do
    GenServer.stop(pool, :normal, @connection_timeout_ms)
  catch
    :exit, _ -> :ok
  end

  defp error_code(%MyXQL.Error{mysql: %{code: code}}), do: "MySQL error #{code}"
  defp error_code(%DBConnection.ConnectionError{}), do: "database connection error"
  defp error_code(_), do: "database or migration resource error"

  defp fail!(message), do: raise(Error, message: message)
end
