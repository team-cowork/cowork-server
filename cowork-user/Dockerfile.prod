ARG ELIXIR_IMAGE=hexpm/elixir:1.18.4-erlang-27.3.4-debian-bookworm-20250520-slim

FROM flyway/flyway:12.8.1 AS flyway

FROM ${ELIXIR_IMAGE} AS builder
WORKDIR /app
ENV MIX_ENV=prod
RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && mix local.hex --force \
    && mix local.rebar --force
ENV ERL_FLAGS="+S 1:1 +JMsingle true"

COPY mix.exs mix.lock ./
COPY config config
RUN mix deps.get --only prod \
    && mix deps.compile
COPY lib lib
COPY priv priv
RUN mix compile \
    && mix release

FROM debian:bookworm-20240926-slim AS runtime
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl curl wget jq ca-certificates default-mysql-client default-jre-headless libstdc++6 libncurses5 locales \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app \
    && useradd -r -g app app \
    && mkdir -p /var/log/cowork/user \
    && chown -R app:app /var/log/cowork

COPY --from=flyway /flyway /flyway
COPY src/main/resources/db/migration /flyway/sql
COPY --chown=app:app --from=builder /app/_build/prod/rel/cowork_user ./
COPY --chown=app:app docker-entrypoint.sh /app/docker-entrypoint.sh
ENV PATH="/flyway:${PATH}"
ENV ELIXIR_ERL_OPTIONS="+fnu"
RUN chmod +x /app/docker-entrypoint.sh && chown app:app /app
USER app
EXPOSE 8082
ENTRYPOINT ["/app/docker-entrypoint.sh"]
