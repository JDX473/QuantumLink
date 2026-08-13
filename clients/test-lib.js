/**
 * 测试公共库:注册/登录真实用户(新鉴权流程)。
 *
 * 历史背景:早期验证脚本硬编码 test-token-123 等假 token(旧设计"Redis 预置 token"),
 * 与服务端"注册→登录→token 握手"的现行身份体系不兼容(握手校验 im:token:{token})。
 * 统一改造为:随机用户名 → 注册(幂等,已存在则直接登录)→ 登录拿 token/deviceId。
 *
 * 用法:
 *   const { newUser, loginOrRegister } = require('./test-lib');
 *   const A = await newUser('chatA');   // 随机后缀,多跑不冲突
 */
const API = process.env.IM_API || 'http://127.0.0.1:8081';

let _n = 0;
function unique() {
  // 时间戳(秒级)+ 进程内自增,保证同秒并发多用户也不冲突
  return 't' + (Date.now() % 1000000) + '_' + (++_n);
}

/** 登录,失败则注册后再登录(用户名被种子用户/上次运行占用时也安全) */
async function loginOrRegister(username, password = 'pass123') {
  const login = () => fetch(API + '/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, deviceType: 'desktop' }),
  }).then(r => r.json());
  let d = await login();
  if (!d.success) {
    await fetch(API + '/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    d = await login();
  }
  return d;
}

/** 注册+登录一个随机用户名的新用户(每次调用都不同,多跑不撞) */
async function newUser(prefix = 'u') {
  return loginOrRegister(`${prefix}_${unique()}`);
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

module.exports = { API, loginOrRegister, newUser, sleep };
