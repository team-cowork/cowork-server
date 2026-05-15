FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o cowork-notification ./cmd/server

FROM alpine:3.20 AS runtime
WORKDIR /app
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/cowork-notification /usr/local/bin/cowork-notification
COPY src/main/resources/db/migration ./db/migration
EXPOSE 8086
ENV PORT=8086
CMD ["cowork-notification"]
