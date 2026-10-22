/**
 State 이벤트의 식별용 owner/repo 정규화. GitHub은 owner/repo의 대소문자를 구분하지 않으므로
 `project.github-repo.event`와 같은 규칙으로 소문자로 맞춘다. 화면에 보이는 `fullName`·`htmlUrl`은 바꾸지 않는다.
 */
export const toRepoIdentity = (name: string): string => name.toLowerCase();
