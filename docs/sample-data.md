# 로컬 샘플 데이터

화면 개발을 할 때 DataGSM 로그인 없이 바로 데이터를 확인할 수 있도록 `local` 프로필에서 샘플 데이터를 넣는다.

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 사용자 테이블이 비어 있을 때만 넣는다. 이미 데이터가 있으면 아무것도 하지 않는다.
- 샘플 사용자 3명, 팀 `cowork 샘플 팀`, 프로젝트 1개, 채널 2개(일반, 파일 공유)를 만든다.
- 샘플 사용자의 액세스 토큰은 `JwtTokenProvider`로 직접 만들어 쓴다. 운영과 스테이징에서는 `local` 프로필을 켜지 않는다.
