# 전체 소실과 개별 서비스 소실 알림의 중복 제거

- **서비스**: Prometheus, Alertmanager
- **우선순위**: 🟡 낮음
- **현재 상태**: discovery target이 0개가 되면 `coworkServiceDiscoveryEmpty`와 함께 `coworkServiceMissing`(서비스별 11개)이 동시에 critical로 울린다
- **관련 작업**: [수집 대상 소실 감지 추가](../../20261014_TODO.md) (PR #485 리뷰에서 파생)

## 문제

`deploy/config/monitoring/prometheus/rules/application-alerts.yml`의 `coworkServiceDiscoveryEmpty`는
`up{job="cowork-services"}` 전체가 사라졌을 때 5분 뒤 울린다. 같은 조건에서
`deploy/prod/monitoring/render-prometheus.py`가 application slot 목록으로부터 생성하는
`coworkServiceMissing`(`rules.json`, 3분, critical)도 slot 11개 각각에서 함께 울린다.

두 알림은 서로 다른 것을 본다 — 하나는 "discovery 자체가 비었다", 다른 하나는
"이 서비스 하나가 안 보인다" — 는 점에서 역할이 겹치지 않는다는 주장도 가능하지만, discovery가
완전히 비는 경우(Eureka 응답은 정상인데 등록 인스턴스가 0개이거나, Eureka가 죽은 상태로
Prometheus가 재시작된 경우)에는 11개 서비스 알림과 전체 소실 알림이 동시에 Discord로 전송되어
사실상 같은 장애를 12번 알리는 것과 다르지 않다. 현재 `deploy/config/monitoring/alertmanager/alertmanager.yml`에는
`inhibit_rules`가 정의되어 있지 않다.

## 선택지

| 방향 | 내용 |
|------|------|
| Alertmanager inhibit | `coworkServiceDiscoveryEmpty`(critical)가 firing 중이면 같은 `severity=critical` 그룹의 `coworkServiceMissing`을 억제하는 `inhibit_rules`를 추가한다 |
| 현행 유지 | "전체 소실"과 "개별 서비스 소실"을 별도 신호로 계속 구분해서 받는다 |

## 할 일

- `inhibit_rules`를 추가하기로 하면 `alertmanager.yml`의 `route`와 함께 `source_matchers`/`target_matchers`를
  정의하고, `coworkServiceDiscoveryEmpty` 발생 시 `coworkServiceMissing`이 억제되는지 로컬 Alertmanager로 확인한다.
- 현행 유지로 결론 내면 이 문서를 완료 처리하고 두 알림이 의도적으로 분리되어 있다는 점을
  `application-alerts.yml`의 기존 주석 수준으로 남긴다.

## 검증

- 로컬 Alertmanager에 두 알림을 동시에 fire시켜 inhibit 적용 전/후 Discord 수신 알림 개수를 비교한다.

## 완료 조건

- discovery 전체 소실 시 Discord로 전송되는 알림이 의도한 개수(억제 적용 시 1개, 현행 유지 결론 시 현재 그대로)로 확정되어 있다.
