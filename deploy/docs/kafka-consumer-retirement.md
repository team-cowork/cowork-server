# Kafka consumer 그룹 전환 후 정리

운영 CD는 애플리케이션 배포가 모두 성공한 뒤 `retire-kafka-groups` 작업을 실행합니다.
배포한 소스의 `deploy/prod/kafka/retirements.json`에 선언되어 있고, 이번 배포에 포함된
consumer 서비스의 이전 그룹만 정리합니다. `check_only` 실행에서는 정리하지 않습니다.

## 전환 목록 등록

`retirements.json`에 consumer 서비스와 이전·새 그룹 및 토픽을 정확한 이름으로 등록합니다.
그룹 이름에 버전이 있다는 이유만으로 정리 대상을 추론하지 않습니다.

목록 등록 전에는 새 consumer가 필요한 기존 데이터와 삭제 기록을 복구할 수 있는지 확인해야 합니다.
새 토픽 준비, producer 전환, 필요한 데이터 변환과 snapshot 발행은 해당 기능 변경에 포함합니다.
이 절차는 서로 다른 토픽의 내용이 동일한지 비교하거나 미발행 outbox를 옮기지 않습니다.

Chat이 `REBUILD_REQUIRED`라면 원인을 확인하고 필요한 stream의 관리자 rebuild API를 먼저 호출합니다.
재구축과 snapshot 반영이 완료되어 readiness를 통과해야 그룹 정리가 진행됩니다.

## 운영 실행과 확인

CD는 기존 `prod-iac` Vault 역할과 검증된 SSH host key를 사용합니다. 소비자 컨테이너가 이번
배포 SHA로 실행 중이고 readiness 응답이 정상인지 다시 확인한 뒤 Kafka VM에서 정리합니다.
전체 운영 CD의 동시 실행 제한은 정리 작업이 끝날 때까지 유지됩니다. 수동 배포와 함께 실행하지 않습니다.

수동 확인은 Kafka VM에 검토한 스크립트와 목록을 함께 전달한 뒤 수행합니다. 기본 동작은 조회입니다.

```sh
sudo python3 retire-consumer-groups.py --service chat --catalog /absolute/path/retirements.json
```

삭제하려면 같은 명령에 `--apply`를 추가합니다. 수동 실행 시에도 대상 애플리케이션의 배포 버전과
readiness를 먼저 확인해야 합니다. 스크립트 단독 실행은 Kafka 상태만 검사합니다.

이전 그룹은 비활성 상태여야 하며, 새 그룹의 모든 파티션이 할당되고 처음 관측한 high-watermark까지
커밋되어 있어야 합니다. 토픽 식별자와 파티션 구성이 관측 사이에 바뀌거나, offset·할당 정보가
누락되면 삭제를 중단합니다. 빈 토픽의 미커밋 상태도 정상 소비로 간주하지 않습니다.

삭제 전 관측 기록과 이전 offset은 Kafka VM의 `/var/lib/cowork/kafka-retirements`에 보관합니다.
이미 삭제된 그룹은 건너뛰며, 실패한 정리는 운영 CD에 경고로 남습니다. 정상 배포를 rollback하지 않고
다음번 해당 애플리케이션의 성공 배포에서 다시 시도합니다. 일부 그룹만 먼저 정리됐을 수 있으므로
재시도 전 로그와 보관 기록을 확인합니다.

## 정리 범위와 rollback

이 절차는 consumer 그룹과 그 그룹의 broker offset만 삭제합니다. 토픽·메시지·애플리케이션의
projection/checkpoint는 삭제하거나 초기화하지 않습니다. 구 토픽을 사용하는 다른 consumer가
있을 수 있으므로 토픽 폐기는 별도 작업으로 수행합니다.

구버전으로 rollback하면 삭제한 그룹이 다시 생성될 수 있습니다. 그룹 offset 복원만으로
projection의 연속성이 보장되지는 않습니다. rollback 전 보관한 offset과 해당 버전의 checkpoint,
토픽 보존 범위를 함께 확인하고 서비스별 복구 절차를 적용합니다.
