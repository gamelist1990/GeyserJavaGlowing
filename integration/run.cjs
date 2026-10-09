// Isolated offline integration test: all processes share the same local network.
// Run after ./gradlew build: cd integration; npm ci --ignore-scripts; node run.cjs
const fs = require('fs')
const path = require('path')
const { spawn, spawnSync } = require('child_process')
const { createHash } = require('crypto')
// jsp-raknet 2.1.3 still advertises RakNet 10; modern Geyser requires 11.
// This test-only transport adaptation does not change Minecraft packet contents.
const transport = require.resolve('jsp-raknet/js/Client.js')
const originalTransport = fs.readFileSync(transport, 'utf8')
if (originalTransport.includes('const RAKNET_PROTOCOL = 10;')) {
  fs.writeFileSync(transport, originalTransport.replace('const RAKNET_PROTOCOL = 10;', 'const RAKNET_PROTOCOL = 11;'))
}
const bedrock = require('bedrock-protocol')
const nbt = require('prismarine-nbt')
const { Rcon } = require('rcon-client')
const assert = require('node:assert/strict')
const root = path.resolve(__dirname, '..')
const paperDir = process.env.TEST_PAPER_DIR || path.resolve(root, '../paper')
const geyserDir = process.env.TEST_GEYSER_DIR || path.resolve(root, '../geyser')
const java25 = process.env.TEST_JAVA25 || path.resolve(root, '../tools/java25/bin/java')
const java21 = process.env.TEST_JAVA21 || path.resolve(root, '../tools/jdk-21.0.12.1+1/bin/java')
const resultDir = path.join(__dirname, 'results')
fs.mkdirSync(resultDir, { recursive: true })
const packUuid = 'bbe692bf-dc53-438a-afd8-acadbc5697ed'
const encode = value => JSON.stringify(value, (_, v) => typeof v === 'bigint' ? v.toString() : v)
const summary = { started: new Date().toISOString(), library: require('bedrock-protocol/package.json').version, clients: [], commands: [], checks: [], errors: [] }
const clients = []
const processes = []
let rcon
let stage = 'startup'
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function waitFor(fn, label, timeout = 15000) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    const failed = clients.find(c => c.errors.length)
    if (failed) throw new Error(`${failed.name}: ${failed.errors.join('; ')}`)
    if (fn()) return
    await pause(100)
  }
  throw new Error(`Timeout: ${label}`)
}
function launch(name, java, args, cwd, ready) {
  const file = fs.createWriteStream(path.join(resultDir, `${name}-console.log`))
  const process = spawn(java, args, { cwd, stdio: ['pipe', 'pipe', 'pipe'] })
  processes.push({ name, process })
  return new Promise((resolve, reject) => {
    let text = ''
    let resolved = false
    const deadline = setTimeout(() => reject(new Error(`${name} startup timed out`)), 70000)
    const receive = chunk => {
      file.write(chunk); text = (text + chunk.toString()).slice(-100000)
      if (!resolved && ready.test(text)) { resolved = true; clearTimeout(deadline); console.log(`${name}: ready`); resolve(process) }
    }
    process.stdout.on('data', receive); process.stderr.on('data', receive)
    process.once('error', reject)
    process.once('exit', code => { clearTimeout(deadline); file.end(); if (!ready.test(text)) reject(new Error(`${name} exited ${code}`)) })
  })
}
function connect(name, version = '1.26.30') {
  const session = clients.filter(c => c.name === name).length + 1
  const state = { name, session, version, players: new Map(), properties: new Map(), indexes: {}, packets: [], counts: {}, errors: [], packs: [], downloads: new Map(), spawned: false }
  const file = fs.createWriteStream(path.join(resultDir, `${name}-session${session}-packets.jsonl`))
  const client = bedrock.createClient({ host: '127.0.0.1', port: 19132, offline: true,
    username: name, version, skipPing: true, raknetBackend: 'jsp-raknet', useRaknetWorkers: false,
    deviceOS: 7, viewDistance: 3, conLog: text => console.log(`${name}: ${text}`) })
  state.client = client; state.file = file; clients.push(state)
  // Offline profiles default to XUID 0. Geyser rejects concurrent duplicate IDs.
  // Assign stable, distinct synthetic IDs only in this offline test fixture.
  const setTestIdentity = profile => { profile.xuid = `90000000000000${['GlowA','GlowB','GlowC'].indexOf(name) + 1}` }
  if (client.profile) setTestIdentity(client.profile)
  client.on('session', setTestIdentity)
  // Replace createClient's automatic pack skip with an actual chunk download.
  client.removeAllListeners('resource_packs_info')
  const packResponse = (status, ids = []) => client.write('resource_pack_client_response', {
    response_status: status, response_status_name: status === 'completed' ? 'resourcepackstackfinished' : status,
    resourcepackids: ids
  })
  client.once('resource_packs_info', packet => {
    // bedrock-protocol's binary UUID decoder keeps the two little-endian longs
    // reversed. Geyser supplies the canonical UUID as the content identity too.
    packResponse('send_packs', packet.texture_packs.map(p => `${p.content_identity}_${p.version}`))
    client.queue('client_cache_status', { enabled: false })
  })
  client.on('resource_pack_data_info', packet => {
    state.downloads.set(packet.pack_id, { info: packet, chunks: new Map() })
    for (let i = 0; i < packet.chunk_count; i++) client.queue('resource_pack_chunk_request', { pack_id: packet.pack_id, chunk_index: i })
  })
  client.on('resource_pack_chunk_data', packet => {
    const download = state.downloads.get(packet.pack_id)
    download.chunks.set(packet.chunk_index, packet.payload)
    if (download.chunks.size === download.info.chunk_count) {
      const bytes = Buffer.concat([...download.chunks.entries()].sort((a,b)=>a[0]-b[0]).map(([,b])=>b))
      const digest = createHash('sha256').update(bytes).digest()
      assert.deepEqual(digest, download.info.hash, 'downloaded pack matches advertised SHA-256')
      download.sha256 = digest.toString('hex'); download.bytes = bytes.length; download.complete = true
      const advertised = state.packs.find(p => packet.pack_id.startsWith(p.content_identity))
      if (advertised?.content_identity === packUuid) {
        assert.deepEqual(bytes, fs.readFileSync(path.join(root, 'build/generated/resource-pack/GeyserJavaGlowing.mcpack')))
        state.outlineDownloaded = true
      }
      if (state.downloads.size === state.packs.length && [...state.downloads.values()].every(d=>d.complete)) packResponse('have_all_packs')
    }
  })
  client.once('resource_pack_stack', () => {
    packResponse('completed')
    client.queue('request_chunk_radius', { chunk_radius: 3 })
  })
  client.on('packet', packet => {
    const { name: type, params } = packet.data
    state.counts[type] = (state.counts[type] || 0) + 1
    if (['sync_entity_property','set_entity_data','add_player','remove_entity','resource_packs_info','resource_pack_stack','resource_pack_data_info','start_game','disconnect','change_dimension','play_status'].includes(type)) {
      const entry = { time: new Date().toISOString(), stage, type, params }
      state.packets.push(entry); file.write(encode(entry) + '\n')
    }
    if (type === 'sync_entity_property') {
      const data = nbt.simplify(params.nbt)
      if (data.type === 'minecraft:player') {
        state.definitions = data
        data.properties.forEach((p, i) => { state.indexes[p.name] = i })
        console.log(`${name}: player properties ${encode(state.indexes)}`)
      }
    }
    if (type === 'resource_packs_info') state.packs = params.texture_packs
    if (type === 'start_game') {
      state.self = params.runtime_entity_id.toString()
      state.players.set(name, state.self)
    }
    if (type === 'add_player') state.players.set(params.username, params.runtime_id.toString())
    if (type === 'set_entity_data') {
      const id = params.runtime_entity_id.toString()
      const values = state.properties.get(id) || {}
      for (const p of [...params.properties.ints, ...params.properties.floats]) values[p.index] = p.value
      state.properties.set(id, values)
    }
  })
  client.on('spawn', () => { state.spawned = true; console.log(`${name}: spawned`) })
  client.on('error', e => { state.errors.push(e.message); console.log(`${name}: ERROR ${e.message}`) })
  client.on('kick', packet => { state.errors.push('Kicked: ' + encode(packet)); console.log(`${name}: kicked ${encode(packet)}`) })
  client.on('close', () => { state.closed = true })
  return state
}
async function cmd(command) {
  const response = await rcon.send(command)
  summary.commands.push({time:new Date().toISOString(),command,response})
  console.log(`> ${command}: ${response.replace(/\n/g, ' ')}`)
  return response
}
function prop(observer, subject, name) {
  return observer.properties.get(observer.players.get(subject))?.[observer.indexes[name]]
}
async function expectGlow(label, subject, mode, rgb, observers = clients) {
  stage = label
  await waitFor(() => observers.every(observer => prop(observer, subject, 'glow:color') === mode &&
    (!rgb || ['r','g','b'].every((name, i) => Math.abs(prop(observer, subject, `glow:${name}`) - rgb[i] / 255) < 0.0001))), label)
  const values = observers.map(observer => ({ observer: observer.name, subject, runtimeId: observer.players.get(subject),
    mode: prop(observer, subject, 'glow:color'), rgb: ['r','g','b'].map(n => prop(observer, subject, `glow:${n}`)) }))
  summary.checks.push({ label, pass: true, values })
  console.log(`PASS: ${label}`)
}
async function main() {
  // These fixture directories must be local-only offline servers.
  const props = fs.readFileSync(path.join(paperDir, 'server.properties'), 'utf8')
  assert.match(props, /^server-ip=127\.0\.0\.1$/m)
  assert.match(props, /^online-mode=false$/m)
  const geyserConfig = fs.readFileSync(path.join(geyserDir, 'config.yml'), 'utf8')
  assert.match(geyserConfig, /address: 127\.0\.0\.1/)
  assert.match(geyserConfig, /auth-type: offline/)
  fs.mkdirSync(path.join(geyserDir, 'extensions'), { recursive: true })
  fs.copyFileSync(path.join(root, 'build/libs/GeyserJavaGlowing-0.2.0.jar'), path.join(geyserDir, 'extensions/GeyserJavaGlowing-0.2.0.jar'))
  summary.binaries = Object.fromEntries([
    ['extension',path.join(root,'build/libs/GeyserJavaGlowing-0.2.0.jar')],
    ['paper',path.join(paperDir,'paper.jar')],
    ['geyser',path.join(geyserDir,'Geyser-Standalone.jar')]
  ].map(([name,file])=>[name,{sha256:createHash('sha256').update(fs.readFileSync(file)).digest('hex')}]))
  const classes = path.join(__dirname, 'classes')
  fs.mkdirSync(classes, { recursive: true })
  const compilation = spawnSync(path.join(path.dirname(java21),'javac'), ['-cp',path.join(geyserDir,'Geyser-Standalone.jar'),'-d',classes,path.join(__dirname,'OfflineGeyserBootstrap.java')], {encoding:'utf8'})
  if (compilation.status !== 0) throw new Error(`Bootstrap compilation failed: ${compilation.stderr || compilation.error}`)
  await launch('paper', java25, ['-Xms256m','-Xmx1024m','-Dterminal.jline=false','-Dterminal.ansi=false','-jar','paper.jar','--nogui'], paperDir, /Done \([0-9.]+s\)!/)
  rcon = await Rcon.connect({ host: '127.0.0.1', port: 25575, password: props.match(/^rcon.password=(.*)$/m)[1] })
  await launch('geyser', java21, ['-Xmx768m','-Dterminal.jline=false','-Djava.awt.headless=true','-DdisableNativeEventLoop=true','-DGeyser.RakSendCookie=false','-cp',path.join(__dirname,'classes')+path.delimiter+path.join(geyserDir,'Geyser-Standalone.jar'),'OfflineGeyserBootstrap',path.join(__dirname,'metadata')], geyserDir, /Installed glowing hooks for 6 Java packet translators/)
  stage = 'three simultaneous logins'
  connect('GlowA'); connect('GlowB', '1.26.51'); connect('GlowC')
  await waitFor(() => clients.every(c => c.spawned), 'three clients spawn', 65000)
  summary.checks.push({label:'three simultaneous offline logins',pass:true})
  for (const c of clients) {
    assert.ok(c.indexes['glow:color'] >= 0, `${c.name} received property definitions`)
    assert.ok(c.packs.some(p => encode(p).includes(packUuid)), `${c.name} received resource pack advertisement`)
    assert.ok(c.outlineDownloaded, `${c.name} downloaded the exact bundled resource pack`)
  }
  summary.checks.push({label:'all clients received property definitions and downloaded matching resource pack bytes',pass:true})
  await cmd('tp @a 0 65 0')
  await waitFor(() => clients.every(c => ['GlowA','GlowB','GlowC'].every(n => c.players.has(n))), 'all clients see all players', 30000)
  await cmd('effect clear @a')
  await cmd('team remove glowRed'); await cmd('team add glowRed'); await cmd('team modify glowRed color red'); await cmd('team join glowRed GlowA')
  stage = 'white GlowB enabled'
  await cmd('effect give GlowB minecraft:glowing 120 0 true')
  await expectGlow('white GlowB enabled for all observers', 'GlowB', 2, [255,255,255])
  stage = 'red GlowA enabled'
  await cmd('effect give GlowA minecraft:glowing 120 0 true')
  await expectGlow('red GlowA enabled for all observers', 'GlowA', 2, [255,85,85])
  await expectGlow('unaffected GlowC stays disabled', 'GlowC', 0)
  stage = 'team color changed to blue'
  await cmd('team modify glowRed color blue')
  await expectGlow('live team color changes to blue', 'GlowA', 2, [85,85,255])
  stage = 'GlowB effect cleared'
  await cmd('effect clear GlowB minecraft:glowing')
  await expectGlow('GlowB disabled while GlowA remains enabled', 'GlowB', 0)
  await expectGlow('GlowA stays blue', 'GlowA', 2, [85,85,255])
  stage = 'GlowA leaves team'
  await cmd('team leave GlowA')
  await expectGlow('team removal restores white', 'GlowA', 2, [255,255,255])
  stage = 'late viewer reconnect'
  clients[2].client.close()
  await waitFor(() => clients[2].closed, 'GlowC disconnect')
  await pause(1200)
  const late = connect('GlowC')
  await waitFor(() => late.spawned, 'GlowC reconnect', 50000)
  await cmd('tp GlowC 0 65 0')
  await expectGlow('late viewer receives existing glow state', 'GlowA', 2, [255,255,255], [late])
  stage = 'rapid toggles'
  const active = clients.filter(c => !c.closed)
  for (let i=0; i<4; i++) {
    await cmd('effect clear GlowA minecraft:glowing')
    await expectGlow(`rapid toggle ${i+1} off`, 'GlowA', 0, null, active)
    await cmd('effect give GlowA minecraft:glowing 120 0 true')
    await expectGlow(`rapid toggle ${i+1} on`, 'GlowA', 2, [255,255,255], active)
  }
  await cmd('effect clear @a')
  await expectGlow('final cleanup clears all outlines', 'GlowA', 0, null, active)
  for (const c of clients) assert.equal(c.errors.length, 0, `${c.name} protocol errors: ${c.errors.join('; ')}`)
  summary.checks.push({label:'no client packet decoding errors',pass:true})
  summary.pass = true
}
async function cleanup() {
  for (const state of clients) {
    state.client.close(); state.file.end()
    summary.clients.push({name:state.name,session:state.session,version:state.version,spawned:state.spawned,counts:state.counts,indexes:state.indexes,outlineDownloaded:state.outlineDownloaded,downloads:[...state.downloads.entries()].map(([id,d])=>({id,bytes:d.bytes,sha256:d.sha256,complete:d.complete})),errors:state.errors})
  }
  for (const {name,process: child} of processes.reverse()) {
    if (child.exitCode == null) child.stdin.write(name === 'geyser' ? 'geyser stop\n' : 'stop\n')
  }
  rcon?.end()
  await Promise.all(processes.map(({process:child}) => new Promise(resolve => {
    if (child.exitCode != null) return resolve()
    const deadline = setTimeout(() => { child.kill('SIGTERM'); resolve() }, 20000)
    child.once('exit', () => { clearTimeout(deadline); resolve() })
  })))
  const paperLog = path.join(paperDir, 'logs/latest.log')
  if (fs.existsSync(paperLog)) fs.copyFileSync(paperLog, path.join(resultDir, 'paper-server.log'))
  summary.finished = new Date().toISOString()
  fs.writeFileSync(path.join(resultDir, 'summary.json'), JSON.stringify(summary,null,2)+'\n')
  console.log(`RESULT: ${summary.pass ? 'PASS' : 'FAIL'} (${summary.checks.length} checks)`)
}
main().catch(error=>{summary.pass=false;summary.errors.push(error.stack);console.error(error.stack);process.exitCode=1}).finally(cleanup)
