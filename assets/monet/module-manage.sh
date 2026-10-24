#!/system/bin/sh
# Monet entry point retained for existing app callers.
exec sh /data/user/0/dev.codex.watchworkshop/files/monet/runtime-manage.sh "${1:-status}" monet
