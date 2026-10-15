export const ISSUE_WRITE_COMMAND_TOPIC = 'github.issue-write.command.v1';
export const ISSUE_WRITE_RESULT_TOPIC = 'github.issue-write.result.v1';
// Drains commands published before the github-app → github rename. Remove once its consumer lag reaches zero.
export const LEGACY_ISSUE_WRITE_COMMAND_TOPIC = 'github-app.issue-write.command';
