VERSION := $(shell cat VERSION)

.PHONY: version bump tag release init-logs setup

version:
	@cat VERSION

bump:
	@./scripts/bump.sh

tag:
	git add VERSION MODULE.bazel cowork-*/build.gradle.kts cowork-*/package.json cowork-*/pom.xml \
	        cowork-user/mix.exs \
	        cowork-authorization/cmd/main.go \
	        cowork-notification/cmd/server/main.go \
	        cowork-voice/cmd/server/main.go
	git commit -m "chore: release v$(shell cat VERSION)"
	git tag v$(shell cat VERSION)

release: tag
	git push origin main --follow-tags

init-logs:
	@bash scripts/init-log-dirs.sh

setup:
	$(MAKE) -C cowork-authorization setup
	$(MAKE) -C cowork-notification setup
	$(MAKE) -C cowork-voice setup
