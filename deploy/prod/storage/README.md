# 운영 SeaweedFS

이 디렉터리는 애플리케이션 전용 SeaweedFS의 최초 설치와 일반 CD를 제공한다. `prod-cd`의 `storage`
target은 Vault 다음, Config 이전에 배포한다. 최초 VM 준비가 끝나면 일반 배포는 `cowork-storage`
계정과 Vault `secret/deploy/storage` 문서로 실행한다. 계정·권한·GitHub 환경은
[운영 스토리지 GitHub CD](../../../docs/deployment.md#운영-스토리지-github-cd)에 정의한다.

`compose.yaml`은 로컬 스택과 같은 SeaweedFS 4.47 및 AWS CLI digest를 사용한다. 스토리지·admin·
`s3_lifecycle` worker를 함께 실행한다. 데이터, admin 작업 상태, worker 상태를 각각 저장한다.
master·volume·filer·admin·worker 관리 포트는 같은 네트워크 namespace의 loopback에 바인딩한다.
S3와 함께 실행되는 내부 gRPC 19000 포트는 bridge 안에서 수신하지만 호스트에 게시하지 않으며
방화벽으로 직접 bridge 접근을 차단한다. 호스트에는 사설 IPv4의 S3 9000 포트만 게시한다.
컨테이너는 UID/GID 10001로 실행한다.

## 최초 구성

스토리지 VM에 저장소의 승인된 소스를 배치하고, 다음 구조의 비밀 JSON 파일을 mode 0600으로 준비한다.
예시 문자열을 실제 키로 사용하지 않는다. `admin`에는 새 관리자 키를 넣는다. 서비스 키는 해당 서비스의
Vault 앱 문서와 일치해야 한다. Gateway에는 키를 전달하지 않는다.

```json
{
  "bind_ip": "10.0.0.93",
  "allowed_cidrs": ["10.0.0.127/32", "10.0.0.74/32", "10.0.0.144/32", "10.0.0.98/32", "10.0.0.93/32"],
  "bucket": "cowork-bucket",
  "region": "us-east-1",
  "public_web_origins": ["https://service.gsmsv.site:22118"],
  "identities": {
    "admin": {"access_key": "REPLACE_WITH_GENERATED_KEY", "secret_key": "REPLACE_WITH_GENERATED_SECRET"},
    "team": {"access_key": "REPLACE_WITH_TEAM_KEY", "secret_key": "REPLACE_WITH_TEAM_SECRET"},
    "user": {"access_key": "REPLACE_WITH_USER_KEY", "secret_key": "REPLACE_WITH_USER_SECRET"},
    "chat": {"access_key": "REPLACE_WITH_CHAT_KEY", "secret_key": "REPLACE_WITH_CHAT_SECRET"}
  }
}
```

준비 명령은 설정과 빈 데이터 디렉터리를 만든다. 기존 데이터 디렉터리의 내용은 삭제하지 않는다.

```sh
sudo python3 deploy/prod/storage/bootstrap.py prepare --settings /root/cowork-storage.json
```

기존 엔진에서 전환할 때는 모든 구버전 업로더를 먼저 중지한다. 이전 스토리지 컨테이너의 정확한 ID,
데이터 볼륨, KMS 의존성, 방화벽 unit을 확인하고 승인된 초기화 범위에 따라 제거한다. MinIO의 객체,
버전, 암호화 상태를 새 데이터 디렉터리에 복사하지 않는다. 이전 방화벽을 제거하기 전에 새 방화벽을
설치하여 제한이 없는 시간을 만들지 않는다. 다른 서비스가 사용하는 볼륨·네트워크·방화벽 규칙은 유지한다.

```sh
sudo python3 deploy/prod/storage/bootstrap.py apply
```

이 명령은 전용 방화벽을 적용한 후 스토리지를 시작하고 기존
[`configure-bucket.sh`](../../config/seaweedfs/configure-bucket.sh)로 버킷·접근 정책·CORS·만료 규칙을
설정한다. 그다음 admin과 lifecycle worker를 시작한다. 단계가 실패하면 명령은 중단하며, 기존 엔진을
자동 복구하거나 데이터 저장소를 삭제하지 않는다. 실패 원인을 확인한 뒤 같은 명령을 다시 실행한다.

## 적용 후 확인

- 버킷은 애플리케이션 전용이다. 초기화 전환 직후 객체와 이전 버전이 없어야 한다.
- Team·User·Chat 키의 경로 범위는 `s3-config.json.template`과 일치해야 한다. 공개 읽기는
  `team-icons/*`만 허용한다. 비공개 경로의 익명 접근, 익명 목록·쓰기, 다른 서비스의 비공개 경로 접근은 거부해야 한다.
- 버킷의 private ACL, 정책, CORS, lifecycle을 S3 API로 다시 읽는다. CORS에는 실제 운영 웹
  origin만 있어야 한다. 임시 업로드 세 접두사에는 1일 만료가 있고, 확정 접두사에는 이 규칙이 없어야 한다.
- `seaweedfs-lifecycle`이 healthy이고 admin에 `s3_lifecycle` 작업이 활성화되어 있는지 확인한다.
  worker 준비 상태는 만료 작업 완료를 의미하지 않는다. admin의 작업 결과를 별도로 확인한다.
- Gateway·Team·User·Chat에서 사설 S3에 도달하고, 그 밖의 VM에서는 차단되는지 확인한다.
  master·filer·admin·worker 포트와 이전 MinIO 콘솔 9002 포트는 외부에 열려 있으면 안 된다.
- 앱 배포 후 공개 아이콘, 서명된 비공개 파일, 임시 업로드·확정·범위 다운로드를 실제 경로에서 확인한다.
  객체와 DB를 모두 초기화했다면 앱 API가 만든 새 객체만 남아야 한다.

SeaweedFS 4.47의 PublicAccessBlock API는 501 `NotImplemented`를 반환한다. 구성 스크립트는 이
정확한 오류만 허용하고 미지원 사실을 출력한다. 이 기능이 적용됐다고 기록하지 않는다. 세 서비스는
별도 `WriteAcp` 권한을 받지 않으며, 버킷 정책에도 세 서비스 principal의 객체·버킷 ACL 변경 거부를
명시한다. 버킷 자체의 ACL은 private으로 유지한다. [4.47 구현](https://github.com/seaweedfs/seaweedfs/blob/4.47/weed/s3api/s3api_bucket_policy_handlers.go)을 기준으로 하며,
공식 wiki의 지원 표만으로 이 API가 동작한다고 판단하지 않는다.

## 유지 관리

`/opt/cowork/storage`에는 최초 설정과 영속 데이터 디렉터리를 남긴다. 일반 CD는 선택한 GitHub SHA와
Vault 문서에서 private snapshot을 만들고 기존 데이터 경로를 연결한다. 비밀 파일은 저장소에 추가하지
않는다. 서버가 재생성되면 admin과 worker도 재생성하여 새 네트워크 namespace에 연결한다.

`prepare`와 `apply`는 관리자가 최초 설치 또는 승인된 재구성 때 실행한다. `prepare`는 설치 경로를
root 전용으로 설정하므로 완료 후 CD 계정의 부모 경로 통과 권한을 다시 확인한다. 일반 CD는 두 명령을
호출하지 않는다. 방화벽 bind IP·허용 peer가 바뀌면 관리자가 고정 root 정책을 먼저 갱신해야 한다.
일반 CD의 `check-binding`은 Vault 값과 고정 정책의 일치를 확인한 뒤 기존 정책만 다시 적용한다.

`cowork-storage-firewall.service`는 Docker보다 먼저 규칙을 복구한다. 복구 실패 시 Docker 시작을
막는다. 직접 bridge 접근은 고정 bridge 이름 `br-cowork-s3`에 대한 규칙으로 제한하므로 컨테이너 IP가
바뀌어도 정책은 유지된다. 이 이름의 네트워크는 다른 용도로 사용하지 않는다.

단일 DB VM의 로컬 디스크를 사용하므로 노드 장애를 견디는 복제 구성이 아니다. 논리 volume은 1 GiB,
최대 8개이며 여유 공간이 2 GiB 아래로 떨어지면 새 쓰기를 제한한다. 이는 디스크 사용의 정확한 상한이
아니다. 용량 변경은 호스트 전체의 남은 공간을 확인한 뒤 적용한다.

SeaweedFS 4.47의 [서버 옵션](https://github.com/seaweedfs/seaweedfs/blob/4.47/weed/command/server.go)과
[lifecycle worker 기본값](https://github.com/seaweedfs/seaweedfs/blob/4.47/weed/worker/tasks/s3_lifecycle/handler.go)이
실행 기준이다. 기본 만료 탐지는 일 단위이므로 업로드 후 정확히 24시간에 삭제된다고 보장하지 않는다.
