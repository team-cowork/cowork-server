FROM oven/bun:1.4.4-alpine@sha256:4cf82f5686b196504fe119db3fcd9185b191f82ece82c40680e597228ec6070a AS builder
WORKDIR /workspace/cowork-chat

COPY cowork-chat/package.json cowork-chat/bun.lock ./
RUN --mount=type=cache,id=bun-chat-builder,target=/root/.bun/install/cache \
    bun install --frozen-lockfile
COPY cowork-chat/tsconfig.json ./
COPY cowork-chat/src src
RUN bun run build


FROM builder AS verification
RUN apk --no-cache add bash python3
COPY cowork-chat/jest.config.js cowork-chat/xo.config.mjs ./
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=bun-chat-builder,target=/root/.bun/install/cache \
    bun run lint -- --max-warnings=0 \
    && bash /ci/run-native.sh bun cowork-chat /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-chat --snapshot pr --format istanbul --metric line --input /coverage/report.json --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM oven/bun:1.4.4-alpine@sha256:4cf82f5686b196504fe119db3fcd9185b191f82ece82c40680e597228ec6070a AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 app -G app
WORKDIR /app
COPY cowork-chat/package.json cowork-chat/bun.lock ./
RUN --mount=type=cache,id=bun-chat-runtime,target=/root/.bun/install/cache \
    bun install --frozen-lockfile --production
COPY --chown=app:app --from=builder /workspace/cowork-chat/dist ./dist
COPY --chown=app:app cowork-chat/public ./public
RUN test -s /app/dist/main.js && test -s /app/public/asyncapi.json
USER app
EXPOSE 8087
ENV PORT=8087
ENV NODE_ENV=production
ENTRYPOINT ["bun", "dist/main.js"]
