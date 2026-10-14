// 临时手机访问入口：静态构建产物 + 业务 API + WebSocket，独立口令保护，四小时后关闭。
import http from 'node:http'
import { randomBytes, createHash, timingSafeEqual } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { resolve, extname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn } from 'node:child_process'

const root = fileURLToPath(new URL('../frontend/dist/', import.meta.url))
const port = 5180
const expiresAt = Date.now() + 4 * 60 * 60 * 1000
const password = randomBytes(18).toString('base64url')
const session = randomBytes(32).toString('base64url')
const hash = (s) => createHash('sha256').update(s).digest()
const passwordHash = hash(`sems:${password}`)
const sessionHash = hash(session)
const attempts = new Map()
const sockets = new Set()
let tunnel
let publicHost = ''
let stopping = false

function authorized(req) {
  if (Date.now() >= expiresAt) return false
  const cookie = (req.headers.cookie || '').split(';').map((s) => s.trim())
    .find((s) => s.startsWith('__Host-sems_remote='))?.split('=')[1]
  if (cookie && timingSafeEqual(hash(cookie), sessionHash)) return true
  const auth = req.headers.authorization || ''
  if (!auth.startsWith('Basic ')) return false
  const value = Buffer.from(auth.slice(6), 'base64').toString('utf8')
  return timingSafeEqual(hash(value), passwordHash)
}

function validOrigin(req) {
  const origin = req.headers.origin
  if (!origin) return true
  return origin === `https://${publicHost}` || origin === `http://127.0.0.1:${port}`
}

function proxyHeaders(req) {
  const headers = { ...req.headers, host: '127.0.0.1:8090' }
  // 外层口令和 Cookie 不传入系统；业务 JWT 仍由系统校验。
  if (headers.authorization?.startsWith('Basic ')) delete headers.authorization
  delete headers.cookie
  delete headers['forwarded']
  delete headers['x-forwarded-for']
  delete headers['x-forwarded-host']
  delete headers['x-forwarded-proto']
  if (headers.origin) headers.origin = 'http://localhost:5173'
  return headers
}

const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.woff2': 'font/woff2', '.ico': 'image/x-icon' }

const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('X-Content-Type-Options', 'nosniff')
  res.setHeader('Referrer-Policy', 'no-referrer')
  res.setHeader('X-Frame-Options', 'DENY')
  if (!authorized(req)) {
    const key = req.headers['cf-connecting-ip'] || req.socket.remoteAddress
    const now = Date.now()
    const entry = attempts.get(key)
    const counter = entry && now - entry.start < 60000 ? entry : { start: now, count: 0 }
    counter.count++
    attempts.set(key, counter)
    if (counter.count > 20) { res.writeHead(429, { 'Retry-After': '60' }); res.end('请求过于频繁'); return }
    res.writeHead(401, { 'WWW-Authenticate': 'Basic realm="SEMS", charset="UTF-8"',
      'Content-Type': 'text/plain; charset=utf-8' })
    res.end('请输入临时访问用户名和口令。')
    return
  }
  if (!validOrigin(req)) { res.writeHead(403); res.end(); return }
  // Safari 同源 WebSocket 使用这枚短期 Cookie，口令不会出现在网址中。
  const maxAge = Math.max(0, Math.floor((expiresAt - Date.now()) / 1000))
  res.setHeader('Set-Cookie', `__Host-sems_remote=${session}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`)
  let pathname
  try { pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname) }
  catch { res.writeHead(400); res.end(); return }
  if (pathname.startsWith('/api/v1/') || pathname === '/api/health') {
    const upstream = http.request({ host: '127.0.0.1', port: 8090, path: req.url,
      method: req.method, headers: proxyHeaders(req), timeout: 25000 }, (incoming) => {
      const headers = { ...incoming.headers }
      delete headers['set-cookie']
      headers['cache-control'] = 'no-store'
      res.writeHead(incoming.statusCode, headers)
      incoming.pipe(res)
    })
    upstream.on('timeout', () => upstream.destroy())
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end('服务暂时不可用') })
    req.pipe(upstream)
    return
  }
  if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return }
  // 只提供 dist 文件，禁止源码、文档和任意目录穿越。
  const path = resolve(root, pathname === '/' ? 'index.html' : `.${pathname}`)
  if (!path.startsWith(root.endsWith(sep) ? root : root + sep) || pathname.split('/').some((s) => s.startsWith('.'))
      || pathname.endsWith('.map')) { res.writeHead(404); res.end(); return }
  try {
    if (!(await stat(path)).isFile()) throw new Error('非文件')
    res.setHeader('Content-Type', mime[extname(path)] || 'application/octet-stream')
    if (req.method === 'HEAD') res.end()
    else createReadStream(path).on('error', () => res.destroy()).pipe(res)
  } catch { res.writeHead(404); res.end() }
})

server.on('upgrade', (req, socket, head) => {
  if (req.url !== '/ws' || !authorized(req) || !validOrigin(req)) {
    socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n'); return
  }
  const upstream = http.request({ host: '127.0.0.1', port: 8090, path: '/ws',
    headers: proxyHeaders(req), timeout: 10000 })
  upstream.on('upgrade', (response, peer, peerHead) => {
    socket.write('HTTP/1.1 101 Switching Protocols\r\n' + Object.entries(response.headers)
      .map(([k, v]) => `${k}: ${v}\r\n`).join('') + '\r\n')
    if (peerHead.length) socket.write(peerHead)
    if (head.length) peer.write(head)
    socket.pipe(peer).pipe(socket)
    socket.on('error', () => peer.destroy())
    peer.on('error', () => socket.destroy())
    socket.on('close', () => peer.destroy())
    peer.on('close', () => socket.destroy())
  })
  upstream.on('response', () => socket.destroy())
  upstream.on('timeout', () => upstream.destroy())
  upstream.on('error', () => socket.destroy())
  upstream.end()
})
server.on('connection', (socket) => { sockets.add(socket); socket.on('close', () => sockets.delete(socket)) })

function stop() {
  if (stopping) return
  stopping = true
  tunnel?.kill()
  for (const socket of sockets) socket.destroy()
  server.close()
  console.log('临时手机入口已关闭。')
  setTimeout(() => process.exit(0), 1000)
}
process.on('SIGINT', stop)
process.on('SIGTERM', stop)
setTimeout(stop, expiresAt - Date.now())
setInterval(() => { for (const [key, value] of attempts) if (Date.now() - value.start > 60000) attempts.delete(key) }, 60000).unref()

server.listen(port, '127.0.0.1', () => {
  console.log(`访问用户名：sems\n访问口令：${password}\n自动关闭时间：${new Date(expiresAt).toISOString()}\n网关端口：${port}`)
  const executable = process.env.SEMS_CLOUDFLARED
  if (!executable) { console.error('缺少 SEMS_CLOUDFLARED 路径'); stop(); return }
  tunnel = spawn(executable, ['tunnel', '--url', `http://127.0.0.1:${port}`, '--protocol', 'http2', '--no-autoupdate'],
    { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  function output(chunk) {
    const value = chunk.toString()
    const match = value.match(/https:\/\/([a-z0-9-]+\.trycloudflare\.com)/)
    if (match) { publicHost = match[1]; console.log(`手机访问地址：https://${publicHost}`) }
    process.stderr.write(value)
  }
  tunnel.stdout.on('data', output)
  tunnel.stderr.on('data', output)
  tunnel.on('error', (err) => { console.error(err.message); stop() })
  tunnel.on('exit', stop)
})
