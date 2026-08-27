FROM golang:1.26-alpine AS builder
WORKDIR /src

COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod \
    go mod download
COPY . .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/cowork-notification ./cmd/server

FROM alpine:3.20 AS runtime
RUN apk --no-cache add ca-certificates tzdata \
    && addgroup -S app \
    && adduser -S app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /out/cowork-notification /usr/local/bin/cowork-notification
COPY --chown=app:app src/main/resources/db/migration ./db/migration
USER app
EXPOSE 8086
ENV PORT=8086
ENTRYPOINT ["cowork-notification"]
