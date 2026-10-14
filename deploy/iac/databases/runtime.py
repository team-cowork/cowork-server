#!/usr/bin/env python3
"""Apply a module's database independently of application image deployment."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys
from urllib.parse import quote


SOURCE = Path(__file__).resolve().parent
PROD = SOURCE.parents[1] / 'prod'
PORTS = {'mysql': 3306, 'postgres': 5432, 'mongodb': 27017}
SECRET_KEYS = {'DB_PASSWORD', 'DB_ADMIN_PASSWORD', 'DB_MONITORING_PASSWORD'}


def catalog():
    result = json.loads((SOURCE / 'catalog.json').read_text())
    for module, value in result.items():
        if not re.fullmatch(r'[a-z][a-z0-9_]*', module) or value['engine'] not in PORTS:
            raise ValueError('Invalid module database catalog')
        if value['database'] != 'cowork_' + module or value['username'] != 'cowork_' + module:
            raise ValueError('Each database and account must belong to its module')
        for image in (value['image'], value['exporter']['image']):
            if not re.fullmatch(r'[a-zA-Z0-9_./:-]+@sha256:[0-9a-f]{64}', image):
                raise ValueError('Database and exporter images require digest pins')
        if not re.fullmatch(r'[1-9][0-9]*[mg]', value['memory']) or not 0 < value['cpus'] <= 16:
            raise ValueError('Invalid database resource limits')
        if value['exporter']['port'] != {'mysql': 9104, 'postgres': 9187, 'mongodb': 9216}[value['engine']]:
            raise ValueError('Invalid database exporter port')
    return result


def credentials(value):
    if set(value) != SECRET_KEYS or not all(isinstance(v, str) and v and not any(c in v for c in '\r\n\0') for v in value.values()):
        raise ValueError('Supply the three database passwords as nonempty single-line strings')
    if len(set(value.values())) != len(SECRET_KEYS):
        raise ValueError('Application, administrator and monitoring credentials must differ')
    return value


def write(path, content):
    temporary = path.with_suffix(path.suffix + '.new')
    with temporary.open('w') as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(content)
    temporary.replace(path)


def environment(path, value):
    write(path, ''.join(f'{key}={item}\n' for key, item in value.items()))


def run(*args, content=None, env=None, timeout=600):
    result = subprocess.run(args, input=content, text=True, capture_output=True, timeout=timeout,
                            env={**os.environ, **(env or {})})
    if result.returncode:
        # Database errors can echo statements and credentials.
        raise RuntimeError(f'{args[0]} command failed; inspect the module database on its VM')
    return result.stdout


def render(module, value, secrets, address, network, directory):
    name, engine = f'cowork-{module}-db', value['engine']
    admin, monitor = (secrets[key] for key in ('DB_ADMIN_PASSWORD', 'DB_MONITORING_PASSWORD'))
    if engine == 'mysql':
        db_env = {'MYSQL_ROOT_PASSWORD': admin, 'MYSQL_ROOT_HOST': 'localhost', 'MYSQL_DATABASE': value['database']}
        db_command = ['--mysqlx=0', '--innodb-buffer-pool-size=134217728', '--max-connections=100',
                      '--character-set-server=utf8mb4', '--collation-server=utf8mb4_unicode_ci']
        health = ['CMD', 'mysqladmin', '--protocol=tcp', '--host=127.0.0.1', 'ping', '--silent']
        mount = '/var/lib/mysql'
        exporter_env = {'MYSQLD_EXPORTER_PASSWORD': monitor}
        exporter_command = [f'--mysqld.address={name}:3306', '--mysqld.username=cowork_monitoring']
    elif engine == 'postgres':
        db_env = {'POSTGRES_PASSWORD': admin, 'POSTGRES_DB': value['database']}
        db_command = ['postgres', '-c', 'shared_buffers=128MB', '-c', 'max_connections=100']
        health = ['CMD', 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres', '-d', value['database']]
        mount = '/var/lib/postgresql'
        exporter_env = {'DATA_SOURCE_NAME': f'postgresql://cowork_monitoring:{quote(monitor, safe="")}@{name}:5432/{value["database"]}?sslmode=disable'}
        exporter_command = []
    else:
        db_env = {'MONGO_INITDB_ROOT_USERNAME': 'cowork_admin', 'MONGO_INITDB_ROOT_PASSWORD': admin}
        db_command = ['mongod', '--wiredTigerCacheSizeGB', '0.25']
        health = ['CMD-SHELL', 'test "$$(cat /proc/1/comm)" = mongod && mongosh --quiet --eval "if(db.runCommand({ping:1}).ok!==1)quit(1)"']
        mount = '/data/db'
        exporter_env = {'MONGODB_URI': f'mongodb://cowork_monitoring:{quote(monitor, safe="")}@{name}:27017/admin?authSource=admin'}
        exporter_command = ['--compatible-mode']
    environment(directory / 'database.env', db_env)
    environment(directory / 'exporter.env', exporter_env)
    common = {'restart': 'unless-stopped', 'networks': ['runtime'], 'logging': {
        'driver': 'json-file', 'options': {'max-size': '20m', 'max-file': '3'}}}
    db = {**common, 'image': value['image'], 'container_name': name,
          'env_file': [{'path': './database.env', 'format': 'raw'}], 'command': db_command,
          'mem_limit': value['memory'], 'cpus': value['cpus'], 'pids_limit': 256,
          'stop_grace_period': '60s',
          'volumes': [{'type': 'volume', 'source': 'data', 'target': mount, 'volume': {'nocopy': True}}],
          'healthcheck': {'test': health, 'interval': '5s', 'timeout': '5s', 'retries': 48, 'start_period': '30s'}}
    if engine == 'mongodb':
        # Standalone MongoDB does not use config-server data; avoid an anonymous image volume.
        db['tmpfs'] = ['/data/configdb']
    exporter = {**common, 'image': value['exporter']['image'], 'container_name': name + '-exporter',
                'env_file': [{'path': './exporter.env', 'format': 'raw'}], 'command': exporter_command,
                'ports': [f'{address}:{value["exporter"]["port"]}:{value["exporter"]["port"]}'],
                'mem_limit': '128m', 'cpus': 0.25, 'pids_limit': 64,
                'security_opt': ['no-new-privileges:true'], 'cap_drop': ['ALL'],
                'depends_on': {'database': {'condition': 'service_healthy'}}}
    compose = {'name': name, 'services': {'database': db, 'exporter': exporter},
               'volumes': {'data': {'external': True, 'name': name + '-data'}},
               'networks': {'runtime': {'external': True, 'name': network}}}
    write(directory / 'compose.json', json.dumps(compose, indent=2) + '\n')


def reconcile(module, value, secrets, admin):
    name, engine = f'cowork-{module}-db', value['engine']
    user, database = value['username'], value['database']
    app, next_admin, monitor = (secrets[key] for key in ('DB_PASSWORD', 'DB_ADMIN_PASSWORD', 'DB_MONITORING_PASSWORD'))
    literal = lambda text: "'" + text.replace("'", "''") + "'"
    if engine == 'mysql':
        sql = f"""SET SESSION sql_mode='NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS `{database}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '{user}'@'%' IDENTIFIED BY {literal(app)};
