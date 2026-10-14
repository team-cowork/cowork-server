# 저장소 밖의 운영 조건

GSM SV의 VM 생성·사양·공개 포트 전달은 패널에서 관리한다. IaC는 기존 VM의 설정과 모니터링을
관리하며, Docker Engine·Python 3·SSH 접속·관리자 권한이 준비되어 있어야 한다.

## 인증과 state 복구

- 최초 Vault 신뢰 설정은 외부 관리자 토큰으로 [bootstrap-vault.py](../deploy/iac/scripts/bootstrap-vault.py)를
  실행한다. 모듈 DB의 관리자·수집 계정은 Vault `secret/iac/databases/<module>`에,
  앱 계정 비밀번호는 `secret/slot-secrets/<module>`에 보관한다. DB·S3 자격 증명을 변경하면
  먼저 `service=target=infrastructure`로 IaC를 적용하고 앱을 재배포한다.
  수동 앱 재배포는 DB·스토리지를 변경하지 않는다.
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
  GitHub의 Webhook Secret과 App의 값은 함께 교체한다.

VM 삭제 시 로컬 영속 볼륨과 데이터 디렉터리도 삭제된다. 복구 사본은 해당 VM 밖에 보관한다.
