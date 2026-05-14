#!/system/bin/sh
export HOME=/data/local/tmp/openclaw-home
export LD_LIBRARY_PATH=/data/local/tmp/node-lib
export OPENSSL_CONF=/data/local/tmp/node-lib/openssl.cnf
export NODE_TLS_REJECT_UNAUTHORIZED=0
# Ensure app can read Agent workspace
chmod 755 /data/local/tmp/openclaw-home/.openclaw 2>/dev/null
cd /data/local/tmp/openclaw/lib/node_modules/openclaw
exec /data/local/tmp/node-termux openclaw.mjs gateway --port 18801
