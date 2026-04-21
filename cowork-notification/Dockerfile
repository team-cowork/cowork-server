FROM golang:1.23-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o cowork-notification ./cmd/server

FROM alpine:3.20 AS runtime
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/cowork-notification /usr/local/bin/cowork-notification
EXPOSE 8086
CMD ["cowork-notification"]
