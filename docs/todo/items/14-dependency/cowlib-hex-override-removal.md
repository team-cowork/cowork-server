# cowlib git override 제거와 Hex 릴리스 복귀

- **서비스**: cowork-user, 컨테이너 빌드 인프라
- **우선순위**: 🟠 중간
- **현재 상태**: 완료. CVE-2026-43971 수정 커밋을 포함한 Hex `2.20.0`으로 전환하고 git override를 제거했다
- **결론**: 선언·잠금·수정 소스와 로컬 컴파일·핵심 비즈니스 단위 테스트로 Hex 복귀를 확인했다. 새 런타임의 컨테이너·HTTP 검증은 별도 배포 확인 사항으로 둔다

## 진행 상태 (2026-09-16)

| 항목 | 확인 결과 |
| --- | --- |
| 수정 커밋 포함 | upstream에서 `89da27ee4c241f5d649ba7d9b7f2188918af6cea...2.20.0`을 비교한 결과 `ahead_by: 6`, `behind_by: 0`이며 merge base가 기존 고정 커밋과 같다. `2.20.0`이 수정 커밋을 포함한다. |
| 선언·잠금 | `mix.exs`는 `{:cowlib, "~> 2.20"}`이고 `mix.lock`은 Hex `2.20.0`과 체크섬을 기록한다. git 선언과 `override: true`는 제거했다. |
| 상위 의존성 | 함께 갱신한 `cowboy 2.19.0`의 `cowlib >= 2.20.0 and < 3.0.0` 제약을 만족한다. |
| 확보한 소스 | `deps/cowlib/hex_metadata.config`의 버전이 `2.20.0`이며 `deps/cowlib/.git`이 없다. `cow_link.erl`의 이스케이프 수정도 확인했다. 경로는 `cowork-user/` 기준이다. |
| 로컬 검증 | 의존성 갱신, 프로덕션 컴파일·릴리스 생성, 핵심 비즈니스 단위 테스트 47개가 통과했다. 검증 런타임은 Elixir `1.20.1` / OTP `29.0.2`이다. |
| 별도 참고 | `Dockerfile.local`·`Dockerfile.prod`의 Elixir `1.20.4` / OTP `29.1` 조합에서 이미지 빌드와 Plug/Cowboy 기동·HTTP 요청은 미검증이다. IDE의 이전 VCS root 매핑도 확인하지 않았다. 이 항목들은 저장소의 git override 제거·Hex 복귀 완료 여부와 별도로 관리한다. |

