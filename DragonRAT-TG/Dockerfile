# Stage 1: Build TypeScript
FROM node:22-slim AS build

WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install --no-audit --no-fund
COPY src/ ./src/
RUN npm run build

# Stage 2: Production image
FROM node:22-slim

ENV NODE_ENV=production

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        wget \
        tini \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 1001 app \
    && useradd --system --uid 1001 --gid 1001 --create-home app

WORKDIR /app

COPY --from=build /app/dist ./dist
COPY package.json ./

RUN npm install --omit=dev --no-audit --no-fund

COPY factory/ ./factory/
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN mkdir -p /app/data \
    && chown -R app:app /app \
    && chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8000

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD wget --spider -q http://localhost:8000/health || exit 1

CMD ["node", "dist/index.js"]
