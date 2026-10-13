#!/usr/bin/env python3
"""Create least-privilege exporter accounts on the DB VM from a private stdin document."""
import json
import os
import re
import subprocess
import sys
from urllib.parse import urlsplit


def execute(engine, args, content, environment=None):
    result = subprocess.run(args, input=content, text=True, capture_output=True,
                            env={**os.environ, **(environment or {})}, timeout=45)
    if result.returncode:
        # Database diagnostics can include credentials or statements.
        raise RuntimeError(f'{engine} exporter account setup failed; inspect the database locally')
    print(f'{engine}: exporter account privileges applied')


def main():
    settings = json.load(sys.stdin)
    admin, monitoring = settings['datastores'], settings['monitoring']
    passwords = {'mysql': monitoring['MYSQL_EXPORTER_PASSWORD'],
                 'postgres': urlsplit(monitoring['POSTGRES_EXPORTER_DSN']).password,
                 'mongodb': urlsplit(monitoring['MONGO_EXPORTER_URI']).password}
    if not all(isinstance(value, str) and re.fullmatch(r'[a-f0-9]{48}', value) for value in passwords.values()):
        raise ValueError('Bootstrap exporter passwords must be 24-byte hexadecimal values')
    password = passwords['mysql']
    sql = f"""CREATE USER IF NOT EXISTS 'cowork_monitoring'@'10.0.0.137' IDENTIFIED BY '{password}';
ALTER USER 'cowork_monitoring'@'10.0.0.137' IDENTIFIED BY '{password}' WITH MAX_USER_CONNECTIONS 3;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'cowork_monitoring'@'10.0.0.137';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'cowork_monitoring'@'10.0.0.137';
"""
    execute('MySQL', ['docker', 'exec', '-i', '--env', 'MYSQL_PWD', 'cowork-mysql', 'mysql', '-u', 'root'],
            sql, {'MYSQL_PWD': admin['mysql']['root_password']})
    password = passwords['postgres']
    sql = f"""DO $$ BEGIN
IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='cowork_monitoring') THEN
CREATE ROLE cowork_monitoring LOGIN; END IF; END $$;
ALTER ROLE cowork_monitoring WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '{password}' CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE postgres TO cowork_monitoring;
GRANT pg_monitor TO cowork_monitoring;
"""
    execute('PostgreSQL', ['docker', 'exec', '-i', '--env', 'PGPASSWORD', 'cowork-postgres',
                           'psql', '-v', 'ON_ERROR_STOP=1', '-U', admin['postgres']['user'], '-d', 'postgres'],
            sql, {'PGPASSWORD': admin['postgres']['password']})
    script = """const s=JSON.parse(require('fs').readFileSync(0,'utf8'));
const a=db.getSiblingDB('admin');
if (!a.auth(s.username,s.password)) throw new Error('Administrator authentication failed');
const u={pwd:s.exporterPassword,roles:[{role:'clusterMonitor',db:'admin'},{role:'read',db:'local'}]};
if (a.getUser('cowork_monitoring')) a.updateUser('cowork_monitoring',u);
else a.createUser(Object.assign({user:'cowork_monitoring'},u));
"""
    execute('MongoDB', ['docker', 'exec', '-i', 'cowork-mongodb', 'mongosh', '--quiet', '--norc', '--eval', script],
            json.dumps({'username': admin['mongodb']['user'], 'password': admin['mongodb']['password'],
                        'exporterPassword': passwords['mongodb']}))


if __name__ == '__main__':
    main()