근거는 [Hex 2.20.0](https://hex.pm/packages/cowlib/2.20.0)과 [수정 커밋에서 릴리스 태그까지의 비교](https://github.com/ninenines/cowlib/compare/89da27ee4c241f5d649ba7d9b7f2188918af6cea...2.20.0)이다. [OSV의 CVE-2026-43971](https://osv.dev/vulnerability/EEF-CVE-2026-43971)에는 확인 시점까지 수정된 Hex 버전이 반영되지 않아 패키지 경고가 남는다. 이는 위의 커밋·소스 확인 결과와 구분한다. 함께 보고되는 CVE-2026-43966·43969의 해결 여부는 이 확인으로 입증하지 않는다.

아래 문제와 선언, 복귀 절차는 최초 점검 당시의 배경·작업 명세다. 완료 결과와 검증 범위는 위 표를 기준으로 한다.

## 문제

`cowork-user`는 `plug_cowboy` → `cowboy`를 거쳐 `cowlib`에 의존한다. `cowboy` 2.18.0이
`cowlib >= 2.19.0 and < 3.0.0`을 요구하지만, Hex의 최신 릴리스 `2.19.0`(2026-07-28 배포)에는
CVE-2026-43971 픽스가 들어 있지 않다. 픽스 커밋 `89da27e`는 2026-08-18에 upstream에 들어갔고
릴리스 태그보다 뒤에 있다.

이 때문에 `cowork-user/mix.exs`는 `cowlib`을 Hex 패키지가 아니라 git 저장소에서 특정 커밋으로
받도록 선언하고 `override: true`로 `cowboy`의 Hex 제약을 우회한다. 취약점 자체는 해소된 상태지만
이 선언은 Hex 릴리스가 나올 때까지만 유지할 임시 조치다.

git 의존성은 Hex 패키지와 동작이 다르다. `mix deps.get`이 tarball을 받는 대신 실제 `git clone`을
수행하므로 `cowork-user/deps/cowlib/.git`이 생기고, IDE가 이를 별도 VCS root로 인식한다. 빌드도
hex.pm 외에 github.com 도달성과 이미지 내 `git` 바이너리에 의존하게 된다. 두 dockerfile 모두
`git`을 설치하고 있어 현재 빌드는 성공하지만, 의존성 확보 경로가 하나 더 늘어난 상태다.

## 취약점과 고정 커밋

| 항목      | 값                                                                                                                  |
|-----------|---------------------------------------------------------------------------------------------------------------------|
| 식별자    | CVE-2026-43971 / [GHSA-gg23-fwhr-prjh](https://github.com/advisories/GHSA-gg23-fwhr-prjh)                           |
| 심각도    | medium (CVSS v4 6.3)                                                                                                |
| 영향 범위 | cowlib 2.9.0 이상 전체, Hex 패치 릴리스 없음                                                                        |
| 내용      | `cow_link:do_link/1`이 target URI·rel 값·속성 키를 이스케이프 없이 `Link:` 헤더에 직렬화해 directive smuggling 허용 |
| 고정 커밋 | `89da27ee4c241f5d649ba7d9b7f2188918af6cea` (2026-08-18, `git describe`: `2.19.0-3-g89da27e`)                        |

고정 커밋은 `2.19.0` 태그에 픽스 관련 커밋 3개를 더한 지점이다. `cowork-user`의 애플리케이션
코드는 `cow_link`를 직접 호출하지 않으므로 실제 노출은 `cowboy`가 `Link:` 헤더를 직렬화하는
경로에 한정되며, 이 범위는 아직 별도로 확인하지 않았다.

## 최초 점검 당시 선언

`cowork-user/mix.exs`:

```elixir
# Remove the override after Hex publishes a Cowlib release containing CVE-2026-43971's fix.
{:cowlib,
 git: "https://github.com/ninenines/cowlib.git",
 ref: "89da27ee4c241f5d649ba7d9b7f2188918af6cea",
 override: true},
```

`cowork-user/mix.lock`:

```text
"cowlib": {:git, "https://github.com/ninenines/cowlib.git", "89da27ee...", [ref: "89da27ee..."]},
```

## 복귀 조건

다음을 모두 만족하는 Hex 릴리스가 나오면 override를 제거한다.

- 릴리스에 `89da27e`가 포함되어 있다. 버전 번호가 아니라 커밋 포함 여부로 판단한다.
- 해당 버전이 `cowboy`의 `cowlib >= 2.19.0 and < 3.0.0` 제약을 만족한다.
- 릴리스가 요구하는 Erlang/OTP 최소 버전이 현재 런타임 이하다. upstream master는 픽스 이후
  OTP-27 이상을 요구하도록 바뀌었고, 두 dockerfile은 `erlang-27.3.4` 이미지를 사용하므로
  현재 기준으로는 문제가 없다.

## 할 일

### Hex 릴리스 추적

- `cowlib`의 Hex 릴리스 목록을 주기적으로 확인한다.

  ```bash
  curl -s https://hex.pm/api/packages/cowlib | jq -r '.releases[0:5][] | "\(.version) \(.inserted_at)"'
  ```

- 새 릴리스가 나오면 해당 태그가 고정 커밋을 포함하는지 확인한다.

  ```bash
  git -C cowork-user/deps/cowlib fetch --tags origin
  git -C cowork-user/deps/cowlib merge-base --is-ancestor 89da27ee4c241f5d649ba7d9b7f2188918af6cea {태그}
  ```

- 릴리스가 장기간 나오지 않으면 upstream 이슈나 릴리스 정책을 확인하고, 계속 고정할지 다른
  대응으로 바꿀지 판단한다.

### override 제거

- `mix.exs`의 `:cowlib` 항목과 그 위의 임시 조치 주석을 함께 제거한다. `cowboy`가 전이 의존성으로
  Hex 버전을 끌어오게 두고, 명시적 버전 고정이 필요한지 별도로 판단한다.
- `mix deps.unlock cowlib` 후 `mix deps.get`으로 `mix.lock` 항목이 `:git`에서 `:hex`로 바뀌고
  체크섬이 기록되는지 확인한다.
- `cowork-user/deps/cowlib`의 git 작업 디렉터리가 사라졌는지 확인하고, IDE에 남은 VCS root 매핑을
  정리한다.

## 검증

- `mix deps.get`과 `mix compile`이 새 의존성 구성에서 성공한다.
- `mix deps` 출력에서 `cowlib`이 Hex 패키지로 표시되고 `override` 표기가 사라진다.
- `mix.lock`의 `cowlib` 항목이 `:hex` 형식이고 체크섬을 포함한다.
- 핵심 비즈니스 단위 테스트가 통과한다.
- 복귀한 Hex 버전에서 `cow_link` 픽스가 실제로 적용되어 있는지 소스로 확인한다.

### 배포 전 추가 확인

새 Elixir·OTP 런타임에 대한 아래 확인은 Hex 복귀 TODO의 완료 조건에 포함하지 않으며, 아직 수행하지 않았다.

- `Dockerfile.local`과 `Dockerfile.prod`의 이미지 빌드를 확인한다.
- Plug/Cowboy HTTP 서버의 기동·요청 처리를 수동으로 확인한다.

## 완료 조건

- `mix.exs`에 `cowlib` git 의존성과 `override: true`가 남아 있지 않다.
- `mix.lock`의 `cowlib`이 Hex 패키지와 체크섬으로 고정되어 있다.
- 사용하는 `cowlib` 버전에 CVE-2026-43971 픽스가 포함되어 있다.
- `cowork-user/deps` 아래에 중첩 git 저장소가 생기지 않는다.
- `cowlib` 확보가 Hex 패키지로 완결되고 해당 의존성을 받기 위한 GitHub clone이 필요하지 않다.
