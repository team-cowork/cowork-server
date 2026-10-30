FROM node:26.8.2-alpine3.24@sha256:ef24c5053d50fdc3e4e56eb4e7ddb7861874ab0fdc797046ba897581deb8e868 AS builder
WORKDIR /workspace/cowork-github

COPY cowork-github/package.json cowork-github/package-lock.json ./
RUN --mount=type=cache,id=npm-github-builder,target=/root/.npm \
    npm ci
COPY cowork-github/tsconfig.json cowork-github/tsconfig.build.json cowork-github/nest-cli.json ./
COPY cowork-github/src src
RUN npm run build

FROM builder AS verification
RUN apk --no-cache add bash python3
COPY cowork-github/eslint.config.mjs cowork-github/xo.config.mjs ./
COPY cowork-github/test test
COPY deploy/ci/verification /ci
RUN --mount=type=cache,id=npm-github-builder,target=/root/.npm \
    npm run lint -- --max-warnings=0 \
    && bash /ci/run-native.sh jest cowork-github /workspace /coverage \
    && python3 /ci/report.py collect --module cowork-github --snapshot pr --format istanbul --metric line --input /coverage/report.json --output /coverage/coverage.json --exit-code 0

FROM scratch AS verify
COPY --from=verification /coverage /

FROM node:26.8.2-alpine3.24@sha256:ef24c5053d50fdc3e4e56eb4e7ddb7861874ab0fdc797046ba897581deb8e868 AS runtime
RUN addgroup -S -g 10001 app && adduser -S -u 10001 app -G app
WORKDIR /app
COPY cowork-github/package.json cowork-github/package-lock.json ./
RUN --mount=type=cache,id=npm-github-runtime,target=/root/.npm \
    npm ci --omit=dev
COPY --chown=app:app --from=builder /workspace/cowork-github/dist ./dist
RUN test -s /app/dist/main.js
USER app
EXPOSE 8090
ENV PORT=8090
ENV NODE_ENV=production
ENTRYPOINT ["node", "dist/main.js"]
