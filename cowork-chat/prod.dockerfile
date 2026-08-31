FROM node:24-alpine AS builder
WORKDIR /app

COPY package.json package-lock.json ./
RUN --mount=type=cache,id=npm-chat-builder,target=/root/.npm \
    npm ci
COPY tsconfig.json ./
COPY src src
RUN npm run build

FROM node:24-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,id=npm-chat-runtime,target=/root/.npm \
    npm ci --omit=dev \
    && npm cache clean --force
COPY --chown=app:app --from=builder /app/dist ./dist
COPY --chown=app:app public ./public
USER app
EXPOSE 8087
ENV PORT=8087
ENV NODE_ENV=production
ENTRYPOINT ["node", "dist/main.js"]
