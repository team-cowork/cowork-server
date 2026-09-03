# cowork-roadmap

## 역할

전공·포지션별 온보딩 로드맵과 학습 과제를 관리합니다.

- 공통·팀·프로젝트 범위의 로드맵 생성·조회·수정·삭제
- 트리형 노드와 문서·관련 자료 관리
- 멤버별 과제 출제와 진행 상태 관리

## 스택

- Java 25 / Spring Boot WebFlux
- Gradle
- Spring Data R2DBC / MySQL / Flyway (JDBC)
- Spring Kafka / Eureka / Config Client

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8088`        | `8088`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                     | 기본값                                   | 설명                                |
|--------------------------|------------------------------------------|-------------------------------------|
| `SPRING_PROFILES_ACTIVE` | `local`                                  | 설정 프로파일 (`local` 또는 `prod`) |
| `SPRING_CONFIG_IMPORT`   | `configserver:http://cowork-config:8761` | 필수 Config Server 연결             |

- Config Server: 포트, R2DBC·Flyway URL, Kafka topic·group, Eureka.
- Vault: MySQL 계정.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.
