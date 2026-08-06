// 一次性数据校准:Redis seq 计数器与 MySQL 最大 seq 对齐(修复 Redis 重启导致的 seq 回绕)
const { execSync } = require('child_process');
const MYSQL = 'F:/Study/MySQL/MySQL8.0/bin/mysql.exe';
const REDIS = 'F:/Study/Redis4/redis-cli.exe';

function mysql(q) {
  const out = execSync(`"${MYSQL}" -h127.0.0.1 -uroot -p123456 quantumlink -N -e "${q}"`, { encoding: 'utf8' });
  return out.split('\n').filter(Boolean).map(l => l.trim().split('\t'));
}
function redisGet(k) { return execSync(`"${REDIS}" -p 6379 GET "${k}"`, { encoding: 'utf8' }).trim(); }
// Windows cmd 不认 /dev/null,不能重定向;redis SET 返回 OK 到 stdout 无害,直接忽略返回值
function redisSet(k, v) { execSync(`"${REDIS}" -p 6379 SET "${k}" "${v}"`); }

let fixed = 0;
// 单聊会话 seq 计数器
for (const [conv, maxseq] of mysql('SELECT conversation_id, MAX(seq) FROM im_message GROUP BY conversation_id;')) {
  const cur = parseInt(redisGet(`im:conv:seq:${conv}`) || '0', 10);
  const max = parseInt(maxseq, 10);
  if (cur < max) { redisSet(`im:conv:seq:${conv}`, String(max)); console.log(`校准 conv=${conv} counter ${cur}->${max}`); fixed++; }
}
// 群 seq 计数器
for (const [gid, maxseq] of mysql('SELECT group_id, MAX(seq) FROM im_group_message GROUP BY group_id;')) {
  const cur = parseInt(redisGet(`im:group_seq:${gid}`) || '0', 10);
  const max = parseInt(maxseq, 10);
  if (cur < max) { redisSet(`im:group_seq:${gid}`, String(max)); console.log(`校准 group=${gid} counter ${cur}->${max}`); fixed++; }
}
console.log(`共校准 ${fixed} 个计数器`);
// 验证用户测试的会话
console.log('校验 conv 计数器 =', redisGet('im:conv:seq:u_1d4ac91b455c4453#u_e34e22443ed44d24'));
