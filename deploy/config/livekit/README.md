# 운영 LiveKit

운영 DB VM의 LiveKit 한 개를 `compose.yaml`로 실행한다. 이미지 버전과 digest는
GitHub 소스에 고정한다. `livekit.yaml`과 `livekit-cloud.yaml`은 기존 참고 설정이다.
운영 자격 증명을 그 파일에 기록하지 않는다.

운영 설정은 VM의 `/opt/cowork/livekit/livekit.yaml`, 환경 변수는 같은 디렉터리의
`runtime.env`에 둔다. 디렉터리는 root 소유 `0700`, 두 파일은 root 소유 `0600`을
사용한다. 실제 키와 기존 RTC·TURN 설정을 포함한 파일을 저장소에 추가하지 않는다.

`configure-webhook.py`는 기존 설정의 비공개 JSON 사본을 읽어 Voice webhook과
명시한 RTC 항목을 갱신한다. 키가 한 개면 그 키를 사용한다. 키가 여러 개면 `LIVEKIT_WEBHOOK_API_KEY`
환경 변수로 기존 서명 키를 선택한다. JSON 출력은 LiveKit이 읽을 수 있는 YAML이다.

```bash
python3 deploy/config/livekit/configure-webhook.py \
  --input /private/livekit-current.json \
  --output /private/livekit-next.yaml \
  --url http://10.0.0.90:8089/voice/webhook \
  --rtc-public-ip 158.247.251.109 \
  --rtc-tcp-port 32154 --rtc-udp-port 37932 \
  --disable-turn
```

검토한 소스 SHA의 `compose.yaml`과 생성한 설정을 운영 디렉터리에 설치한다.
`runtime.env`에는 기존 LiveKit 컨테이너의 환경 변수만 보존한다. 초기 전환 때는
기존 `cowork-livekit` 한 개만 중지·제거한다. 다른 서비스가 포함된 전체 Compose를
실행하지 않는다. 이후 같은 운영 디렉터리에서 아래 명령으로 해당 서비스만 갱신한다.

```bash
sudo docker compose --project-directory /opt/cowork/livekit \
  -f /opt/cowork/livekit/compose.yaml config --quiet
sudo docker compose --project-directory /opt/cowork/livekit \
  -f /opt/cowork/livekit/compose.yaml pull livekit
sudo docker compose --project-directory /opt/cowork/livekit \
  -f /opt/cowork/livekit/compose.yaml up -d --no-deps --force-recreate \
  --wait --wait-timeout 180 livekit
```

이 구성은 GitHub의 버전 관리 대상이다. 아직 독립 `prod-CD` target은 아니다.
배포한 소스 SHA와 이미지 digest를 운영 기록에 남긴다.

2026-10-13에 확인한 GSM의 기존 매핑은 다음과 같다. Compose의 컨테이너 RTC 포트는
클라이언트에게 광고하는 외부 포트와 맞춘다. GSM 매핑이 바뀌면 renderer 인자와
Compose를 함께 갱신한다.

| 용도 | GSM 외부 포트 | VM 포트 | 컨테이너 포트 |
|---|---:|---:|---:|
| HTTP | TCP 34424 | TCP 7880 | TCP 7880 |
| RTC TCP | TCP 32154 | TCP 7881 | TCP 32154 |
| RTC UDP mux | UDP 37932 | UDP 7882 | UDP 37932 |

RTC는 `node_ip=158.247.251.109`, `use_external_ip=false`를 사용한다. 기존
`port_range_start/end`는 제거한다. 고정 버전의 media transport는 포트 범위가 있으면
UDP mux보다 먼저 적용하므로, `udp_port`만 추가하면 단일 포트로 전환되지 않는다.

TURN은 비활성화한다. 기존 GSM UDP 32179 → VM UDP 3478 매핑은 접속 포트 한 개이며,
embedded TURN이 할당하는 별도 relay 포트 범위는 제공하지 않는다. 이 상태에서
TURN을 정상 경로로 광고하지 않는다. 제한된 네트워크에서는 TURN fallback을 제공하지
못하며, RTC UDP와 TCP가 모두 차단되면 음성 연결이 실패할 수 있다. TURN을 다시
사용하려면 relay 경로가 확보된 별도 설계가 필요하다.

현재 HTTPS signaling 경로는 `wss://chcowork.https.gsmsv.site/livekit/`이다.
호스트 nginx가 `/livekit/`를 `127.0.0.1:7880`으로 전달한다. HTTP 준비 상태가
정상이어도 인증된 WebSocket 연결과 외부 음성 왕복이 검증된 것은 아니다.

웹훅은 기존 API 키로 서명하며 Voice의 private 주소로 전송한다.
[공식 웹훅 설명](https://docs.livekit.io/home/server/webhooks/)과
[v1.13.7 설정 예시](https://github.com/livekit/livekit/blob/v1.13.7/config-sample.yaml)를
기준으로 `webhook.api_key`와 `webhook.urls`를 구성한다.

RTC 설정은 [공식 포트 설명](https://docs.livekit.io/home/self-hosting/ports-firewall/)과
[고정 버전의 media transport 구현](https://github.com/livekit/mediatransportutil/blob/f234b534b095/pkg/rtcconfig/webrtc_config.go)을
참고한다. TURN의 별도 relay 포트는 [v1.13.7 구현](https://github.com/livekit/livekit/blob/v1.13.7/pkg/service/turn.go)에서 확인한다.
