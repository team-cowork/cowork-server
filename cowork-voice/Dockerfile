FROM golang:1.27.1-alpine3.24@sha256:cf6fca6641884b8433441b2b0652976f975e1d0fdd26d177eaaf8596087f3125 AS builder
WORKDIR /workspace/cowork-voice

COPY cowork-voice/go.mod cowork-voice/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download
COPY cowork-voice/ ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/cowork-voice ./cmd/server


FROM builder AS verification
RUN apk --no-cache add bash python3 git build-base
COPY --from=golangci/golangci-lint:v2.13.2-alpine@sha256:da1f79b184ff1d98781648407d05a99b9cba7b282aa3416b0a0eac102a3b0557 /usr/bin/golangci-lint /usr/local/bin/golangci-lint
COPY deploy/images/ci /ci
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    golangci-lint run \
    && go vet ./... \
    && bash /ci/run-native.sh go cowork-voice /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-voice --snapshot pr --format go --metric statement --input /coverage/report.out --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS runtime
RUN apk --no-cache add ca-certificates tzdata \
    && addgroup -S -g 10001 app \
    && adduser -S -u 10001 app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /out/cowork-voice /usr/local/bin/cowork-voice
USER app
EXPOSE 8089
ENV PORT=8089
ENTRYPOINT ["cowork-voice"]
