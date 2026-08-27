BINARY  ?= atagent
PKG     := ./cmd/atagent
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo 0.1.0)
COMMIT  ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
BUILT   ?= $(shell date -u +%FT%TZ)

MODULE  := github.com/atest/atagent/internal/version
LDFLAGS := -s -w -X $(MODULE).Version=$(VERSION) -X $(MODULE).Commit=$(COMMIT) -X $(MODULE).BuildTime=$(BUILT)

.PHONY: build static test race vet fmt clean

build:
	go build -o $(BINARY) $(PKG)

# Release artifact: fully static, no libc dependency.
static:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "$(LDFLAGS)" -o $(BINARY) $(PKG)
	@file $(BINARY) 2>/dev/null || true

test:
	go test ./...

race:
	go test -race ./...

vet:
	go vet ./...

fmt:
	gofmt -l -w .

clean:
	rm -f $(BINARY)
