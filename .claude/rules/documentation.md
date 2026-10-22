---
paths:
  - "**/README.md"
  - "**/docs/**/*.md"
---

# Documentation Conventions

- Preserve the established README format, section order, headings, and table layouts. Inspect peer READMEs before editing; do not introduce one-off sections or expand their scope.
- Module READMEs (`cowork-*/README.md`) use role, stack, ports, and environment variables sections in that order. Keep updates within this shared structure. Root and nested documentation READMEs retain their own established formats.
- Document important, lasting knowledge separately when it cannot be discovered or inferred by exploring the codebase. Update an appropriate existing document or create a focused file in the relevant `docs/` directory; do not add it as a new README section.
- Inspect source code, configuration, and scripts before deciding that knowledge needs separate documentation. Do not create separate documents that merely restate information recoverable from them.
