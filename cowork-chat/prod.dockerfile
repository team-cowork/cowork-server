# 빌드 전 dist/ (npm run build) 산출물이 컨텍스트에 있어야 한다.
# TODO: CI 산출물 핸드오프 배선 후 이 주석 삭제
FROM node:22-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --chown=app:app dist ./dist
USER app
EXPOSE 8087
ENV PORT=8087
ENV NODE_ENV=production
CMD ["node", "dist/main.js"]
