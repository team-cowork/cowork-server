# ~~MeetingNote 날짜 직렬화 형식 오류~~

- ~~**서비스**: cowork-channel~~
- ~~**우선순위**: 🔴 즉시 필요~~

## ~~문제~~

~~Spring Boot 기본 Jackson이 `LocalDateTime`을 JSON 배열 `[2024,1,1,12,0,0]`로 직렬화한다.~~
~~클라이언트는 ISO-8601 문자열 `"2024-01-01T12:00:00"` 형식을 기대한다.~~

~~**영향 필드**: `MeetingNoteResponse.createdAt`, `MeetingNoteResponse.updatedAt`~~

## ~~해결~~

~~`application.yml`에 아래 설정 추가:~~

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
```

~~또는 `ObjectMapper` 빈에서 직접 설정:~~

```kotlin
@Bean
fun objectMapper(): ObjectMapper = ObjectMapper().apply {
    registerModule(JavaTimeModule())
    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
}
```
