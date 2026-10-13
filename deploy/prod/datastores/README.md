# 운영 데이터 저장소 CD

`prod-cd`의 `datastores` target은 Vault 다음에 MySQL, PostgreSQL, MongoDB, Redis,
Elasticsearch, Kafka를 배포한다. `cowork-datastores` 계정(UID/GID 10002)이 Docker를 사용한다.
DB 계정의 UID와 컨테이너 내부 엔진 UID는 이 호스트 계정과 별개이다. 공식 이미지의 entrypoint가
각 엔진의 사용자로 전환한다. 모니터링과 객체 저장소는 이 Compose 프로젝트에 포함하지 않는다.

## 설정과 권한

Vault `secret/deploy/datastores` 문서의 runtime에는 `DATASTORE_SETTINGS_JSON` 문자열만 둔다.
JSON 구조는 다음과 같다. 아래 문자열은 형식 설명이며 실제 인증 값으로 사용하지 않는다.

```json
{
  "bind_ip": "10.0.0.93",
  "mysql": {
    "root_password": "REPLACE_WITH_CURRENT_ROOT_PASSWORD",
    "user": "cowork",
    "password": "REPLACE_WITH_CURRENT_APP_PASSWORD"
  },
  "postgres": {"user": "root", "password": "REPLACE_WITH_CURRENT_PASSWORD"},
  "mongodb": {"user": "root", "password": "REPLACE_WITH_CURRENT_PASSWORD"},
  "kafka": {"cluster_id": "REPLACE_WITH_CLUSTER_ID"}
}
```

모든 필드는 필수이다. 중복 키, 미지 필드, 빈 인증 값, 제어문자, 유효하지 않은 private IPv4와
Kafka cluster ID를 거부한다. Kafka cluster ID는 기존 데이터의 22자리 URL-safe ID이다.
암호의 `$`, 따옴표, `#`, 공백은 Compose의 raw env 파일로 전달한다. Docker Compose 2.30 이상이 필요하다.

CD 계정에는 Docker 접근 권한, 자신의 home/release/snapshot 디렉터리에 대한 권한과 다음 고정 명령의
sudo 권한만 설정한다. 배포 소스 자체를 sudo로 실행하지 않는다.

```text
/usr/bin/python3 -I /usr/local/libexec/cowork-datastore-firewall.py check-binding
```

runtime은 사설 IP, TCP 3306/5432/27017/6379/9094/9200의 host/container binding, 기존 네트워크 이름을
stdin으로 전달한다. 설치된 root 정책이 이 집합과 정확히 일치해야 한다. 포트별 허용 peer와 직접 bridge
접근 제한은 root 정책에서 관리한다. Vault 값으로 방화벽 허용 범위를 넓히지 않는다.

`apply-settings.py`가 만든 SHA별 private snapshot에 env 파일을 mode 0600으로 생성한다. Docker에
전달되는 값은 해당 Vault snapshot에서만 읽는다. 일반 호스트 `.env` 파일을 병합하지 않는다.
실패 명령의 출력은 계정의 private `~/.local/state/cowork/datastores-diagnostics`에 남긴다.
공개 CI 로그에는 단계와 진단 파일 경로만 기록한다.

## 초기 데이터셋 등록

일반 CD를 사용하기 전에 운영자가 현재 데이터셋을 준비하고 다음 항목을 등록한다.

- `/etc/cowork`: root 소유이며 다른 사용자가 쓸 수 없는 디렉터리. 일반적인 mode는 0755이다.
- `/etc/cowork/datastores-bootstrap.json`: root 소유의 읽기 가능한 일반 파일. 일반적인 mode는 0644이다.
- 기존 `cowork-server_default` Docker bridge 네트워크.
- 아래 외부 named volume 7개. 일반 CD는 이 볼륨을 생성하거나 삭제하지 않는다.

| 엔진 | 볼륨 | 컨테이너 경로 |
|---|---|---|
| MySQL | cowork-prod-mysql-data | /var/lib/mysql |
| PostgreSQL | cowork-prod-postgres-data | /var/lib/postgresql |
| MongoDB | cowork-prod-mongo-data | /data/db |
| MongoDB | cowork-prod-mongo-configdb | /data/configdb |
| Redis | cowork-prod-redis-data | /data |
| Elasticsearch | cowork-prod-elasticsearch-data | /usr/share/elasticsearch/data |
| Kafka | cowork-kafka-data-20261013-fresh | /var/lib/kafka/data |

부트스트랩 문서는 `schema_version: 1`, `network: {name, id}`, `volumes`, `kafka_cluster_id`, `engines`를 갖는다.
`volumes`는 위 이름을 키로 사용하고 각 값에 Docker의 `created_at`, `driver`, `mountpoint`를 기록한다.
`engines`는 서비스 이름을 키로 사용하고 각 값에 `major`, `bootstrap_container_id`, `bootstrap_image_id`를
기록한다. runtime은 현재 volume의 CreatedAt·driver·mountpoint, network ID, Kafka cluster ID와
엔진 major를 비교한다. 같은 이름으로 볼륨이 교체되어도 배포를 거부한다. 다른 컨테이너가 엔진의
데이터 볼륨을 공유해도 거부한다.

이 기록은 승인된 초기화 또는 데이터 이동 후 운영자가 갱신한다. 일반 CD 계정은 수정할 수 없다.
운영자가 기존 볼륨 내부를 직접 지우는 작업까지 CreatedAt으로 감지할 수는 없다. 데이터 삭제·복구는
업무 서비스와 projection의 정지·재구성 절차를 함께 수행해야 한다.

