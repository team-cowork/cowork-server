ARG ELIXIR_IMAGE=hexpm/elixir:1.17.3-erlang-27.1.1-debian-bookworm-20240926-slim

FROM flyway/flyway:11.8.1 AS flyway

FROM ${ELIXIR_IMAGE} AS builder
WORKDIR /app
ENV MIX_ENV=prod

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential cmake git curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mix local.hex --force && mix local.rebar --force

COPY cowork-user/mix.exs ./
COPY cowork-user/config config
RUN mix deps.get
RUN mix deps.compile

COPY cowork-user/lib lib
COPY cowork-user/priv priv
RUN mix compile
RUN mix release

FROM debian:bookworm-20240926-slim
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl curl wget ca-certificates default-mysql-client default-jre-headless libstdc++6 libncurses5 locales \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app app \
    && mkdir -p /var/log/cowork/user && chown -R app:app /var/log/cowork

COPY --from=flyway /flyway /flyway
COPY cowork-user/src/main/resources/db/migration /flyway/sql
COPY --from=builder /app/_build/prod/rel/cowork_user ./
COPY cowork-user/docker-entrypoint.sh /app/docker-entrypoint.sh

ENV PATH="/flyway:${PATH}"
ENV ELIXIR_ERL_OPTIONS="+fnu"
RUN chmod +x /app/docker-entrypoint.sh && chown -R app:app /app
USER app
EXPOSE 8082
ENTRYPOINT ["/app/docker-entrypoint.sh"]
