// P0 自检脚本：证明内嵌 Node 能启动、环境变量生效，并把版本信息写到状态文件。
// 正式运行时这里会换成 dsh 启动器（见 docs/dsh-android.md）。
const fs = require('fs');
const os = require('os');

const statusFile = process.env.FACTOTUM_STATUS_FILE;
const info = {
  version: process.version,
  mobile: process.versions.mobile || null,
  platform: process.platform,
  arch: process.arch,
  tmpdir: os.tmpdir(),
  homedir: os.homedir(),
  pagesize: (() => {
    try {
      return require('node:os').pageSize ? os.pageSize : null;
    } catch (e) {
      return null;
    }
  })(),
};

try {
  if (statusFile) fs.writeFileSync(statusFile, JSON.stringify(info, null, 2));
} catch (error) {
  console.error('[factotum-node] write status failed: ' + error.message);
}
console.log('[factotum-node] ' + JSON.stringify(info));

// 存活 5 秒再退出：P0 只验证生命周期；P1 会换成常驻的 dsh 进程。
setTimeout(() => {
  console.log('[factotum-node] alive 5s, exiting');
  process.exit(0);
}, 5000);
