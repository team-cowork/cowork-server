FROM mcr.microsoft.com/dotnet/sdk:10.0.402-noble@sha256:0d99ebf1ccad15037b5c98c9fe8979d6b8dbac23812feae2277a0b277b5c5ffa AS builder
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

FROM mcr.microsoft.com/dotnet/aspnet:10.0.13-noble@sha256:d5633778c754e89a386882eddf8dbee6942447a8a4b8267c19cf1e1158d0b0ac AS runtime
WORKDIR /app
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
EXPOSE 8082
ENTRYPOINT ["dotnet", "/app/CoworkUser.dll"]
