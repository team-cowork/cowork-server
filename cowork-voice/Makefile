GOPATH := $(shell go env GOPATH)
SWAG   := $(GOPATH)/bin/swag

.PHONY: swagger-install swagger-deps swagger-gen tidy build setup lint-install fmt lint

swagger-install:
	go install github.com/swaggo/swag/cmd/swag@v1.16.4

swagger-deps:
	go get github.com/swaggo/swag@v1.16.4
	go get github.com/swaggo/http-swagger@v1.3.4

swagger-gen:
	$(SWAG) init -g cmd/server/main.go -o docs --parseDependency --parseInternal

tidy:
	go mod tidy

build:
	go build -o bin/voice ./cmd/server/...

lint-install:
	go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.12.2

fmt:
	golangci-lint fmt ./...

lint:
	golangci-lint run ./...

setup: swagger-install swagger-deps swagger-gen tidy
