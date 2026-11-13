FROM flyway/flyway:12.8.1@sha256:b8a2d72926b98234c1fb8f45659fd23d8a001af9ee7f450326aa46af14d447bb AS flyway

FROM mcr.microsoft.com/dotnet/sdk:11.0.100-resolute@sha256:65f3c7c6a25a239ab3e6719f2896147e7b68242ff2d8818dd70498bde6ef8bea AS builder
WORKDIR /workspace/cowork-user
ENV DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1

COPY cowork-user/cowork-user.slnx ./
COPY cowork-user/src/CoworkUser.csproj src/
COPY cowork-user/test/CoworkUser.Tests.csproj test/
RUN --mount=type=cache,id=cowork-user-nuget,target=/root/.nuget/packages,sharing=locked \
    dotnet restore cowork-user.slnx
COPY cowork-user/src src
RUN --mount=type=cache,id=cowork-user-nuget,target=/root/.nuget/packages,sharing=locked \
    dotnet publish src/CoworkUser.csproj -c Release --no-restore -o /out


FROM builder AS verification
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*
COPY cowork-user/test test
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=cowork-user-nuget,target=/root/.nuget/packages,sharing=locked \
    bash /ci/run-native.sh dotnet cowork-user /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-user --snapshot pr --format lcov --metric line --input /coverage/report.info --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM mcr.microsoft.com/dotnet/aspnet:11.0.0-resolute@sha256:bc0d6653979efba491ab3d6f589d691f96c309c3c3ef81325efa6978e7b7f242 AS runtime
WORKDIR /app
ENV JAVA_HOME=/opt/java/openjdk PATH="/flyway:/opt/java/openjdk/bin:${PATH}"
COPY --from=flyway /opt/java/openjdk /opt/java/openjdk
COPY --from=flyway /flyway /flyway
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r -g 10001 cowork \
    && useradd -r -u 10001 -g cowork cowork \
    && mkdir -p /var/log/cowork/user \
    && chown -R cowork:cowork /var/log/cowork

COPY --chown=cowork:cowork --from=builder /out ./
RUN test -f /app/CoworkUser.dll \
    && test -n "$(find /app/db/migration -name 'V*__*.sql' -print -quit)"
RUN test "$(stat -c %u /var/log/cowork)" = 10001
USER cowork
RUN flyway -v
EXPOSE 8082
ENTRYPOINT ["dotnet", "/app/CoworkUser.dll"]
