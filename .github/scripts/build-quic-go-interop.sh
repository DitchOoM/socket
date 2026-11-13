#!/usr/bin/env bash
# Build quic-go's interop server and client for a native lane (quic-interop-native.yaml): <out-dir>.
#
# They are written for the runner's container: /certs, /www and /downloads at the root and port 443. A native
# lane cannot make those paths (macOS has a read-only root), so a copy is patched to read CERTS, WWW,
# DOWNLOADS and PORT from the environment, as our endpoint does. Each edit is checked, so a reshaped upstream
# source fails here rather than building a binary that ignores the environment.
set -euo pipefail
out=$1
src=$(mktemp -d)
git clone --depth 1 https://github.com/quic-go/quic-go "$src" >/dev/null 2>&1
cd "$src"
git log -1 --format='quic-go %H %cs'
perl -pi -e 's#"/certs/cert.pem"#os.Getenv("CERTS")+"/cert.pem"#; s#"/certs/priv.key"#os.Getenv("CERTS")+"/priv.key"#; s#http\.Dir\("/www"\)#http.Dir(os.Getenv("WWW"))#g; s#":443"#":"+os.Getenv("PORT")#g' interop/server/main.go
perl -pi -e 's#os\.Create\("/downloads" \+#os.Create(os.Getenv("DOWNLOADS") +#' interop/client/main.go
for want in 'os.Getenv("CERTS")+"/cert.pem"' 'http.Dir(os.Getenv("WWW"))' '":"+os.Getenv("PORT")'; do
  grep -qF "$want" interop/server/main.go || { echo "::error::quic-go's interop server changed shape: '$want' was not patched in"; exit 1; }
done
grep -qF 'os.Create(os.Getenv("DOWNLOADS") +' interop/client/main.go || { echo "::error::quic-go's interop client changed shape: the downloads path was not patched"; exit 1; }
if grep -n -E '"/(certs|www|downloads)' interop/server/main.go interop/client/main.go; then
  echo "::error::quic-go's interop endpoints still name a container path (listed above)"
  exit 1
fi
mkdir -p "$out"
exe=""
case "$(go env GOOS)" in windows) exe=.exe ;; esac
go build -o "$out/qgo-server$exe" ./interop/server
go build -o "$out/qgo-client$exe" ./interop/client
ls -la "$out"