MySQL의 7개 DB와 권한 및 PostgreSQL의 `cowork_preference`/`preference` 스키마는 최초 구성에서
현재 `deploy/config/mysql/init.sh`, `deploy/config/postgres/init.sh`를 적용한다. 일반 CD에는 init
mount를 넣지 않는다. PostgreSQL18의 실제 PGDATA는 `/var/lib/postgresql/18/docker`이고 상위 경로에
볼륨을 연결한다. 이전 PG16의 데이터 경로와 다르다.

## 최초 Compose 소유권 전환

기존에 `docker create`로 만든 엔진에는 Compose 소유 label이 없다. 일반 CD는 이 컨테이너를 자동으로
삭제하거나 이름을 바꾸지 않고 중단한다. 운영자는 현재 앱과 GitHub App 등 writer를 정지한 상태에서
다음 절차를 한 번 수행한다.

1. 검토한 SHA와 Vault 설정을 렌더링하고 root 정책, 등록된 볼륨, network ID, Kafka cluster ID를 확인한다.
2. 지정 이미지 다운로드와 Elasticsearch 빌드를 모두 끝낸다. 실패하면 기존 엔진을 유지한다.
3. 여섯 기존 컨테이너의 전체 ID·image ID를 부트스트랩 기록과 비교한다. 이름, 데이터 마운트와 전용
   볼륨 참조도 확인한다. 하나라도 다르면 정지·삭제하지 않는다.
4. 해당 컨테이너만 정상 종료하고 제거한다. `docker rm -v`, `compose down -v`, volume prune을 사용하지 않는다.
5. 같은 외부 볼륨으로 `cowork-datastores` Compose 프로젝트를 시작한다. 인증, 엔진 health, Kafka cluster ID와
   토픽 계약을 확인한 후 현재 앱을 시작한다.

이 과정은 업무 데이터를 복사하거나 초기화하지 않는다. 실패하면 현재 상태와 private 진단을 확인하고
앞으로 복구한다. 이전 엔진 이미지를 데이터 볼륨에 자동으로 다시 연결하지 않는다.

## 일반 배포

`check`는 설정 렌더링, Compose 문법·구조, 등록된 volume/network와 설치된 방화벽 정합성을 검사한다.
이미지를 다운로드하거나 빌드하지 않으며 엔진을 시작하거나 topic을 바꾸지 않는다. 엔진 인증과 실제
기동 성공을 확인한 결과로 해석하지 않는다.

`deploy`는 같은 검증 후 일반 Compose 소유권을 확인하고 필요한 이미지를 다운로드한다.
Elasticsearch는 선택한 SHA의 `deploy/images/elasticsearch/Dockerfile`로 빌드한다. 나머지 엔진의
이미지는 같은 SHA의 `deploy/compose/stack.yaml`에서 version+digest를 읽는다. Elasticsearch tag는
Dockerfile 내용의 hash를 사용하므로 release SHA만 바뀌면 같은 이미지를 가리킨다.

모든 이미지 준비가 끝난 뒤 `compose up -d --wait --no-build --pull never`를 수행한다.
`--force-recreate`를 사용하지 않는다. snapshot env 경로는 컨테이너에 bind하지 않으므로 snapshot
디렉터리만 바뀌어도 설정 hash가 바뀌지 않는다. 현재 엔진 health, 이미지, 사설 binding, 데이터 mount와
Kafka cluster ID를 확인한다. 실패하면 중단하고 자동 이미지 rollback을 수행하지 않는다.

환경 변수의 암호를 바꾸어도 기존 MySQL/PostgreSQL/MongoDB 사용자 암호가 회전되지는 않는다.
인증 변경은 DB 사용자 설정과 Vault 값을 함께 갱신하는 별도 운영 절차이다. health 검사는 현재 앱 인증을
사용하므로 값이 불일치하면 배포 완료로 처리하지 않는다.

major 버전이 부트스트랩 기록과 다르면 배포를 거부한다. minor 버전 변경도 기존 데이터 포맷의 호환성과
downgrade 제한을 운영자가 확인해야 한다. major 변경은 공식 업그레이드 절차 또는 승인된 빈 데이터셋
재구성을 먼저 설계하고 부트스트랩 기록을 갱신한다. 새 이미지의 최초 시작이 데이터 포맷을 바꿀 수 있으므로
컨테이너를 예전 이미지로 되돌리는 것을 데이터 복구로 간주하지 않는다.

## Kafka 토픽

토픽 이름과 compact 목록은 같은 SHA의 `stack.yaml`에서 읽는다. 현재 카탈로그는 52개이며 compact는
13개이다. `chat.message.v2`의 delete/retention 정책도 같은 소스에서 읽는다. 파티션/RF는 현재 각1이다.
source의 전역 파티션·복제 설정이나 provisioning 형식이 바뀌면 자동 적용하지 않고 검토를 요구한다.

기존 토픽의 파티션·RF·cleanup/retention 계약을 먼저 읽는다. 값이 다르면 중단하며 자동으로 고치지 않는다.
없는 토픽만 필요한 정책을 지정하여 생성하고 다시 확인한다. source에서 빠진 토픽도 삭제하지 않는다.
기존 토픽 UUID, 데이터, group offset을 초기화하지 않는다. 토픽 생성만으로 projection snapshot이
완료된 것은 아니며, 이후 앱이 자신의 replay/readiness 조건을 충족해야 한다.
