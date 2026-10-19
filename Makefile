.PHONY: init-logs setup

init-logs:
	@bash deploy/local/init-log-dirs.sh

setup:
	$(MAKE) -C cowork-authorization setup
	$(MAKE) -C cowork-notification setup
	$(MAKE) -C cowork-voice setup
