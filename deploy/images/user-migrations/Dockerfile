FROM flyway/flyway:12.8.1@sha256:b8a2d72926b98234c1fb8f45659fd23d8a001af9ee7f450326aa46af14d447bb
COPY cowork-user/src/db/migration/ /flyway/sql/
COPY cowork-user/src/db/flyway.toml /flyway/conf/flyway.toml
USER 1000:1000
