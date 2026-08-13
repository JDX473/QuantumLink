/**
 * 清理压测数据:删本轮压测用户 + 他们的消息 + Redis 会话/发号键。
 * 用法: node loadtest-cleanup.js [round]
 *   round 不填 = 清理所有 lt_* 前缀用户的数据
 */
const API = process.env.IM_API || 'http://127.0.0.1:8081';
const { execSync } = require('child_process');

const round = process.argv[2];
const prefix = round ? `lt_r${round}_` : 'lt_r%';

console.log(`清理用户前缀: ${prefix}`);

// 1. 查用户
const users = execSync(
  `mysql -u root -p123456 -h 127.0.0.1 quantumlink -N -e "SELECT user_id FROM im_user WHERE username LIKE '${prefix}'"`,
  { encoding: 'utf8' }
).trim().split('\n').filter(Boolean);
console.log(`待删用户: ${users.length} 个`);
if (users.length === 0) { console.log('无数据,结束'); process.exit(0); }

// 2. 删消息(sender 或 receiver 是这些用户)
const userIds = users.join("','");
execSync(
  `mysql -u root -p123456 -h 127.0.0.1 quantumlink -e "DELETE FROM im_message WHERE sender_id IN ('${userIds}') OR receiver_id IN ('${userIds}'); DELETE FROM im_group_message WHERE sender_id IN ('${userIds}');"`,
  { encoding: 'utf8' }
);

// 3. 删用户
execSync(
  `mysql -u root -p123456 -h 127.0.0.1 quantumlink -e "DELETE FROM im_user WHERE username LIKE '${prefix}'; DELETE FROM im_device WHERE user_id IN ('${userIds}'); DELETE FROM im_group_member WHERE user_id IN ('${userIds}');"`,
  { encoding: 'utf8' }
);

// 4. 清 Redis:会话表 + 发号键 + 幂等键 + 设备 Set
const redisKeys = execSync(
  `F:/Study/Redis4/redis-cli.exe -h 127.0.0.1 -p 6379 keys "im:*" 2>/dev/null`,
  { encoding: 'utf8' }
).trim().split('\n').filter(Boolean);
console.log(`Redis 待清 key: ${redisKeys.length} 个`);
if (redisKeys.length > 0) {
  execSync(`F:/Study/Redis4/redis-cli.exe -h 127.0.0.1 -p 6379 del ${redisKeys.join(' ')} 2>/dev/null`);
}

console.log('清理完成');
