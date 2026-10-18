#!/bin/bash
# Applies a service's SQL migrations with the official Flyway CLI before the new container takes over.
# The previous instance keeps serving while this runs, and a failure stops the deployment before cutover.
FLYWAY_IMAGE='flyway/flyway:12.8.1@sha256:b8a2d72926b98234c1fb8f45659fd23d8a001af9ee7f450326aa46af14d447bb'

# The migrations and flyway.toml ship in the application image under /app/db; the slot's DB_* variables
# reach Flyway unchanged and flyway.toml turns them into the JDBC connection.
run_migrations() (
  local image=$1 network='' previous='' entry workdir extractor
  local -a database_env=()
  while IFS= read -r -d '' argument; do
    [ "$previous" = --network ] && network=$argument
    previous=$argument
  done < "${DEPLOY_SETTINGS_DIR}/container.args0"
  [ -n "$network" ] || fail 'The slot network is required to run migrations'
  # Values stay out of the docker command line: -e NAME reads them from this subshell's environment.
  while IFS= read -r -d '' entry; do
    case "$entry" in
      DB_HOST=*|DB_PORT=*|DB_NAME=*|DB_USERNAME=*|DB_PASSWORD=*) export "$entry"; database_env+=(-e "${entry%%=*}") ;;
    esac
  done < "${DEPLOY_SETTINGS_DIR}/container.env0"
  [ "${#database_env[@]}" -eq 10 ] || fail 'DB_HOST, DB_PORT, DB_NAME, DB_USERNAME and DB_PASSWORD are required to run migrations'

  workdir=$(mktemp -d)
  trap 'rm -rf "$workdir"' EXIT
  # The extractor container is created from the application image and never started.
  extractor=$(docker create "$image")
  docker cp "${extractor}:/app/db/." "$workdir/"
  docker rm "$extractor" >/dev/null
  [ -f "${workdir}/flyway.toml" ] && [ -d "${workdir}/migration" ] || fail "The image ${image} carries no migrations"
  chmod -R a+rX "$workdir"

  docker pull "$FLYWAY_IMAGE"
  echo "[deploy] Applying migrations from ${image}"
  timeout 600 docker run --rm --network "$network" --memory 512m --cpus 1 --pids-limit 256 \
    --security-opt no-new-privileges:true --cap-drop ALL "${database_env[@]}" \
    -v "${workdir}/migration:/flyway/sql:ro" -v "${workdir}/flyway.toml:/flyway/conf/flyway.toml:ro" \
    "$FLYWAY_IMAGE" migrate || fail 'Migration failed; the previous container was left running'
)
