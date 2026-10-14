# 저장소 밖의 운영 조건

GSM SV의 VM 생성·사양·공개 포트 전달은 패널에서 관리한다. IaC는 기존 VM의 설정과 모니터링을
관리하며, Docker Engine·Python 3·SSH 접속·관리자 권한이 준비되어 있어야 한다.

## 설정 변경과 복구

- 최초 Vault 신뢰 설정은 외부 관리자 토큰으로 [bootstrap-vault.py](../deploy/iac/scripts/bootstrap-vault.py)를
  실행한다. Vault는 GitHub에서 적용한 설정과 버전을 보관한다.
- [설정 선언](../deploy/prod/configuration.json)의 공유 입력은 repository Variables·Secrets에,
  모듈 전용 입력은 `Prod-CD(<module>)` 환경에 둔다. 일반 값은 Variable, 비밀 값은 Secret을 사용한다.
  공유 입력과 같은 이름을 모듈 환경에 등록하지 않는다. GitHub는 환경 값을 우선 적용한다.
- 값을 수정한 뒤 기존 `Cowork Prod CD Workflow`를 main에서 실행하고 `service`를 선택한다.
  `sha`를 비우면 현재 배포 이미지를 재사용한다. 공유 값이 변경되면 해당 값을 쓰는 서비스도 적용 대상에 포함된다.
  값 저장만으로 CD가 시작되지는 않는다. Vault 동기화·필요한 계정 변경·컨테이너 재생성을 한 실행에서 처리한다.
- 설정을 실시간으로 다시 읽지 않으며 재생성 중 서비스가 잠시 중단될 수 있다. 앱 재배포는 DB 볼륨과
  객체 데이터를 유지한다. DB 이름·bucket 이름 변경은 기존 데이터를 새 이름으로 이전하지 않는다.
- `ACCOUNT_CREDENTIAL_ENCRYPTION_KEY`는 기존 저장 데이터를 재암호화하지 않고 교체할 수 없다.
  일반 재배포는 이 키의 변경을 거부한다.
- `Prod-IaC`의 `IAC_STATE_ENCRYPTION_KEY` 복구 사본은 VM·Vault 밖에 보관한다. 이 키를 잃으면
  `infra-state`의 암호화된 Terraform state를 읽을 수 없다.
- CD의 `cowork-production` 동시 실행 제한이 IaC 적용을 직렬화한다. 로컬 IaC 적용은 CD와 겹치지 않게 실행한다.
- state 저장 실패 후에는 다음 적용 전에 암호화 복구 파일을 실제 Grafana 상태와 대조하고 `infra-state`에 저장한다.
  후속 실행은 자동 차단되지 않는다. 기존 state를 읽을 수 없다는 이유로 새 state를 초기화하지 않는다.

## GSM 연결과 별도 서비스

- GSM SV에 TURN relay 포트 범위를 확보하지 못했다. RTC UDP와 TCP가 모두 차단된
  클라이언트에는 대체 경로가 없다.
- GitHub App은 별도 `cowork-github-app` 저장소에서 배포한다. GSM의
  `cowork-github-app.https.gsmsv.site`는 Nginx 8080으로 연결하며 App 3000을 직접 공개하지 않는다.
  GitHub의 Webhook Secret과 App의 값은 함께 교체한다. `COWORK_GITHUB_APP_INTERNAL_API_KEY`도
  별도 App의 값과 맞춰야 하며, 이 저장소의 CD는 외부 App 설정이나 자격 증명 발급을 변경하지 않는다.

VM 삭제 시 로컬 영속 볼륨과 데이터 디렉터리도 삭제된다. 복구 사본은 해당 VM 밖에 보관한다.
