// Destructive account setup tests always use an owned, disposable database/backend.
import { spawn, execFileSync } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'
import { createServer } from 'node:net'
import { closeSync, existsSync, mkdirSync, openSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'

const frontend = fileURLToPath(new URL('..', import.meta.url))
const repository = resolve(frontend, '..')
const jar = join(repository, 'backend/target/medical-rag-backend-0.1.0-SNAPSHOT.jar')
const image = 'pgvector/pgvector:0.8.2-pg17'
const name = `medical-rag-a3-e2e-${randomUUID().slice(0, 8)}`
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
let created = false
let backend
let playwright
let interrupted = false
const sleep = (ms) => new Promise((done) => setTimeout(done, ms))

async function freePort() {
  const server = createServer()
  await new Promise((done, fail) => { server.once('error', fail); server.listen(0, '127.0.0.1', done) })
  const port = server.address().port
  await new Promise((done) => server.close(done))
  return port
}
function docker(args, options = {}) {
  return execFileSync('docker', args, { encoding: 'utf8', timeout: 15000, ...options })
}
async function stop(child) {
  if (!child || child.exitCode !== null) return
  child.kill()
  for (let attempt = 0; attempt < 40 && child.exitCode === null; attempt++) await sleep(100)
  if (child.exitCode === null) child.kill('SIGKILL')
}
function interrupt() { interrupted = true; playwright?.kill() }
process.on('SIGINT', interrupt)
process.on('SIGTERM', interrupt)

try {
  if (!existsSync(jar)) throw new Error('请先在 backend 执行 Maven verify，生成后端 jar；本测试不使用已有后端。')
  const version = execFileSync(java, ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
  void version
  const endpoint = process.env.DOCKER_HOST || JSON.parse(docker(['context', 'inspect']))[0].Endpoints.docker.Host
  if (!endpoint.startsWith('unix://') && !endpoint.startsWith('npipe://')) throw new Error('隔离测试只允许本机 Docker socket/pipe，不操作远端 Docker。')
  docker(['info', '--format', '{{.ServerVersion}}'])
  const password = randomBytes(32).toString('base64url')
  const containerEnv = { ...process.env, POSTGRES_DB: 'a3_e2e', POSTGRES_USER: 'a3_e2e', POSTGRES_PASSWORD: password }
  docker(['run', '--detach', '--name', name, '--publish', '127.0.0.1::5432',
    '--env', 'POSTGRES_DB', '--env', 'POSTGRES_USER', '--env', 'POSTGRES_PASSWORD', image], { env: containerEnv, timeout: 180000 })
  created = true
  const databasePort = docker(['port', name, '5432/tcp']).trim().split(':').at(-1)
  let databaseReady = false
  for (let attempt = 0; attempt < 30 && !interrupted; attempt++) {
    try { docker(['exec', name, 'pg_isready', '-U', 'a3_e2e', '-d', 'a3_e2e'], { stdio: 'ignore' }); databaseReady = true; break }
    catch { await sleep(300) }
  }
  if (!databaseReady) throw new Error('隔离 PostgreSQL 未就绪。')
  const backendPort = await freePort()
  const frontendPort = await freePort()
  const environment = {
    ...process.env,
    DB_HOST: '127.0.0.1', DB_PORT: databasePort, DB_NAME: 'a3_e2e', DB_USERNAME: 'a3_e2e', DB_PASSWORD: password,
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${databasePort}/a3_e2e?connectTimeout=3&socketTimeout=3`,
    SPRING_DATASOURCE_USERNAME: 'a3_e2e', SPRING_DATASOURCE_PASSWORD: password,
    SERVER_PORT: String(backendPort), FRONTEND_PORT: String(frontendPort), SESSION_COOKIE_SECURE: 'false',
    // Test-instance override only; leave the production A2 30-minute timeout unchanged.
    SERVER_SERVLET_SESSION_TIMEOUT: '1m',
    MEDICAL_RAG_E2E_ISOLATED: '1', MEDICAL_RAG_E2E_BACKEND: `http://127.0.0.1:${backendPort}`,
  }
  mkdirSync(join(frontend, 'test-results'), { recursive: true })
  const logPath = join(frontend, 'test-results/isolated-backend.log')
  const log = openSync(logPath, 'w')
  backend = spawn(java, ['-jar', jar], { cwd: join(repository, 'backend'), env: environment, stdio: ['ignore', log, log] })
  closeSync(log)
  backend.on('error', () => { interrupted = true })
  let ready = false
  for (let attempt = 0; attempt < 100 && !interrupted; attempt++) {
    if (backend.exitCode !== null) break
    if (readFileSync(logPath, 'utf8').includes('Started MedicalRagApplication')) {
      try {
        const health = await fetch(`${environment.MEDICAL_RAG_E2E_BACKEND}/api/health`, { signal: AbortSignal.timeout(1000) })
        if (health.status === 200) { ready = true; break }
      } catch { }
    }
    await sleep(300)
  }
  if (!ready) throw new Error('隔离后端启动失败，请检查 test-results/isolated-backend.log。')
  const setupState = await fetch(`${environment.MEDICAL_RAG_E2E_BACKEND}/api/auth/setup-status`).then((response) => response.json())
  if (setupState.initialized !== false) throw new Error('测试数据库不是未初始化的独立数据库；停止操作，不清空任何数据。')
  console.log('使用独立临时 PostgreSQL/后端/前端端口；不会读取或修改现有管理员及数据卷。')
  playwright = spawn(process.execPath, [join(frontend, 'node_modules/@playwright/test/cli.js'), 'test', ...process.argv.slice(2)],
    { cwd: frontend, env: environment, stdio: 'inherit' })
  const code = await new Promise((done, fail) => { playwright.once('exit', done); playwright.once('error', fail) })
  process.exitCode = code ?? 1
} catch (error) {
  console.error(error instanceof Error ? error.message : '隔离测试失败。')
  process.exitCode = 1
} finally {
  await stop(playwright)
  await stop(backend)
  if (created) docker(['rm', '--force', '--volumes', name], { stdio: 'ignore' })
  console.log('仅清理本次创建的临时容器及其匿名卷，现有数据卷保持不变。')
}
