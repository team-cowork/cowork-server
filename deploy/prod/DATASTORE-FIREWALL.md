# 운영 DB 접근 제한

DB 포트를 사설 주소에 게시하는 것만으로는 GSMSV의 공유 사설망에서 접근을 제한할 수 없다. 이 방화벽은 포트별 허용 주소를 root 소유 정책으로 관리한다. 일반 `datastores` CD는 설치된 정책과 배포 포트가 일치하는지 확인하고 같은 정책을 복원한다.

## 설치 정책

관리자는 검토한 다음 파일을 root 소유 staging 디렉터리에 복사한다. 디렉터리와 상위 경로는 다른 사용자에게 쓰기 권한이 없어야 한다. 정책 파일 권한은 `0600`이다.

- `bootstrap-datastore-firewall.sh`
- `datastore-firewall.py`
- `datastore-firewall.service`
- `policy.json`

`policy.json` 형식은 다음과 같다. 예시의 포트와 peer는 전체 구성이 아니므로 그대로 설치하지 않는다. 실제 여섯 개 게시 포트와 각 포트의 사용자를 모두 지정한다.

```json
{
  "bind_ip": "10.0.0.93",
  "network": "cowork-server_default",
  "bridge": "br-75264de57517",
  "subnet": "172.18.0.0/16",
  "bindings": [
    {"host_port": 3306, "container_port": 3306, "peers": ["10.0.0.74/32"]}
  ],
  "internal_ports": [9092, 9093, 33060, 9300]
}
```

검토한 root staging에서 `sudo bash ./bootstrap-datastore-firewall.sh`를 실행한다. 도구는 정책을 검증하고 `/etc/cowork/datastore-firewall/policy.json`과 고정 helper를 원자적으로 설치한다. 정책을 직접 적용한 다음 unit을 enable/start한다. Docker와 이미 실행 중인 unit을 restart하지 않는다.

`cowork-datastore-firewall.service`는 Docker의 시작 필수 조건이다. 일반 CD 계정에는 다음 고정 명령만 sudo로 허용한다.

```sh
sudo -n /usr/bin/python3 -I /usr/local/libexec/cowork-datastore-firewall.py check-binding
```

명령의 표준 입력은 `bind_ip`, `bindings`의 `host_port`·`container_port`, `network`만 받는다. 게시 tuple 집합과 network 이름은 root 정책과 정확히 일치해야 한다. 실제 Docker bridge 이름과 subnet도 대조한다. 일반 CD는 peer 주소, 정책 경로, 실행 파일 경로를 전달하거나 변경할 수 없다.

## 적용 범위

IPv4 INPUT과 DOCKER-USER에서 원래 요청 방향만 검사한다. 게시 포트와 컨테이너 포트를 직접 향하는 경로를 모두 제한한다. 각 DB 포트는 자체 허용 주소 목록을 사용한다. DB 호스트 자체 주소는 허용한다. DB 내부 컨테이너는 입력 인터페이스와 source subnet이 설치 정책에 모두 일치할 때 허용한다. 따라서 기존 로컬 exporter와 Kafka 내부 통신은 유지된다.

IPv6에는 허용 주소를 정의하지 않는다. 같은 예약 포트의 외부 INPUT과 Docker bridge 방향 FORWARD를 차단한다. IPv6를 사용하는 새 구성이 필요하면 관리자 정책을 먼저 변경해야 한다.

`iptables-restore --noflush`는 각 주소 계열의 filter table에 한 번씩 적용한다. 도구 소유 chain과 jump만 교체하므로 기존 S3 방화벽, SSH, 모니터링 포트는 유지된다. 모니터링의 설정과 키는 변경하지 않는다.

운영 게시 포트는 MySQL `3306`, PostgreSQL `5432`, MongoDB `27017`, Redis `6379`, Kafka 외부 listener `9094`, Elasticsearch `9200`이다. Kafka 내부·controller `9092`·`9093`, MySQL X Protocol `33060`, Elasticsearch transport `9300`은 DB 호스트와 현재 로컬 bridge에서만 허용한다.

2026-10-13 운영 정책의 외부 peer 목록은 다음과 같다. 모든 주소는 `10.0.0.<번호>/32`이다. DB 자체 주소 `.93`과 고정 로컬 bridge의 허용은 별도로 적용한다.

| 포트 | 허용 VM 주소의 마지막 번호 |
| --- | --- |
| MySQL 3306 | Authorization 140, User 144, Channel 97, Project 88, Team 74, Notification 131, Roadmap 132, Monitoring 137 |
| PostgreSQL 5432 | Preference 89, Monitoring 137 |
| MongoDB 27017 | Chat 98, Voice 90, Monitoring 137 |
| Redis 6379 | Gateway 127, User 144, Preference 89, Chat 98, GitHub App 150, Monitoring 137 |
| Elasticsearch 9200 | Chat 98 |
| Kafka 9094 | Config 169, Gateway 127, Authorization 140, User 144, Channel 97, Project 88, Team 74, Notification 131, Chat 98, Roadmap 132, Voice 90, Preference 89, GitHub App 150, Monitoring 137 |

Config는 Spring Cloud Bus의 Kafka 연결을 사용한다. Config에는 다른 DB 포트를 허용하지 않는다. Monitoring에는 저장소의 exporter 구성에 필요한 다섯 개 포트만 허용한다.

Docker network를 다시 생성하면 bridge 이름이 달라질 수 있다. 일반 CD는 이를 자동으로 허용하지 않고 중단한다. 관리자가 새 network를 검토한 후 root 정책을 다시 설치한다. 기존 `cowork-server_default`는 정상 CD에서 유지한다.

설치 후 허용 VM의 연결 성공과 비허용 VM의 연결 차단을 관측한다. Docker PID, 기존 컨테이너 상태, 수집기 응답도 대조한다. 이 정책은 허용된 VM 내부의 서비스 인증을 대신하지 않는다.
