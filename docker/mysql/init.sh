#!/bin/bash
set -e

mysql -u root -p"${MYSQL_ROOT_PASSWORD}" <<-EOSQL
    CREATE DATABASE IF NOT EXISTS cowork_authorization DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_user          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_team          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_project       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    CREATE DATABASE IF NOT EXISTS cowork_channel       DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

    GRANT ALL PRIVILEGES ON cowork_authorization.* TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_user.*          TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_team.*          TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_project.*       TO '${MYSQL_USER}'@'%';
    GRANT ALL PRIVILEGES ON cowork_channel.*       TO '${MYSQL_USER}'@'%';

    FLUSH PRIVILEGES;
EOSQL
