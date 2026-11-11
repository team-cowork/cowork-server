GOPATH := $(shell go env GOPATH)
SWAG   := $(GOPATH)/bin/swag

.PHONY: swagger-install swagger-gen tidy build setup fmt

swagger-install:
	go install github.com/swaggo/swag/cmd/swag@v1.16.6

swagger-gen:
	$(SWAG) init -g cmd/server/main.go -o swagger --parseDependency --parseInternal

tidy:
	go mod tidy

build:
	go build -o bin/notification ./cmd/server/...

fmt:
	golangci-lint fmt --config .golangci.yml ./...

setup: swagger-install swagger-gen tidy
