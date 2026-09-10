#!/usr/bin/env bash
# Central Portal Publisher API로 번들을 올리고 게시될 때까지 기다린다.
#   CENTRAL_USERNAME=… CENTRAL_PASSWORD=… scripts/central-publish.sh 0.1.2 [bundle.zip]
# 번들 경로를 생략하면 GitHub Release <version> 첨부에서 내려받는다(gh 필요).
# 자격증명은 central.sonatype.com → View Account → Generate User Token. 로그에 찍지 않는다.
set -euo pipefail
VER="${1:?version (예 0.1.2)}"
BUNDLE="${2:-}"
: "${CENTRAL_USERNAME:?CENTRAL_USERNAME 필요}"; : "${CENTRAL_PASSWORD:?CENTRAL_PASSWORD 필요}"
API=https://central.sonatype.com/api/v1/publisher
if [ -z "$BUNDLE" ]; then
  BUNDLE="nudgeon-sdk-${VER}-central-bundle.zip"
  [ -f "$BUNDLE" ] || gh release download "$VER" -R NudgeOn/nudgeon-android-sdk -p "$BUNDLE"
fi
[ -f "$BUNDLE" ] || { echo "번들 없음: $BUNDLE" >&2; exit 1; }
unzip -l "$BUNDLE" | grep -q "io/nudgeon/nudgeon-sdk/${VER}/nudgeon-sdk-${VER}.aar" || { echo "번들에 ${VER} aar가 없다" >&2; exit 1; }

TOKEN=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')
echo "업로드: $BUNDLE → Central Portal (AUTOMATIC)"
ID=$(curl -fsS -H "Authorization: Bearer $TOKEN" -F "bundle=@${BUNDLE}" \
  "${API}/upload?name=nudgeon-sdk-${VER}&publishingType=AUTOMATIC")
echo "deployment id: $ID"

for i in $(seq 1 60); do
  RES=$(curl -fsS -X POST -H "Authorization: Bearer $TOKEN" "${API}/status?id=${ID}")
  STATE=$(printf '%s' "$RES" | sed -n 's/.*"deploymentState" *: *"\([A-Z_]*\)".*/\1/p')
  echo "[$i] $STATE"
  case "$STATE" in
    PUBLISHED) echo "게시 완료 — repo1.maven.org 동기화는 최대 수십 분"; exit 0 ;;
    FAILED) echo "$RES" | sed 's/^/  /'; exit 1 ;;
  esac
  sleep 30
done
echo "30분 내 PUBLISHED 미도달 — https://central.sonatype.com/publishing/deployments 에서 확인" >&2
exit 2
