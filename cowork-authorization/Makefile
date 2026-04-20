GOPATH := $(shell go env GOPATH)
SWAG   := $(GOPATH)/bin/swag

.PHONY: swagger-install swagger-deps swagger-gen tidy build setup

swagger-install:
	go install github.com/swaggo/swag/cmd/swag@v1.16.4

swagger-deps:
	go get github.com/swaggo/swag@v1.16.4
	go get github.com/swaggo/gin-swagger@v1.6.0
	go get github.com/swaggo/files@v1.0.1

swagger-gen:
	$(SWAG) init -g cmd/main.go -o docs --parseDependency --parseInternal

tidy:
	go mod tidy

build:
	go build -o bin/authorization ./cmd/...

setup: swagger-install swagger-deps swagger-gen tidy
