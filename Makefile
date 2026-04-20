VERSION := $(shell cat VERSION)

.PHONY: version bump tag release

version:
	@cat VERSION

bump:
	@./scripts/bump.sh

tag:
	git add VERSION cowork-user/build.gradle.kts cowork-gateway/build.gradle.kts \
	  cowork-team/build.gradle.kts cowork-config/build.gradle.kts \
	  cowork-notification/build.gradle.kts cowork-preference/build.gradle.kts \
	  cowork-chat/package.json
	git commit -m "chore: release v$(shell cat VERSION)"
	git tag v$(shell cat VERSION)

release: tag
	git push origin main --follow-tags
