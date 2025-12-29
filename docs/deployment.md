# 배포

## 구성

| 구성 요소 | 위치 |
|---|---|
| 애플리케이션 | EC2의 Docker 컨테이너 (`cowork-server`) |
| 이미지 저장소 | Amazon ECR |
| 데이터베이스 | Amazon RDS (MySQL 8.0) |
| 파일 저장소 | Amazon S3 |
| 리버스 프록시 | Nginx 컨테이너 (`api.cowork.team`) |
| 인증서 | Let's Encrypt (certbot 컨테이너가 12시간마다 갱신 확인) |

## 배포 절차

1. `develop`을 `main`에 병합합니다.
2. CD 워크플로가 이미지를 빌드해 ECR에 `날짜-커밋 SHA 7자리` 태그(예: `20251112-951a76f`)로 올립니다.
3. 워크플로가 SSH로 EC2에 접속해 `deploy/scripts/deploy.sh`를 실행합니다.
4. 스크립트는 새 이미지를 받아 컨테이너를 교체하고, 헬스 체크가 통과할 때까지 기다립니다.
5. 결과는 Discord 배포 채널로 알립니다.

## 롤백

헬스 체크가 2분 30초 안에 통과하지 않으면 스크립트가 컨테이너 로그를 출력하고 종료 코드 1로 끝납니다.
이전 이미지로 되돌릴 때는 EC2에서 이전 태그로 스크립트를 다시 실행합니다.

```bash
cd ~/cowork/deploy
ECR_REGISTRY=<레지스트리 주소> IMAGE_TAG=<이전 태그> ./scripts/deploy.sh
```

ECR에는 이전 태그가 남아 있으므로 다시 빌드할 필요가 없습니다. EC2의 로컬 이미지는 72시간이 지나면 정리됩니다.

## 운영 환경 변수

- 운영 값은 EC2의 `~/cowork/deploy/.env`에만 둡니다. 저장소에는 `deploy/.env.example`만 둡니다.
- 새 환경 변수를 추가하면 `deploy/.env.example`과 이 문서를 함께 갱신합니다.
- FCM 서비스 계정 파일은 `~/cowork/deploy/secrets/`에 두며, 컨테이너의 `/run/secrets`에 읽기 전용으로 마운트됩니다.
