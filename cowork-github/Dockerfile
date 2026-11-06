FROM oven/bun:1.4.4-alpine@sha256:4cf82f5686b196504fe119db3fcd9185b191f82ece82c40680e597228ec6070a AS builder
WORKDIR /workspace/cowork-github

COPY cowork-github/package.json cowork-github/bun.lock cowork-github/bunfig.toml ./
RUN --mount=type=cache,id=bun-github-builder,target=/root/.bun/install/cache \
    bun install --frozen-lockfile
COPY cowork-github/tsconfig.json cowork-github/tsconfig.build.json ./
COPY cowork-github/src src
RUN bun run build

FROM builder AS verification
RUN apk --no-cache add bash python3
COPY cowork-github/eslint.config.mjs cowork-github/xo.config.mjs cowork-github/jest.config.js ./
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=bun-github-builder,target=/root/.bun/install/cache \
    bun run lint -- --max-warnings=0 \
    && bash /ci/run-native.sh bun cowork-github /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-github --snapshot pr --format istanbul --metric line --input /coverage/report.json --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM oven/bun:1.4.4-alpine@sha256:4cf82f5686b196504fe119db3fcd9185b191f82ece82c40680e597228ec6070a AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 app -G app
WORKDIR /app
COPY cowork-github/package.json cowork-github/bun.lock cowork-github/bunfig.toml ./
RUN --mount=type=cache,id=bun-github-runtime,target=/root/.bun/install/cache \
    bun install --frozen-lockfile --production
COPY --chown=app:app --from=builder /workspace/cowork-github/dist ./dist
RUN test -s /app/dist/main.js
USER app
EXPOSE 8090
ENV PORT=8090
ENV NODE_ENV=production
ENTRYPOINT ["bun", "dist/main.js"]
