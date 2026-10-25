#!/usr/bin/env bash
# CI 与 release 共用的离线自检：审计回路、profile 真解析器、插件边界测试。
#
# 这些检查不需要设备，但 release 以前只跑 gradle 门禁，插件测试与 profile 校验被漏掉了。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

# 扫描器/解码器确实会命中，免得设备上的空结果被当成「一切正常」。
node tools/audit/selftest.mjs

# js-yaml 的版本锁在 tools/offline-checks/package-lock.json；--ignore-scripts 挡住安装脚本。
npm --prefix tools/offline-checks ci --ignore-scripts --no-audit --no-fund
# workflow 脚本和 APK 载荷契约也必须在发版前离线回归。
node --test tools/ci/*.test.mjs
node tools/dsh-phone/check-profile.mjs tools/dsh-phone/profile/cordis.patch.yml tools/offline-checks/node_modules

# 连接器的「只读」与「令牌不外泄」两条承诺靠插件测试守住。
(cd tools/dsh-phone/plugins/gmail && npm test)
# zen 会话头补丁作用在所有出网请求上，边界必须钉住。
node tools/dsh-phone/test/zen-session-fetch.test.mjs
# UI-TARS 适配层：唯一把它的原生动作翻成执行层动作的地方。
node tools/uitars/test/adapt.test.mjs
# 提问 answerer 的边界：接不了必须让路，不能编答案。
(cd tools/dsh-phone/plugins/user-questions && npm test)
# 审批 answerer：只回封闭结果，桥不可用时 fail closed（不能让路挂住整轮）。
(cd tools/dsh-phone/plugins/approval && npm test)
