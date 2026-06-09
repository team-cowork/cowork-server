# 빌드 전 _build/prod/rel/cowork_user (MIX_ENV=prod mix release) 산출물이 컨텍스트에 있어야 한다.
# TODO: CI 산출물 핸드오프 배선 후 이 주석 삭제
FROM flyway/flyway:11.8.1 AS flyway

FROM debian:bookworm-20240926-slim
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends openssl curl wget ca-certificates default-mysql-client default-jre-headless libstdc++6 libncurses5 locales \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r app && useradd -r -g app app \
    && mkdir -p /var/log/cowork/user && chown -R app:app /var/log/cowork

COPY --from=flyway /flyway /flyway
COPY src/main/resources/db/migration /flyway/sql
COPY --chown=app:app _build/prod/rel/cowork_user ./
COPY --chown=app:app docker-entrypoint.sh /app/docker-entrypoint.sh

ENV PATH="/flyway:${PATH}"
ENV ELIXIR_ERL_OPTIONS="+fnu"
RUN chmod +x /app/docker-entrypoint.sh
USER app
EXPOSE 8082
ENTRYPOINT ["/app/docker-entrypoint.sh"]
