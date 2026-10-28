const admin = db.getSiblingDB('admin');
const database = process.env.DB_NAME;
const username = process.env.DB_USERNAME;
if (!/^cowork_[a-z][a-z0-9_]{0,55}$/.test(database) || !/^cowork_[a-z][a-z0-9_]{0,24}$/.test(username)) {
  throw new Error('Invalid database identity');
}
if (!admin.auth('cowork_admin', process.env.DB_ADMIN_PASSWORD)) {
  throw new Error('Administrator authentication failed');
}
function account(target, name, password, roles) {
  const value = { pwd: password, roles };
  if (target.getUser(name)) target.updateUser(name, value);
  else target.createUser({ user: name, ...value });
}
account(admin, username, process.env.DB_PASSWORD, [{ role: 'readWrite', db: database }]);
account(admin, 'cowork_monitoring', process.env.DB_MONITORING_PASSWORD, [
  { role: 'clusterMonitor', db: 'admin' },
  { role: 'read', db: 'local' },
]);
