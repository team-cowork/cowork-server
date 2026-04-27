FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o cowork-voice ./cmd/server

FROM alpine:3.20 AS runtime
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/cowork-voice /usr/local/bin/cowork-voice
EXPOSE 8084
ENV PORT=8084
CMD ["cowork-voice"]
