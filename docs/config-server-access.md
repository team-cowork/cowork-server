# Config Server 접근 보호

Config/Eureka는 사설망 HTTP와 서비스별 Basic 인증을 사용한다. 비밀번호는 사설망에서도 암호화되지
않으므로 허용 peer 방화벽과 VM 관리 권한에 의존한다. 공개 프록시에 연결하지 않는다.
허용 경로는 [접근 정책](../cowork-config/src/main/kotlin/com/cowork/config/security/policy/ControlPlaneAccessPolicy.kt),
배포 입력은 [검증기](../deploy/prod/config-access.py)를 기준으로 한다.

## 계정과 Vault

계정은 [생성기](../deploy/config/generate-control-plane-credentials.py)로 발급한다.
운영 bootstrap 자료는 Vault `secret/control-plane/prod/`에 두고 Git 슬롯의 비밀 참조로 연결한다.
서버 계정 전체와 Config의 AppRole 자격 증명은 Config 배포에만 전달한다.
Config가 읽는 앱 속성 경로에 bootstrap 비밀을 넣지 않는다.

Config의 AppRole은 [전용 정책](../deploy/config/vault/render-config-policy.py)을 사용한다.
GitHub Actions의 배포용 토큰과 별개다. 운영 Secret ID는 재로그인을 위해 만료·사용 횟수 제한 없이
발급하며 자동 교체하지 않는다. 장기 자격 증명으로 취급하고 Config 송신 CIDR과 관리 권한을 제한한다.

배포 사전 검증은 즉시 로그인·갱신·폐기를 확인한다. **같은 Config 인스턴스의 만료 전 자동 갱신과
24시간 최대 수명 이후 재로그인은 아직 관측하지 않았다.** 재배포 없는 7일 운영 결과도 없다.
사전 검증 성공이나 `/actuator/health`만으로 이 장기 동작을 검증했다고 판단하지 않는다.
role과 Secret ID의 실제 설정은 검증기의 관리 권한 밖이므로 Vault 관리자가 대조해야 한다.

## 교체와 복구

서비스 계정은 새 username으로 발급한다. 서버에 새 계정을 추가해 Config를 배포하고, 대상 서비스의
bootstrap을 교체·배포한 뒤 이전 계정을 제거한다. 유출된 계정 폐기는 이미 프로세스에 읽힌
애플리케이션 시크릿을 지우지 않으므로 해당 시크릿도 필요한 범위에서 교체한다.

AppRole은 새 Secret ID 발급 → bootstrap 참조 변경 → Config 배포·로그인 확인 → 이전 accessor
폐기 순서로 교체한다. Secret ID 폐기는 이미 발급된 토큰을 폐기하지 않는다. 유출 시 둘 다 폐기한다.
인증 도입 전 이미지나 폐기된 토큰을 포함한 snapshot으로 복구하지 않는다.

Config의 root 방화벽 helper는 IaC의 Ansible이 설치한다. 앱 CD는 설치된 helper로 정책을 전환하고
실패 시 이전 정책을 복원한다. [bootstrap](../deploy/prod/bootstrap-config-firewall.sh), helper와 unit은
같은 릴리스에서 적용하며 VM의 생성된 정책 파일을 수동으로 수정하지 않는다.
Docker 권한을 가진 배포 계정의 분리를 VM 관리자 권한의 격리로 간주하지 않는다.

Config 응답에는 실제 시크릿이 포함된다. 운영 확인에는 상태 코드와 접근 허용·거부 결과만 남기고
응답 본문·인증 헤더·HTTP wire 로그를 보관하지 않는다.
