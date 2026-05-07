FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -trimpath -o cowork-voice ./cmd/server

FROM alpine:3.20
RUN apk --no-cache add ca-certificates tzdata && \
    addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /app/cowork-voice /usr/local/bin/cowork-voice
EXPOSE 8084
ENV PORT=8084
CMD ["cowork-voice"]
