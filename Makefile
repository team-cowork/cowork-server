VERSION := $(shell cat VERSION)

.PHONY: version bump tag release init-logs

version:
	@cat VERSION

bump:
	@./scripts/bump.sh

tag:
	git add VERSION cowork-*/build.gradle.kts cowork-chat/package.json
	git commit -m "chore: release v$(shell cat VERSION)"
	git tag v$(shell cat VERSION)

release: tag
	git push origin main --follow-tags

init-logs:
	@bash scripts/init-log-dirs.sh
