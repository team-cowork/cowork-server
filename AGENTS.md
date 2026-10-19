# cowork Server

Read and follow `.claude/rules/*.md` whose `paths` match the files in scope.

- Only write and run unit tests for core business logic; do not write or run regression or integration tests.
- This test-authoring policy does not restrict CI or coverage discovery. Keep each module's normal test command; do not add test allowlists.
