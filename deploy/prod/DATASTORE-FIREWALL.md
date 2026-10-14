# 운영 DB 방화벽 경계

GSM의 공유 사설망에서는 private IP에 게시해도 다른 VM의 접근을 막지 못한다.
실제 허용 peer는 DB VM의 root 소유 `/etc/cowork/datastore-firewall/policy.json`에서 관리한다.
Vault 배포 설정이나 일반 CD 계정은 이 허용 범위를 변경하지 않는다.

설치 입력과 검증은 [bootstrap](./bootstrap-datastore-firewall.sh)과
[helper](./datastore-firewall.py)를 기준으로 한다. 현재 정책은 IPv4 Docker bridge를 전제로 하며
IPv6 ingress는 허용하지 않는다. 네트워크 재생성으로 bridge 이름·subnet이 바뀌면 관리자가 정책을
다시 검토·설치해야 한다. 일반 CD의 실패를 피하려고 현재 네트워크를 임의로 다시 만들지 않는다.

Config의 DB VM 접근은 Spring Cloud Bus용 Kafka만 필요하다. 앱별 DB와 monitoring exporter의
필요 포트만 개별 허용한다. 허용된 VM에서도 DB 인증은 별도로 필요하다.
Docker 시작 전에 방화벽을 복원하는 unit은 중지 시 Docker에도 영향을 줄 수 있다.
정책 변경 후에는 허용 VM의 연결과 비허용 VM의 차단을 실제 네트워크에서 확인한다.
