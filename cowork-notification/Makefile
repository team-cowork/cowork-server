GOPATH := $(shell go env GOPATH)
SWAG   := $(GOPATH)/bin/swag

.PHONY: swagger-install swagger-gen tidy build setup lint-install fmt lint

swagger-install:
	go install github.com/swaggo/swag/cmd/swag@v1.16.6

swagger-gen:
	$(SWAG) init -g cmd/server/main.go -o docs --parseDependency --parseInternal

tidy:
	go mod tidy

build:
	go build -o bin/notification ./cmd/server/...

lint-install:
	go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.12.2

fmt:
	golangci-lint fmt ./...

lint:
	golangci-lint run ./...

setup: swagger-install swagger-gen tidy
