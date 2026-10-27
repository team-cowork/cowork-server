pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "cowork-server"

include("cowork-gateway")
include("cowork-channel")
include("cowork-team")
include("cowork-preference")
include("cowork-roadmap")
