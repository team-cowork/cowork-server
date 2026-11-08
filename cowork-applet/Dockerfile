FROM eclipse-temurin:25.0.5_8-jdk-alpine-3.24@sha256:c7c3d0bdf7f2bbc5a05a6712ed334d198360105634ea9adf76fca94f381b1e3f AS builder
RUN apk --no-cache add bash curl
WORKDIR /workspace/cowork-applet

COPY --chmod=0755 cowork-applet/sbtw sbtw
COPY cowork-applet/build.sbt build.sbt
COPY cowork-applet/project/build.properties cowork-applet/project/plugins.sbt project/
COPY cowork-applet/conf conf
COPY cowork-applet/app app
RUN --mount=type=cache,id=cowork-sbt,target=/root/.sbt,sharing=locked \
    --mount=type=cache,id=cowork-sbt-cache,target=/root/.cache,sharing=locked \
    ./sbtw stage


FROM builder AS verification
RUN apk --no-cache add python3
COPY cowork-applet/test test
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=cowork-sbt,target=/root/.sbt,sharing=locked \
    --mount=type=cache,id=cowork-sbt-cache,target=/root/.cache,sharing=locked \
    bash /ci/run-jvm.sh sbt cowork-applet /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-applet --snapshot pr --format jacoco --metric line --input /coverage/report.xml --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM eclipse-temurin:25.0.5_8-jre-alpine-3.24@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918 AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 app -G app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/cowork-applet/target/universal/stage/conf conf
COPY --chown=app:app --from=builder /workspace/cowork-applet/target/universal/stage/lib lib
USER app
EXPOSE 8091
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "--sun-misc-unsafe-memory-access=allow", "-Dpidfile.path=/dev/null", "-cp", "conf:lib/*", "play.core.server.ProdServerStart"]
