#!/usr/bin/env bash
# 归档 APK：把 dist/ 里现有的包移入 dist/archive/，再把新构建放进去。
#
# 用法:
#   ./tools/archive-apk.sh <新构建的.apk> <标签>
# 例:
#   ./tools/archive-apk.sh app/build/outputs/apk/oss/release/NekoBox-1.4.2-arm64-v8a.apk encfix
#
# 归档文件名格式: <该apk最后修改时间 yyyyMMdd-HHmm>_<原文件名>
# 这样历史版本按构建时间天然排序。

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="$HERE/../dist"
ARCHIVE="$DIST/archive"
mkdir -p "$ARCHIVE"

src="${1:?用法: archive-apk.sh <apk> <label>}"
label="${2:-build}"
[ -f "$src" ] || { echo "找不到 APK: $src" >&2; exit 1; }

# 1) 归档上一版（dist 里所有现存 apk）
shopt -s nullglob
moved=0
for f in "$DIST"/*.apk; do
    base="$(basename "$f")"
    stamp="$(date -r "$f" +%Y%m%d-%H%M)"
    dest="$ARCHIVE/${stamp}_$base"
    # 同名冲突时加序号
    n=1
    while [ -e "$dest" ]; do dest="$ARCHIVE/${stamp}-${n}_$base"; n=$((n + 1)); done
    mv "$f" "$dest"
    echo "归档: $base -> $(basename "$dest")"
    moved=$((moved + 1))
done

# 2) 放新构建
dest="$DIST/NekoBox-1.4.2-arm64-v8a-$label.apk"
cp "$src" "$dest"
sha="$(sha256sum "$dest" | cut -d' ' -f1)"
size="$(du -h "$dest" | cut -f1)"

echo
echo "本次归档 $moved 个旧包"
echo "新构建: $dest  ($size)"
echo "sha256: $sha"
echo "历史:   $ARCHIVE/ ($(ls -1 "$ARCHIVE"/*.apk 2>/dev/null | wc -l) 个)"
