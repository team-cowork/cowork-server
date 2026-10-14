# 운영 LiveKit 제약

LiveKit은 DB VM에서 실행한다. [Compose](./compose.yaml)는 버전 관리하지만 독립 `prod-cd`
target은 없다. 실제 설정은 VM의 `/opt/cowork/livekit/livekit.yaml`과 `runtime.env`에 두며 저장소에
추가하지 않는다. 이 디렉터리의 `livekit.yaml`·`livekit-cloud.yaml`은 운영 실값이 아닌 참고 설정이다.

## GSM 네트워크

현재 signaling 주소는 `wss://chcowork.https.gsmsv.site/livekit/`다. GSM의 RTC 외부 주소는
`158.247.251.109`이며 TCP 32154 → VM 7881, UDP 37932 → VM 7882로 전달한다.
이 포트 전달 규칙은 저장소 밖에 있다. 변경 시 [설정 생성기](./configure-webhook.py)의 광고 포트와
Compose의 컨테이너 포트를 함께 맞춰야 한다.

TURN은 비활성화했다. GSM의 UDP 32179 → VM 3478 연결 한 개로는 embedded TURN의 별도
relay 포트 범위를 제공할 수 없다. RTC UDP와 TCP가 모두 차단된 클라이언트에는 fallback이 없다.
TURN을 사용하려면 relay 경로를 확보한 별도 구성이 필요하다.

Voice webhook은 DB VM에서 Voice 사설 주소로 직접 보낸다. Voice ingress 정책에서 DB VM을
허용해야 한다. LiveKit과 Voice의 서명 키를 함께 교체한다.

TLS와 HTTP 준비 상태는 확인했지만 인증된 WebSocket과 외부 음성 왕복은 아직 검증하지 않았다.
포트 제약은 [LiveKit 공식 문서](https://docs.livekit.io/home/self-hosting/ports-firewall/)와
[고정 버전 TURN 구현](https://github.com/livekit/livekit/blob/v1.13.7/pkg/service/turn.go)을 기준으로 한다.
