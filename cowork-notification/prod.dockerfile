FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -trimpath -o cowork-notification ./cmd/server

FROM alpine:3.20
RUN apk --no-cache add ca-certificates tzdata && \
    addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=builder /app/cowork-notification /usr/local/bin/cowork-notification
EXPOSE 8086
ENV PORT=8086
CMD ["cowork-notification"]