ALTER USER '{user}'@'%' IDENTIFIED BY {literal(app)};
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '{user}'@'%';
GRANT ALL PRIVILEGES ON `{database}`.* TO '{user}'@'%';
CREATE USER IF NOT EXISTS 'cowork_monitoring'@'%' IDENTIFIED BY {literal(monitor)};
ALTER USER 'cowork_monitoring'@'%' IDENTIFIED BY {literal(monitor)} WITH MAX_USER_CONNECTIONS 3;
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'cowork_monitoring'@'%';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'cowork_monitoring'@'%';
ALTER USER 'root'@'localhost' IDENTIFIED BY {literal(next_admin)};
"""
        run('docker', 'exec', '-i', '--env', 'MYSQL_PWD', name, 'mysql', '--binary-mode', '-u', 'root',
            content=sql, env={'MYSQL_PWD': admin}, timeout=60)
    elif engine == 'postgres':
        sql = f"""SET standard_conforming_strings=on;
DO $$ BEGIN
IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='{user}') THEN CREATE ROLE {user} LOGIN; END IF;
IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='cowork_monitoring') THEN CREATE ROLE cowork_monitoring LOGIN; END IF;
END $$;
ALTER ROLE {user} WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD {literal(app)};
ALTER DATABASE {database} OWNER TO {user};
REVOKE ALL ON DATABASE {database} FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO {user};
ALTER ROLE cowork_monitoring WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD {literal(monitor)} CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE {database} TO cowork_monitoring;
GRANT pg_monitor TO cowork_monitoring;
ALTER ROLE postgres PASSWORD {literal(next_admin)};
"""
        run('docker', 'exec', '-i', '--env', 'PGPASSWORD', name, 'psql', '-h', '127.0.0.1', '-U', 'postgres',
            '-d', database, '-v', 'ON_ERROR_STOP=1', '--no-password', '--no-psqlrc',
            content=sql, env={'PGPASSWORD': admin}, timeout=60)
    else:
        script = """const s=JSON.parse(require('fs').readFileSync(0,'utf8'));
