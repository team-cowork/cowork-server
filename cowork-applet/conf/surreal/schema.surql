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
DEFINE INDEX IF NOT EXISTS uq_applet_storage_key ON TABLE applet_storage FIELDS appletId, scope, ownerId, `key` UNIQUE;

DEFINE TABLE IF NOT EXISTS applet_http_target SCHEMALESS;
DEFINE INDEX IF NOT EXISTS uq_applet_http_target_host ON TABLE applet_http_target FIELDS appletId, host UNIQUE;
