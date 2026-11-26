DEFINE TABLE IF NOT EXISTS applet SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_applet_id ON TABLE applet FIELDS appletId UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_applet_owner_id ON TABLE applet FIELDS ownerId;

DEFINE TABLE IF NOT EXISTS applet_version SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_version_version_id ON TABLE applet_version FIELDS versionId UNIQUE;
DEFINE INDEX IF NOT EXISTS uq_applet_version_number ON TABLE applet_version FIELDS appletId, number UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_variable SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_variable_name ON TABLE applet_variable FIELDS appletId, name UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_secret SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_secret_name ON TABLE applet_secret FIELDS appletId, name UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_storage SCHEMALESS;
-- Records written before team-scoped storage have no team, so nothing can read or delete them.
DELETE applet_storage WHERE teamId = NONE;
REMOVE INDEX IF EXISTS uq_applet_storage_key ON TABLE applet_storage;
DEFINE INDEX IF NOT EXISTS uq_applet_storage_team_key ON TABLE applet_storage FIELDS appletId, teamId, scope, ownerId, `key` UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_http_target SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_http_target_host ON TABLE applet_http_target FIELDS appletId, host UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_installation SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_installation_team_applet ON TABLE applet_installation FIELDS teamId, appletId UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_applet_installation_applet_id ON TABLE applet_installation FIELDS appletId;

DEFINE TABLE IF NOT EXISTS applet_team_member SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_team_member_team_user ON TABLE applet_team_member FIELDS teamId, userId UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_team_role_policy SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_team_role_policy_key ON TABLE applet_team_role_policy FIELDS policyKey UNIQUE;
DEFINE INDEX IF NOT EXISTS idx_applet_team_role_policy_team_id ON TABLE applet_team_role_policy FIELDS teamId;

DEFINE TABLE IF NOT EXISTS applet_projection_offset SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_projection_offset_partition ON TABLE applet_projection_offset FIELDS consumerGroup, topic, partition UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_projection_barrier SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_projection_barrier_partition ON TABLE applet_projection_barrier FIELDS consumerGroup, topic, partition UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_projection_quarantine SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_projection_quarantine_record ON TABLE applet_projection_quarantine FIELDS consumerGroup, topic, partition, recordOffset UNIQUE;
