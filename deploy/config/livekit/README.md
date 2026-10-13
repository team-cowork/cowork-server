# 운영 LiveKit

운영 DB VM의 LiveKit 한 개를 `compose.yaml`로 실행한다. 이미지 버전과 digest는
GitHub 소스에 고정한다. `livekit.yaml`과 `livekit-cloud.yaml`은 기존 참고 설정이다.
운영 자격 증명을 그 파일에 기록하지 않는다.

운영 설정은 VM의 `/opt/cowork/livekit/livekit.yaml`, 환경 변수는 같은 디렉터리의
`runtime.env`에 둔다. 디렉터리는 root 소유 `0700`, 두 파일은 root 소유 `0600`을
사용한다. 실제 키와 기존 RTC·TURN 설정을 포함한 파일을 저장소에 추가하지 않는다.

`configure-webhook.py`는 기존 설정의 비공개 JSON 사본을 읽어 Voice webhook만
갱신한다. 키가 한 개면 그 키를 사용한다. 키가 여러 개면 `LIVEKIT_WEBHOOK_API_KEY`
환경 변수로 기존 서명 키를 선택한다. JSON 출력은 LiveKit이 읽을 수 있는 YAML이다.

```bash
python3 deploy/config/livekit/configure-webhook.py \
  --input /private/livekit-current.json \
  --output /private/livekit-next.yaml \
  --url http://10.0.0.90:8089/voice/webhook
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

현재 Compose는 기존 TCP 7880·7881 공개 범위를 유지한다. 설정의 RTC 공인 IP,
GSM 외부 포트 매핑, UDP와 TURN 경로는 별도로 대조해야 한다. HTTP 준비 상태가
정상이어도 외부 음성 통신이 검증된 것은 아니다. 공개 포트를 추가하기 전에 실제
GSM 매핑과 클라이언트 접속 주소를 확인한다.

웹훅은 기존 API 키로 서명하며 Voice의 private 주소로 전송한다.
[공식 웹훅 설명](https://docs.livekit.io/home/server/webhooks/)과
[v1.13.7 설정 예시](https://github.com/livekit/livekit/blob/v1.13.7/config-sample.yaml)를
기준으로 `webhook.api_key`와 `webhook.urls`를 구성한다.
