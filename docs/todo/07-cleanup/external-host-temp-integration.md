# 외부 호스트 임시 연동 설정 제거

- **서비스**: cowork-gateway
- **우선순위**: 🟢 낮음 (정식 서비스로 편입되거나 필요성이 없어지는 시점)

## 문제

`ExternalHostProperties`는 홈서버 등 Eureka에 등록되지 않은 외부 호스트로의 임시 연동을 위한 설정이다. `EXTERNAL_HOST_URL` 환경변수가 비어 있으면 관련 라우트/헬스체크/Swagger 연동이 전부 비활성화되지만, 값이 설정되어 있는 동안에는 임시 방편으로 계속 운영된다.

**관련 파일**

- `cowork-gateway/src/main/kotlin/com/cowork/gateway/config/ExternalHostProperties.kt`
- `cowork-gateway/src/main/kotlin/com/cowork/gateway/config/ExternalRouteConfig.kt`
- `cowork-gateway/src/main/kotlin/com/cowork/gateway/controller/HealthCheckController.kt`
- `cowork-gateway/src/main/kotlin/com/cowork/gateway/controller/SwaggerUiController.kt`
- `docker-compose.yml` (`prometheus-external-target-writer` 서비스, gateway의 `EXTERNAL_HOST_*` 환경변수)
- `cowork-monitoring/prometheus/prometheus.yml` (`cowork-external-services` job)

## 해결

외부 호스트가 정식 서비스로 편입되거나 더 이상 필요하지 않아지면 아래 연동 지점을 함께 제거한다.

- `ExternalHostProperties` 클래스 및 `external.host.*` 설정
- `ExternalRouteConfig`의 라우트 등록 로직
- `HealthCheckController`/`SwaggerUiController`의 외부 호스트 연동 지점
- `docker-compose.yml`의 `prometheus-external-target-writer` 서비스와 gateway `EXTERNAL_HOST_*` 환경변수
- `prometheus.yml`의 `cowork-external-services` job 및 `sd/blackbox/external-home.json`, `sd/external-home-metrics.json` 생성 로직