const a=db.getSiblingDB('admin');
if(!a.auth('cowork_admin',s.admin))throw new Error('Administrator authentication failed');
function account(name,pwd,roles){const v={pwd,roles};if(a.getUser(name))a.updateUser(name,v);else a.createUser({user:name,...v});}
account(s.username,s.password,[{role:'readWrite',db:s.database}]);
account('cowork_monitoring',s.monitor,[{role:'clusterMonitor',db:'admin'},{role:'read',db:'local'}]);
a.changeUserPassword('cowork_admin',s.nextAdmin);
"""
        run('docker', 'exec', '-i', name, 'mongosh', '--quiet', '--norc', '--eval', script,
            content=json.dumps({'admin': admin, 'nextAdmin': next_admin, 'username': user,
                                'database': database, 'password': app, 'monitor': monitor}), timeout=60)


def apply(module, value, directory):
    if os.geteuid() != 0:
        raise ValueError('Database infrastructure apply requires root')
    os.umask(0o077)
    secrets = credentials(json.load(sys.stdin))
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    with (directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        slots = runpy.run_path(str(PROD / 'slots.py'))
        slot = slots['load'](module, PROD / 'slots')
        address = slot['container']['advertise_address']
        network = slots['host_network'](PROD)['name']
        policy = json.loads(Path('/etc/cowork/module-database-firewall/policy.json').read_text())
        if any(policy[key] != expected for key, expected in {
            'network': network, 'bind_ip': address, 'database_port': PORTS[value['engine']],
            'exporter_port': value['exporter']['port']}.items()):
            raise ValueError('Installed database firewall differs from the module catalog')
        run('systemctl', 'is-enabled', '--quiet', 'cowork-module-database-firewall.service')
        run('/usr/bin/python3', '-I', '/usr/local/libexec/cowork-module-database-firewall.py', 'restore')
        previous = json.loads((directory / 'credentials.json').read_text()) if (directory / 'credentials.json').exists() else {}
        render(module, value, secrets, address, network, directory)
        compose = ['docker', 'compose', '--env-file', '/dev/null', '-f', str(directory / 'compose.json')]
        run(*compose, 'config', '--quiet')
        # External data is created once. No application deployment references or removes it.
        run('docker', 'volume', 'create', f'cowork-{module}-db-data')
        run(*compose, 'up', '-d', '--wait', '--wait-timeout', '300', 'database')
        candidates = [secrets['DB_ADMIN_PASSWORD']]
        if previous.get('DB_ADMIN_PASSWORD') and previous['DB_ADMIN_PASSWORD'] not in candidates:
            candidates.append(previous['DB_ADMIN_PASSWORD'])
        for index, admin in enumerate(candidates):
            try:
                reconcile(module, value, secrets, admin)
                break
            except RuntimeError:
                if index == len(candidates) - 1:
                    raise
        write(directory / 'credentials.json', json.dumps(secrets) + '\n')
        run(*compose, 'up', '-d', '--wait', '--wait-timeout', '60', 'exporter')
        print(f'{module}: database accounts applied; durable volume retained; exporter started')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('check', 'apply'))
    parser.add_argument('module', nargs='?')
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    values = catalog()
    if args.module is not None and args.module not in values:
        raise ValueError('Unknown module database')
    if args.operation == 'check':
        print(f'Validated {len(values)} module database definitions; no runtime changes')
        return
    if args.module is None:
        raise ValueError('Database apply requires a module')
    directory = args.directory or Path('/root/cowork-iac/databases') / args.module
    apply(args.module, values[args.module], directory.resolve())


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(f'[module-database] {error}', file=sys.stderr)
        sys.exit(1)
