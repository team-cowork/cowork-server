# Infrastructure Exporters

루트 `docker-compose.yml`에는 다음 인프라 exporter가 실제로 구성되어 있으며, Prometheus가 고정 target으로 수집합니다.

| Exporter            | 대상                  | 포트   |
|---------------------|-----------------------|--------|
| `mysqld_exporter`   | MySQL                 | `9104` |
| `redis_exporter`    | Redis                 | `9121` |
| `kafka_exporter`    | Kafka broker·consumer | `9308` |
| `postgres_exporter` | PostgreSQL            | `9187` |
| `mongodb_exporter`  | MongoDB               | `9216` |

서비스 런타임 메트릭은 각 서비스의 `/actuator/prometheus` 또는 `/metrics`에서 직접 수집합니다. 호스트·컨테이너 자원용 `node-exporter`와 cAdvisor는 현재 Compose에 포함되어 있지 않습니다.
