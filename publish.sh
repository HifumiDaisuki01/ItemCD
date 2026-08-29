#!/bin/bash
# ItemCD 发布脚本 —— 通过 GitHub Contents API 上传（无需本地 git，可在跳板执行）
# 用法: publish.sh <GITHUB_TOKEN> <OWNER> [REPO] [DIR]
set -u

TOKEN="${1:?需要 GITHUB_TOKEN}"
OWNER="${2:?需要 OWNER}"
REPO="${3:-ItemCD}"
DIR="${4:-/root/.codebuddy/artifact/itemcd-release}"

API="https://api.github.com"
H1="Authorization: Bearer $TOKEN"
H2="Accept: application/vnd.github+json"

echo "=== 1/4 确保仓库存在 ==="
code=$(curl -s -o /tmp/repo_check.json -w "%{http_code}" -H "$H1" -H "$H2" "$API/repos/$OWNER/$REPO")
if [ "$code" = "200" ]; then
  echo "  仓库已存在: $OWNER/$REPO"
else
  echo "  创建仓库 $REPO (public)..."
  curl -s -o /tmp/repo_create.json -w "  创建 HTTP %{http_code}\n" \
    -X POST -H "$H1" -H "$H2" "$API/user/repos" \
    -d "{\"name\":\"$REPO\",\"description\":\"Minecraft 1.16.5 item cooldown control plugin \\u2014 \\u7269\\u54c1\\u51b7\\u5374\\u63a7\\u5236\\u63d2\\u4ef6 (deep compatibility with CrackShot / CrackShotPlus)\",\"private\":false,\"auto_init\":true}"
fi

echo ""
echo "=== 2/4 获取默认分支 ==="
DEFAULT=$(curl -s -H "$H1" -H "$H2" "$API/repos/$OWNER/$REPO" | python3 -c "import sys,json;print(json.load(sys.stdin).get('default_branch','main'))" 2>/dev/null)
DEFAULT="${DEFAULT:-main}"
echo "  默认分支: $DEFAULT"

echo ""
echo "=== 3/4 逐文件上传 ==="
cd "$DIR" || exit 1
OK=0; FAIL=0
while IFS= read -r f; do
  path="${f#./}"
  b64=$(base64 -w0 "$f" 2>/dev/null || base64 "$f" | tr -d '\n')
  resp=$(curl -s -X PUT -H "$H1" -H "$H2" "$API/repos/$OWNER/$REPO/contents/$path" \
    -d "{\"message\":\"Add $path\",\"content\":\"$b64\",\"branch\":\"$DEFAULT\"}")
  if echo "$resp" | grep -q '"content"'; then
    echo "  [OK]   $path"
    OK=$((OK+1))
  else
    err=$(echo "$resp" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('message','?'),[e.get('message') for e in d.get('errors',[])])" 2>/dev/null)
    echo "  [FAIL] $path : $err"
    FAIL=$((FAIL+1))
  fi
done < <(find . -type f ! -path './.git/*' | sort)

echo ""
echo "=== 4/4 结果 ==="
echo "  成功: $OK / 失败: $FAIL"
[ "$FAIL" = "0" ] && echo "  仓库地址: https://github.com/$OWNER/$REPO"
exit $FAIL
