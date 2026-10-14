// Destructive account setup tests always use an owned, disposable database/backend.
import { spawn, execFileSync } from 'node:child_process'
import { randomBytes, randomUUID } from 'node:crypto'
import { createServer } from 'node:net'
import { createServer as createHttpServer } from 'node:http'
import { closeSync, copyFileSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { join, resolve } from 'node:path'

const frontend = fileURLToPath(new URL('..', import.meta.url))
const repository = resolve(frontend, '..')
const jar = join(repository, 'backend/target/medical-rag-backend-0.1.0-SNAPSHOT.jar')
const image = 'pgvector/pgvector:0.8.2-pg17'
const name = `medical-rag-e2e-${randomUUID().slice(0, 8)}`
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
let created = false
let frontendProcess
let fixturesRoot
let backend
let playwright
let control
let storageRoot
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
  if (!child || child.exitCode !== null || child.signalCode !== null) return
  child.kill()
  for (let attempt = 0; attempt < 40 && child.exitCode === null && child.signalCode === null; attempt++) await sleep(100)
  if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
  for (let attempt = 0; attempt < 50 && child.exitCode === null && child.signalCode === null; attempt++) await sleep(100)
  if (child.exitCode === null && child.signalCode === null) throw new Error('测试子进程未退出；拒绝提前清理其文件目录。')
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
  const containerEnv = { ...process.env, POSTGRES_DB: 'medical_rag_e2e', POSTGRES_USER: 'medical_rag_e2e', POSTGRES_PASSWORD: password }
  docker(['run', '--detach', '--name', name, '--publish', '127.0.0.1::5432',
    '--env', 'POSTGRES_DB', '--env', 'POSTGRES_USER', '--env', 'POSTGRES_PASSWORD', image], { env: containerEnv, timeout: 180000 })
  created = true
  const databasePort = docker(['port', name, '5432/tcp']).trim().split(':').at(-1)
  let databaseReady = false
  for (let attempt = 0; attempt < 30 && !interrupted; attempt++) {
    try { docker(['exec', name, 'pg_isready', '-U', 'medical_rag_e2e', '-d', 'medical_rag_e2e'], { stdio: 'ignore' }); databaseReady = true; break }
    catch { await sleep(300) }
  }
  if (!databaseReady) throw new Error('隔离 PostgreSQL 未就绪。')
  const backendPort = await freePort()
  const frontendPort = await freePort()
  storageRoot = mkdtempSync(join(tmpdir(), 'medical-rag-e2e-storage-'))
  const environment = {
    ...process.env,
    DB_HOST: '127.0.0.1', DB_PORT: databasePort, DB_NAME: 'medical_rag_e2e', DB_USERNAME: 'medical_rag_e2e', DB_PASSWORD: password,
    SPRING_DATASOURCE_URL: `jdbc:postgresql://127.0.0.1:${databasePort}/medical_rag_e2e?connectTimeout=3&socketTimeout=3`,
    SPRING_DATASOURCE_USERNAME: 'medical_rag_e2e', SPRING_DATASOURCE_PASSWORD: password,
    SERVER_PORT: String(backendPort), FRONTEND_PORT: String(frontendPort), SESSION_COOKIE_SECURE: 'false',
    APP_STORAGE_ROOT: storageRoot, APP_IMAGE_MAX_WIDTH: '8192', APP_IMAGE_MAX_HEIGHT: '8192', APP_IMAGE_MAX_PIXELS: '16000000',
    MEDICAL_RAG_E2E_FRONTEND_OWNED: '1',
    APP_STORAGE_MAX_PDF_BYTES: '52428800', APP_STORAGE_MAX_IMAGE_BYTES: '20971520',
    // Test-instance override only; leave the production A2 30-minute timeout unchanged.
    SERVER_SERVLET_SESSION_TIMEOUT: '1m',
    MEDICAL_RAG_E2E_ISOLATED: '1', MEDICAL_RAG_E2E_BACKEND: `http://127.0.0.1:${backendPort}`,
  }
  fixturesRoot = mkdtempSync(join(tmpdir(), 'medical-rag-e2e-fixtures-'))
  execFileSync(java, ['-cp', join(repository, 'backend/target/test-classes'), 'com.medicalrag.image.SyntheticImages', fixturesRoot])
  const runtimeJar = join(fixturesRoot, 'application.jar')
  copyFileSync(jar, runtimeJar) // Owned immutable run snapshot; Maven may rebuild target later.
  environment.MEDICAL_RAG_E2E_FIXTURES = fixturesRoot
  // Playwright clears test-results at startup. Keep the live backend log separately.
  mkdirSync(join(frontend, '.cache'), { recursive: true })
  const logPath = join(frontend, `.cache/${name}-backend.log`)
  async function launchBackend() {
    const log = openSync(logPath, 'w')
    const childEnvironment = { ...environment }
    // Test environment only: ignore inherited alternate profiles/JVM overrides that
    // could redirect Spring to a user's database. Password stays in the child env.
    for (const key of ['SPRING_APPLICATION_JSON', 'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION', 'SPRING_PROFILES_ACTIVE', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) delete childEnvironment[key]
    backend = spawn(java, ['-jar', runtimeJar, `--spring.datasource.url=${environment.SPRING_DATASOURCE_URL}`, '--spring.datasource.username=medical_rag_e2e', '--server.address=127.0.0.1', `--server.port=${backendPort}`, `--app.storage.root=${storageRoot}`, `--app.models.key-file=${join(fixturesRoot, 'secrets', 'model-master.key')}`, '--app.models.allow-loopback-http=true', '--app.storage.max-pdf-bytes=52428800', '--app.storage.max-image-bytes=20971520', '--app.image.max-width=8192', '--app.image.max-height=8192', '--app.image.max-pixels=16000000'], { cwd: join(repository, 'backend'), env: childEnvironment, stdio: ['ignore', log, log] })
    closeSync(log)
    backend.on('error', () => { interrupted = true })
    for (let attempt = 0; attempt < 100 && !interrupted; attempt++) {
      if (backend.exitCode !== null) break
      if (readFileSync(logPath, 'utf8').includes('Started MedicalRagApplication')) {
        try {
          const health = await fetch(`${environment.MEDICAL_RAG_E2E_BACKEND}/api/health`, { signal: AbortSignal.timeout(1000) })
          if (health.status === 200) return
        } catch { }
      }
      await sleep(300)
    }
    throw new Error(`隔离后端启动失败，请检查 ${logPath}。`)
  }
  async function launchFrontend() {
    const frontendLog = openSync(join(frontend, `.cache/${name}-frontend.log`), 'a')
    frontendProcess = spawn(process.execPath, [join(frontend, 'node_modules/vite/bin/vite.js'), ...(environment.MEDICAL_RAG_E2E_PRODUCTION === '1' ? ['preview'] : []), '--host', '127.0.0.1', '--port', String(frontendPort), '--strictPort'], { cwd: frontend, env: environment, stdio: ['ignore', frontendLog, frontendLog] })
    closeSync(frontendLog)
    frontendProcess.on('error', () => { interrupted = true })
    for (let attempt = 0; attempt < 100 && !interrupted; attempt++) {
      if (frontendProcess.exitCode !== null) break
      try { if ((await fetch(`http://127.0.0.1:${frontendPort}`, { signal: AbortSignal.timeout(1000) })).status === 200) return } catch { }
      await sleep(100)
    }
    throw new Error('隔离前端启动失败。')
  }
  async function restartDatabaseAndApplications() {
    if (!created || !name.startsWith('medical-rag-e2e-') || !storageRoot) throw new Error('Missing owned test resources')
    // SQL assertions before AND after a real container stop/start. No supplied container
    // name, PID or storage path is accepted by this loopback test control endpoint.
    const query = args => docker(['exec', name, 'psql', '-U', 'medical_rag_e2e', '-d', 'medical_rag_e2e', '-Atc', args]).trim()
    const beforeHash = query('SELECT password_hash FROM users ORDER BY id')
    if (!beforeHash.startsWith('$2')) throw new Error('Expected an isolated BCrypt administrator')
    if (query("SELECT count(*) FROM flyway_schema_history WHERE version='5' AND success") !== '1') throw new Error('V5 not installed once')
    await stop(frontendProcess); await stop(backend)
    docker(['stop', '--time', '10', name], { timeout: 30000 })
    if (docker(['inspect', '--format', '{{.State.Running}}', name]).trim() !== 'false') throw new Error('Database did not stop')
    docker(['start', name], { timeout: 30000 })
    let ready = false
    for (let attempt = 0; attempt < 60; attempt++) {
      try { docker(['exec', name, 'pg_isready', '-U', 'medical_rag_e2e', '-d', 'medical_rag_e2e'], { stdio: 'ignore' }); ready = true; break } catch { await sleep(200) }
    }
    if (!ready) throw new Error('Isolated database restart timed out')
    // Docker may reassign an ephemeral host port on restart.
    const restartedPort = docker(['port', name, '5432/tcp']).trim().split(':').at(-1)
    environment.DB_PORT = restartedPort
    environment.SPRING_DATASOURCE_URL = `jdbc:postgresql://127.0.0.1:${restartedPort}/medical_rag_e2e?connectTimeout=3&socketTimeout=3`
    if (query('SELECT password_hash FROM users ORDER BY id') !== beforeHash) throw new Error('Administrator hash changed across restart')
    await launchBackend(); await launchFrontend()
    if (query("SELECT count(*) FROM flyway_schema_history WHERE version='5' AND success") !== '1') throw new Error('V5 repeated')
    console.log('A8：自建 PostgreSQL 实际停止/启动，BCrypt 哈希保持，前后端真实重启，V5 仍只执行一次。')
  }
  await launchBackend()
  await launchFrontend()
  const setupState = await fetch(`${environment.MEDICAL_RAG_E2E_BACKEND}/api/auth/setup-status`).then((response) => response.json())
  if (setupState.initialized !== false) throw new Error('测试数据库不是未初始化的独立数据库；停止操作，不清空任何数据。')
  // Test runner control only, never an application API. It can restart ONLY the child
  // process this runner owns, using a temporary random bearer value on loopback.
  const controlToken = randomBytes(32).toString('base64url')
  let restarting = false
  control = createHttpServer(async (request, response) => {
    if (request.method !== 'POST' || !['/restart', '/restart-all'].includes(request.url) || request.headers.authorization !== `Bearer ${controlToken}`) {
      response.writeHead(403).end(); return
    }
    if (restarting) { response.writeHead(409).end(); return }
    restarting = true
    try { if (request.url === '/restart-all') await restartDatabaseAndApplications(); else { await stop(backend); await launchBackend() }; response.writeHead(204).end() }
    catch { response.writeHead(500).end('Isolated backend restart failed') }
    finally { restarting = false }
  })
  await new Promise((done, fail) => { control.once('error', fail); control.listen(0, '127.0.0.1', done) })
  environment.MEDICAL_RAG_E2E_CONTROL = `http://127.0.0.1:${control.address().port}`
  environment.MEDICAL_RAG_E2E_CONTROL_TOKEN = controlToken
  console.log('使用独立临时 PostgreSQL/后端/前端端口；不会读取或修改现有管理员及数据卷。')
  playwright = spawn(process.execPath, [join(frontend, 'node_modules/@playwright/test/cli.js'), 'test', ...process.argv.slice(2)],
    { cwd: frontend, env: environment, stdio: 'inherit' })
  const code = await new Promise((done, fail) => { playwright.once('exit', done); playwright.once('error', fail) })
  process.exitCode = code ?? 1
} catch (error) {
  console.error(error instanceof Error ? error.message : '隔离测试失败。')
  process.exitCode = 1
} finally {
  if (control) await new Promise(done => control.close(done))
  await stop(playwright)
  await stop(frontendProcess)
  await stop(backend)
  if (fixturesRoot) rmSync(fixturesRoot, { recursive: true })
  if (storageRoot) rmSync(storageRoot, { recursive: true }) // Only our newly created test directory.
  if (created) docker(['rm', '--force', '--volumes', name], { stdio: 'ignore' })
  console.log('仅清理本次创建的临时容器及其匿名卷，现有数据卷保持不变。')
}
