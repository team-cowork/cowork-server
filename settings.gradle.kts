rootProject.name = "cowork-server"

// 인프라 모듈 (Spring Boot 확정)
include("cowork-gateway")
include("cowork-config")

// 비즈니스 서비스 모듈 — 스택 확정 후 include 추가
include("cowork-user")
// include("cowork-channel")
// include("cowork-project")
// include("cowork-team")
include("cowork-preference")
include("cowork-team")
