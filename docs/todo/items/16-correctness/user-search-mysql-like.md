# 사용자 통합 검색의 MySQL 호환성 복구

- **서비스**: cowork-user
- **우선순위**: 🔴 높음
- **현재 상태**: `GET /users/search`의 `q`·`query` 조건이 MySQL에서 지원하지 않는 Ecto `ilike` 연산자를 사용함

> **2026-09-07 진척:** `maybe_query/2`의 `ilike`를 `like`로 바꾸고, `name`·`nickname`·`q`·`query`가
> `Accounts.normalize_search_term/1`·`like_pattern/1`을 공유하도록 정리했다. escape가 없던 `name`·`nickname`에도
> 같은 `%`·`_`·`\` escape가 적용된다. `q`가 공백뿐일 때 `query` alias가 무시되던 경로는
> `Accounts.search_term/2`로 분리해 정리했다. 대소문자·escape 계약은 `open_api.ex`의 검색 파라미터 설명과
> `Accounts.like_pattern/1` docstring에, 정규화·escape 규칙은 `test/cowork_user/accounts_search_policy_test.exs`에
> 기록했다. 배포 전 MySQL smoke 항목과 `EXPLAIN` 기반 전문 검색 도입 기준은 별도 문서로 남기지 않기로 했다.
> 로컬에 Elixir 툴체인과 MySQL이 없어 `mix test`와 MySQL smoke는 아직 실행하지 않았다.
>
> **2026-09-08 리뷰 반영:** 정작 `ilike`→`like` 전환 자체를 고정하는 테스트가 없다는 지적에 따라, `maybe_query/2`를
> `@doc false`로 공개해 Ecto 쿼리 구조체를 DB 연결 없이 `inspect/1`로 검사하는 회귀 테스트를 추가했다.
> `search_term/1`이 `"q"`/`"query"`라는 HTTP 파라미터 이름을 아는 유일한 도메인 함수였던 문제는 `search_term(q, query)`로
> 바꿔 파라미터 이름을 아는 책임을 호출부(`search_users/2`)로 옮겼다. `maybe_query/2`는 `search_term/2`가 이미
> 정규화한 값을 받으므로 내부에서 `normalize_search_term/1`을 다시 부르지 않도록 정리했다. `normalize_search_term/1`·
> `like_pattern/1`·`search_term/2`는 기존 관례(`student_event_newer?/2` 등)에 맞춰 `@doc false`로 되돌렸다.
> escape 계약이 `sql_mode`의 `NO_BACKSLASH_ESCAPES` 부재와 컬럼 collation(`utf8mb4_unicode_ci`) 두 가지에 의존한다는
> 점은 PR 본문·docstring뿐 아니라 `cowork-user/README.md`의 "사용자 검색" 절에도 남겼다.

## 문제

`cowork-user`의 `CoworkUser.Repo`는 `Ecto.Adapters.MyXQL`을 사용한다. `Accounts.search_users/2`의 이름·닉네임 개별 필터는 `like`를 사용하지만, `q` 또는 `query` 통합 검색은 `maybe_query/2`에서 `ilike`를 생성한다.

MyXQL의 SQL 생성기는 `ilike`를 지원하지 않으므로 해당 조건이 포함된 쿼리는 데이터베이스에 전달되기 전에 `Ecto.QueryError`로 실패한다. 이 호환성 문제는 어댑터와 쿼리 표현식을 정적으로 점검하고 실제 환경에서 확인한다. 자동화 테스트는 검색어 정규화와 escape 같은 핵심 검색 정책의 단위 테스트로 한정한다.

현재 컬럼 collation이 대소문자를 구분하지 않는다면 `LIKE`만으로 기존 의도를 충족할 수 있다. collation과 무관한 동작이 필요하면 정규화 컬럼이나 `LOWER` 표현식과 그에 맞는 인덱스 전략을 함께 결정한다.

## 검색 방식

| 선택지                                    | 장점                                | 주의점                                                    |
|-------------------------------------------|-------------------------------------|-----------------------------------------------------------|
| MySQL case-insensitive collation + `LIKE` | 현재 개별 필터와 동작이 같고 단순함 | 환경별 collation을 명시적으로 확인해야 함                 |
| `LOWER(column) LIKE LOWER(?)`             | collation 차이를 줄임               | 선행 와일드카드와 함수 적용으로 일반 인덱스 활용이 어려움 |
| 별도 정규화·검색 컬럼                     | 동작과 인덱스를 명확히 제어함       | 쓰기 경로와 migration이 추가됨                            |

## 할 일

### 쿼리 수정

- `Accounts.maybe_query/2`를 MySQL에서 실행 가능한 대소문자 무시 검색으로 변경한다.
- `%`, `_`, `\\` 입력의 escape 규칙을 유지하고 실제 SQL의 escape 동작을 검증한다.
- `name`, `nickname`, `q`, `query` 필터의 대소문자 정책을 하나로 통일한다.

### 정적·운영 검증

- MyXQL adapter가 지원하는 쿼리 연산자만 사용하는지 코드 검토로 확인한다.
- 검색어 정규화와 `%`, `_`, `\\` escape 정책은 외부 의존성 없는 단위 테스트로 검증한다.
- MySQL 환경의 한글·영문·대소문자 검색은 배포 전 smoke 확인 항목으로 기록한다.
- 데이터 규모가 커질 경우 `EXPLAIN`으로 선행 와일드카드 검색 비용을 측정하고 전문 검색 도입 기준을 기록한다.

## 검증

- `q`와 `query`가 같은 검색 정책을 사용하는지 코드 경로를 확인한다.
- 이름과 닉네임 양쪽의 대소문자 정책을 설정과 실제 MySQL smoke 결과로 확인한다.
- `%`, `_`, `\\`가 검색 연산자로 주입되지 않게 변환되는지는 핵심 단위 테스트로 검증한다.
- SQL 생성, MySQL 실행, HTTP 경로를 고정하는 통합·회귀 테스트는 추가하지 않는다.

## 완료 조건

- `GET /users/search`의 `q`와 `query` 조건이 MySQL에서 정상 실행된다.
- 검색 연산자의 대소문자 계약은 문서에, escape 비즈니스 규칙은 단위 테스트에 명시되어 있다.
- PostgreSQL 전용 연산자가 MyXQL 쿼리 경로에 남아 있지 않다.
