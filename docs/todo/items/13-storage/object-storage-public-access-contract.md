# 오브젝트 스토리지 공개 접근 계약

- **서비스**: cowork-chat, cowork-team, cowork-user, cowork-config, SeaweedFS/외부 S3 ingress
- **우선순위**: 🔴 높음
- **현재 상태**: 접근 정책과 배포 계약 미확정

## 문제

team icon, user profile image, chat attachment가 같은 S3 호환 저장소와 bucket을 공유하지만 객체를
누가 어떤 방식으로 읽을 수 있는지 하나의 계약으로 정의되어 있지 않다. bucket 전체 anonymous
read를 허용하면 권한이 필요한 chat attachment까지 공개될 수 있고, 반대로 모두 private으로 두면
DB와 API가 반환하는 만료 없는 URL은 직접 조회할 수 없다. 따라서 anonymous read를 현재 정책으로
간주하거나 배포 설정으로 강제하지 않는다.

presigned upload 계약도 서비스마다 다르다. team과 user는 public endpoint로 PUT URL을 서명하고
서버의 HEAD/DELETE는 internal endpoint를 사용하지만, chat은 현재 internal endpoint client로 PUT
URL도 서명한다. 외부 클라이언트가 internal hostname에 접근할 수 없거나 프록시가 URL의 host/path를
바꾸면 SigV4 검증에 실패할 수 있다.

## 결정할 접근 정책

객체 종류별로 아래 선택지 중 하나를 명시한다.

| 객체 종류          | 공개 GET 후보                      | 인증/인가 GET 후보                         | 결정 시 확인할 점                  |
|--------------------|------------------------------------|--------------------------------------------|------------------------------------|
| team icon          | 영구 public URL 또는 public prefix | 짧은 presigned GET                         | 캐시·공개 프로필 범위              |
| user profile image | public URL 또는 presigned GET      | 사용자/팀 가시성 검사 후 presigned GET     | 현재 응답에서 URL 재발급 방식      |
| chat attachment    | 공개 URL                           | 채널 권한 검사 후 proxy 또는 presigned GET | 채널 탈퇴·메시지 삭제 후 접근 차단 |

- 공개 객체와 비공개 객체를 별도 bucket으로 나눌지, 하나의 bucket 안에서 prefix별 policy를 적용할지
  결정한다.
- public/private 경계를 bucket URL의 난수성에 의존하지 않는다.
- 인증된 조회를 선택하면 Gateway 인증과 채널/팀 권한 검사 주체, URL 만료, 다운로드 감사 로그,
  range request 지원을 함께 정의한다.
- 삭제·교체 시 CDN과 클라이언트 캐시의 만료 정책을 정의한다.

## Public ingress와 SigV4

- `S3_PUBLIC_ENDPOINT`가 origin인지 path prefix를 포함할 수 있는 base URL인지 확정한다.
- path-style(`/bucket/object`)과 virtual-host style(`bucket.host/object`) 중 하나를 정하고 chat(Node),
  team(JVM), user(Elixir) signer가 같은 canonical URI를 생성하게 한다.
- reverse proxy가 요청의 `Host`, raw path, percent encoding, query parameter와 HTTP method를 SigV4
  서명 당시 값 그대로 전달할 수 있는지 확인한다. public host로 서명한 요청을 internal host로
  전달할 때 SeaweedFS가 어떤 host를 검증하는지도 명시한다.
- TLS 종료 위치, 외부 DNS, 업로드 크기 제한, `PUT`/`HEAD`/`GET`/`DELETE` 전달 범위를 정의한다.
- 서비스 내부의 HEAD/DELETE endpoint와 외부 presigned URL endpoint를 분리할 경우 각 설정 이름과
  fallback을 공통 계약으로 정한다.

## Signer 정합성

- chat, team, user의 region, path-style, endpoint, bucket, content-type 서명 여부와 만료 시간을
  표로 inventory한다.
- 세 서비스 모두 public ingress로 사용할 수 있는 PUT URL을 생성하고, 확인·삭제는 private network의
  internal endpoint로 처리하도록 할지 결정한다.
- public endpoint와 internal endpoint가 다를 때 URL 문자열만 치환하지 않는다. host/path는 SigV4
  서명 입력이므로 최종 외부 주소를 기준으로 처음부터 서명한다.
- 설정 누락 시 internal endpoint로 자동 fallback할지 fail-fast할지 환경별로 확정한다.

## CORS

- 허용 origin을 local web, production web, 필요하면 별도 admin client 단위로 관리한다.
- 필요한 method와 header만 허용한다. presigned PUT에서 서명하는 `Content-Type`과 앱이 실제 보내는
  header가 일치하는지 확인한다.
- `ETag`, range/download 관련 header 노출 필요성을 검토하고 wildcard origin과 credential 조합을
  사용하지 않는다.
- SeaweedFS local bootstrap과 외부 S3 provider 설정 중 무엇이 CORS의 기준인지 정하고 환경별 적용
  방법을 문서화한다.

## 저장된 URL 이관

- 각 DB가 absolute URL, object key, bucket/prefix 중 무엇을 저장하는지 inventory한다.
- endpoint, bucket 또는 접근 방식을 바꿔도 기존 team icon·profile image·chat attachment가 계속
  열리도록 구 URL 매핑 또는 단계적 데이터 이관 방식을 정한다.
- URL에서 object key를 역추출해 삭제·소유권 검사를 하는 코드가 새 host/path와 구 URL을 모두
  안전하게 처리하는지 확인한다.
- 장기적으로는 object key와 storage class를 저장하고 응답 시점에 URL을 만드는 방식으로 통일할지
  결정한다.

## 구현 순서

1. 객체 종류별 공개/비공개 정책과 bucket/prefix 경계를 확정한다.
2. public ingress의 DNS·TLS·SigV4 forwarding 계약을 확정한다.
3. 세 서비스 signer와 internal HEAD/DELETE 설정을 같은 계약으로 맞춘다.
4. CORS를 환경별로 적용하고 최소 권한을 확인한다.
5. 저장된 URL 호환·이관 절차를 적용한다.
6. object key 소유권, 허용 prefix, 파일 크기 제한 같은 핵심 보안 정책만 단위 테스트로 검증하고,
   signer canonical request와 실제 PUT·HEAD·GET·DELETE는 정적 검토 및 수동 smoke로 확인한다.

## 완료 조건

- team icon, user profile image, chat attachment 각각의 읽기 권한과 URL 수명이 정의되어 있다.
- bucket-wide anonymous read 사용 여부가 명시적으로 결정되고 비공개 객체가 정책 밖으로 노출되지 않는다.
- public ingress가 SigV4의 host/path/query를 보존하며 세 signer가 동일한 방식으로 동작한다.
- 허용 origin과 method/header가 local/prod 환경별로 정의되어 있다.
- 기존 DB에 저장된 URL 또는 object key의 호환·이관 경로가 마련되어 있다.
- 런타임 Compose 설정과 문서가 결정된 계약을 동일하게 표현한다.
