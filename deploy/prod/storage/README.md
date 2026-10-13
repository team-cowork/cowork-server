# 운영 SeaweedFS 제약

설정·배포 입력은 [runtime](./runtime.py), [Compose](./compose.yaml),
[IAM 템플릿](../../config/seaweedfs/s3-config.json.template),
[버킷 정책 적용](../../config/seaweedfs/configure-bucket.sh)을 기준으로 한다.
일반 CD는 기존 `/opt/cowork/storage` 데이터셋을 사용한다. VM의 root 방화벽 정책은 별도로 관리하며
주소·허용 peer 변경 전 갱신해야 한다. 최초 설치용 `bootstrap.py`는 일반 배포에 사용하지 않는다.

## 저장 방식과 복구

DB VM의 로컬 디스크 한 곳을 사용하며 노드 장애를 견디는 복제 구성이 아니다.
용량 설정은 디스크 사용량의 정확한 상한이 아니므로 호스트 전체 여유 공간도 확인해야 한다.
같은 데이터 디렉터리에 두 server를 동시에 실행하지 않는다. 새 버전이 데이터 형식을 변경할 수 있어
이전 이미지 재시작을 복원으로 간주하지 않는다. 백업·복원은 아직 검증하지 않았다.

이전 MinIO 데이터는 승인된 초기화에서 폐기했다. 현재 데이터셋은 SeaweedFS에서 새로 생성되었으며
MinIO의 객체·버전·KMS 상태를 이전한 데이터셋이 아니다.

## S3 호환성과 만료

SeaweedFS 4.47의 PublicAccessBlock API는 `501 NotImplemented`를 반환한다.
공개 아이콘 외 경로의 보호는 private ACL, 서비스별 권한과 버킷 정책에 의존한다.
PublicAccessBlock이 적용되었다고 판단하지 않는다.

Lifecycle worker가 healthy인 것과 객체의 실제 만료 완료는 별개다. 기본 탐지는 일 단위이므로
업로드 후 정확히 24시간에 삭제된다고 보장하지 않는다. 장기 만료 결과는 아직 관측하지 않았다.
구현 기준은 [4.47 S3 API](https://github.com/seaweedfs/seaweedfs/blob/4.47/weed/s3api/s3api_bucket_policy_handlers.go)와
[lifecycle worker](https://github.com/seaweedfs/seaweedfs/blob/4.47/weed/worker/tasks/s3_lifecycle/handler.go)다.
