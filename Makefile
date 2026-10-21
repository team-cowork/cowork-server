.PHONY: setup

setup:
	$(MAKE) -C cowork-authorization setup
	$(MAKE) -C cowork-notification setup
	$(MAKE) -C cowork-voice setup
