# 로컬 첫 기동

Docker Compose `2.24.4` 이상을 사용한다. `.env.example`의 외부 연동값을 준비한 뒤 실행한다.

```bash
cp .env.example .env
python3 deploy/config/generate-control-plane-credentials.py --profile local --output deploy/local/secrets/config-access
./deploy/local/stack.sh start
```

기존 bootstrap 계정 파일은 재사용한다. 직접 Compose를 실행할 때 필요한 env 파일은
[`stack.sh`](../deploy/local/stack.sh)를 기준으로 한다.

빈 Vault에서는 `secret/cowork-notification/local`의 `fcm.credentials-json`에 Firebase 서비스 계정 JSON
전체를 문자열로 등록한 뒤 notification을 재기동한다. 이 외부 자격 증명은 저장소에서 생성하지 않는다.

`stack.sh` 종료는 전체 앱의 준비 완료를 뜻하지 않는다. 빈 DB도 소유자의 snapshot과 projection catch-up을
기다린다. init job의 정상 종료와 장기 실행 서비스의 readiness를 구분한다.

별도 `cowork-github-app`은 Compose에 포함되지 않는다. GitHub 기능 확인에는 별도 App 실행과 내부 API key
설정이 필요하다. 실기기는 호스트의 `localhost`에 접근할 수 없으므로 공개 S3·LiveKit 주소를 LAN 주소로 맞춘다.
