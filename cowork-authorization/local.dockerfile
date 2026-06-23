FROM golang:1.26-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o cowork-authorization ./cmd

FROM alpine:3.20 AS runtime
WORKDIR /app
RUN apk --no-cache add ca-certificates tzdata
COPY --from=builder /app/cowork-authorization /usr/local/bin/cowork-authorization
COPY src/main/resources/db/migration ./db/migration
EXPOSE 8081
CMD ["cowork-authorization"]
