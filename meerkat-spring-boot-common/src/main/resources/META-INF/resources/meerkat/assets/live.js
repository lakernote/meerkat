import { authHeaders, loginRedirected, SESSION_EXPIRED_MESSAGE } from './common.js?v=20261111.6'
import { $, api, formatBytes, formatMs, initializeEmbeddedAuth, kindName, node, request, showNotice } from './metrics-common.js?v=20261111.6'

const DENIED = t('没有查看实时诊断的权限，请登录或检查 ObservabilityAccessPolicy')
const SOURCES = { HTTP: 'HTTP', JOB: t('定时任务'), KAFKA_CONSUMER: t('Kafka 消费') }
const REFRESH_MS = 5000
const expandedRequests = new Set()
const expandedThreads = new Set()
let requests = null
let threadReport = null
let tab = 'requests'
let timer = null
let loading = false
let refreshPending = false



function cpuText(ms) {
  if (ms == null || ms < 0) return '—'
  if (ms < 1) return '< 1 ms'
  return ms >= 1000 ? formatMs(ms) : `${Math.round(ms)} ms`
}

function shortLock(name) {
  return name ? name.replace(/^(?:[a-z_$][\w$]*\.)+/, '') : ''
}

// Table cells do not honour max-width with nowrap text; an inner block element clips long values instead.
function clipped(className, text, title = text) {
  const cell = node('td', className)
  const inner = node('span', 'clip', text)
  if (title) inner.title = title
  cell.append(inner)
  return cell
}

function stateBadge(state) {
  return node('span', `live-state ${state || ''}`, state || t('异步'))
}

function stackBlock(lines) {
  return node('pre', 'live-stack', lines && lines.length ? lines.join('\n') : t('没有可用的堆栈'))
}

function fact(label, value) {
  const item = node('span')
  item.append(t('{label}：', { label }), node('b', '', value))
  return item
}

// ---- running requests ----

async function loadRequests() {
  requests = await request('live/requests', {}, DENIED)
  renderRequests()
}

function renderRequests() {
  const rows = $('request-rows')
  rows.replaceChildren()
  if (!requests) return
  const min = Number($('min-elapsed').value)
  const items = requests.items.filter(item => item.elapsedMs >= min)
  const hidden = requests.total - requests.items.length
  $('requests-summary').textContent = t('{n} 个进行中', { n: requests.total })
    + (hidden > 0 ? t('，显示最久的 {n} 个', { n: requests.items.length }) : '')
    + ` · ${new Date(requests.capturedAt).toLocaleTimeString(MeerkatI18n.locale)}`
  $('requests-empty').hidden = items.length > 0
  for (const item of items) {
    const key = `${item.traceId}|${item.threadId}|${item.startedAt}`
    const row = node('tr')
    row.tabIndex = 0
    const entry = node('td')
    entry.append(node('span', 'live-kind', SOURCES[item.source] || item.source), item.name)
    const elapsed = node('td', `numeric ${item.elapsedMs >= 30000 ? 'bad' : item.elapsedMs >= 5000 ? 'warn' : ''}`,
      formatMs(item.elapsedMs))
    const step = item.currentStep
    const stepCell = clipped('mono', step ? `[${kindName(step.kind)}] ${step.detail || step.name} · ${formatMs(step.elapsedMs)}` : '—',
      step ? step.detail || step.name : '')
    const thread = node('td')
    thread.append(stateBadge(item.async ? null : item.threadState), ' ', item.async ? t('入口线程已返回') : item.threadName)
    row.append(entry, elapsed, node('td', 'numeric', cpuText(item.cpuTimeMs)), stepCell, thread,
      node('td', 'mono', item.traceId || '—'))
    const expanded = expandedRequests.has(key)
    row.classList.toggle('is-expanded', expanded)
    row.setAttribute('aria-expanded', String(expanded))
    const toggle = () => {
      if (expandedRequests.has(key)) expandedRequests.delete(key)
      else expandedRequests.add(key)
      renderRequests()
    }
    row.addEventListener('click', toggle)
    row.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggle() } })
    rows.append(row)
    if (expanded) rows.append(requestDetail(item))
  }
}

function requestDetail(item) {
  const row = node('tr', 'live-detail')
  const cell = node('td')
  cell.colSpan = 6
  const facts = node('div', 'live-facts')
  facts.append(fact(t('开始'), new Date(item.startedAt).toLocaleTimeString(MeerkatI18n.locale)), fact(t('路径'), item.path || '—'),
    fact(t('已记录节点'), String(item.spanCount)), fact(t('内存分配'), formatBytes(item.allocatedBytes)))
  if (item.lockName) facts.append(fact(t('等待锁'), item.lockName + (item.lockOwnerName ? t('（持有者 {owner}）', { owner: item.lockOwnerName }) : '')))
  cell.append(facts)
  if (item.currentStep) {
    const step = item.currentStep
    cell.append(node('pre', 'live-stack', `${t('当前步骤 [{kind}] {name}，已执行 {elapsed}', {
      kind: kindName(step.kind), name: step.name, elapsed: formatMs(step.elapsedMs) })}\n${step.detail || ''}`))
    cell.lastChild.style.marginBottom = '8px'
  }
  cell.append(item.async ? node('p', 'stats-detail-status', t('请求已转为异步处理，入口线程已返回，没有对应的线程堆栈')) : stackBlock(item.stack))
  row.append(cell)
  return row
}

// ---- threads ----

async function loadThreads() {
  $('thread-cards').replaceChildren(node('p', 'stats-detail-status', t('正在采样 1 秒…')))
  try {
    threadReport = await request('live/threads?sampleMs=1000', {}, DENIED)
  } catch (error) {
    if (!threadReport) $('thread-cards').replaceChildren()
    throw error
  }
  renderThreads()
}

function stackKey(thread) {
  return `${thread.state}\n${thread.stack.filter(line => line.startsWith('at ')).join('\n')}`
}

function filteredThreads() {
  const state = $('thread-state').value
  const query = $('thread-search').value.trim().toLowerCase()
  return threadReport.threads.filter(thread => (!state || thread.state === state)
    && (!query || thread.name.toLowerCase().includes(query) || thread.stack.some(line => line.toLowerCase().includes(query))))
}

function renderThreads() {
  if (!threadReport) return
  const report = threadReport
  const waiting = (report.states.WAITING || 0) + (report.states.TIMED_WAITING || 0)
  // Each card is [label, value, filter state, alert when non-zero].
  const cards = [[t('线程'), report.threadCount, ''], ['RUNNABLE', report.states.RUNNABLE || 0, 'RUNNABLE'],
    ['BLOCKED', report.states.BLOCKED || 0, 'BLOCKED', true], ['WAITING', waiting], [t('死锁'), report.deadlockedThreadIds.length, null, true]]
  // Thread and state cards double as filters; the WAITING card covers both waiting states, so it stays informational.
  $('thread-cards').replaceChildren(...cards.map(([label, value, filter, alert]) => {
    const clickable = filter != null
    const card = node(clickable ? 'button' : 'div', `live-card ${alert && value > 0 ? 'bad' : ''}`)
    card.append(node('small', '', label), node('strong', '', value.toLocaleString()))
    if (clickable) {
      card.type = 'button'
      card.setAttribute('aria-pressed', String($('thread-state').value === filter))
      card.title = filter ? t('只看 {state} 线程', { state: label }) : t('显示全部线程')
      card.addEventListener('click', () => { $('thread-state').value = filter; renderThreads() })
    }
    return card
  }))
  const deadlocks = $('deadlocks')
  deadlocks.hidden = report.deadlockedThreadIds.length === 0
  if (!deadlocks.hidden) {
    const list = node('ul')
    for (const thread of report.threads.filter(item => item.deadlocked)) {
      list.append(node('li', '', t('{name} 等待 {lock}，持有者 {owner}',
        { name: thread.name, lock: thread.lockName || '?', owner: thread.lockOwnerName || '?' })))
    }
    deadlocks.replaceChildren(node('strong', '', t('检测到 {n} 个线程死锁', { n: report.deadlockedThreadIds.length })), list)
  }
  const grouped = $('group-stacks').checked
  const head = node('tr')
  for (const [label, numeric, context] of grouped
    ? [['线程数', true], ['状态', false], ['线程', false], ['栈顶', false]]
    : [['线程', false, 'one'], ['状态', false], ['CPU', true], ['累计 CPU', true], ['锁', false]]) {
    head.append(node('th', numeric ? 'numeric' : '', t(label, null, context)))
  }
  $('thread-head').replaceChildren(head)
  const threads = filteredThreads()
  $('threads-empty').hidden = threads.length > 0
  const rows = $('thread-rows')
  rows.replaceChildren()
  if (grouped) renderGroups(rows, threads)
  else for (const thread of threads) appendThread(rows, thread)
}

function expandable(rows, row, key, columns, detail, rerender = renderThreads) {
  const expanded = expandedThreads.has(key)
  row.tabIndex = 0
  row.classList.toggle('is-expanded', expanded)
  row.setAttribute('aria-expanded', String(expanded))
  const toggle = () => {
    if (expandedThreads.has(key)) expandedThreads.delete(key)
    else expandedThreads.add(key)
    rerender()
  }
  row.addEventListener('click', toggle)
  row.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggle() } })
  rows.append(row)
  if (!expanded) return
  const detailRow = node('tr', 'live-detail')
  const cell = node('td')
  cell.colSpan = columns
  cell.append(...detail())
  detailRow.append(cell)
  rows.append(detailRow)
}

function appendThread(rows, thread) {
  const row = node('tr')
  const name = clipped('mono', thread.name)
  if (thread.daemon) name.firstChild.append(' ', node('span', 'live-kind', 'daemon'))
  const cpu = thread.cpuPercent
  const state = node('td')
  state.append(stateBadge(thread.state))
  row.append(name, state,
    node('td', `numeric ${cpu >= 80 ? 'bad' : cpu >= 30 ? 'warn' : ''}`, cpu < 0 ? '—' : `${cpu.toFixed(1)}%`),
    node('td', 'numeric', cpuText(thread.cpuTimeMs)),
    lockCell(thread))
  expandable(rows, row, `t${thread.id}`, 5, () => [stackBlock(thread.stack)])
}

// A lock with an owner is contention worth reading; ownerless waits are idle pools parked on a condition.
function lockCell(thread) {
  const text = thread.lockName
    ? `${thread.deadlocked ? `${t('死锁')} · ` : ''}${shortLock(thread.lockName)}${thread.lockOwnerName ? ` ← ${thread.lockOwnerName}` : ''}` : ''
  const tone = thread.deadlocked ? 'bad' : thread.lockOwnerName ? 'contended' : 'muted'
  return clipped(`mono ${tone}`, text,
    thread.lockName ? thread.lockName + (thread.lockOwnerName ? t('，持有者 {owner}', { owner: thread.lockOwnerName }) : '') : '')
}

function renderGroups(rows, threads) {
  const groups = new Map()
  for (const thread of threads) {
    const key = stackKey(thread)
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key).push(thread)
  }
  const sorted = [...groups.values()].sort((a, b) => b.length - a.length)
  for (const members of sorted) {
    const first = members[0]
    const row = node('tr')
    const names = members.slice(0, 3).map(thread => thread.name).join(t('、'))
      + (members.length > 3 ? t(' 等 {n} 个', { n: members.length }) : '')
    const top = first.stack.find(line => line.startsWith('at ')) || '—'
    const state = node('td')
    state.append(stateBadge(first.state))
    row.append(node('td', 'numeric', String(members.length)), state, clipped('mono', names),
      clipped('mono', top.replace(/^at /, '')))
    expandable(rows, row, `g${first.id}`, 4, () => [
      node('p', 'live-facts', members.map(thread => thread.name).join(t('、'))), stackBlock(first.stack)])
  }
}

async function downloadDump(event) {
  event.preventDefault()
  try {
    const response = await fetch(new URL('live/threads/dump', api), { credentials: 'same-origin', headers: authHeaders() })
    if (!response.ok) throw new Error(response.status === 401 || response.status === 403 ? DENIED
      : response.status === 429 ? t('线程 Dump 刚刚生成过或正在生成，请 5 秒后再试')
        : t('下载失败（HTTP {status}）', { status: response.status }))
    if (loginRedirected(response)) throw new Error(SESSION_EXPIRED_MESSAGE)
    const name = /filename="?([^";]+)"?/.exec(response.headers.get('Content-Disposition') || '')?.[1] || 'threads.txt'
    const url = URL.createObjectURL(await response.blob())
    const link = node('a')
    link.href = url
    link.download = name
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (error) {
    showNotice(error.message, true)
  }
}

// ---- page ----

async function refresh() {
  if (loading) {
    // A tab switch or manual refresh during a load must not be dropped; run once more afterwards.
    refreshPending = true
    return
  }
  loading = true
  refreshPending = false
  $('refresh').disabled = true
  try {
    if (tab === 'requests') await loadRequests()
    else await loadThreads()
    showNotice('')
  } catch (error) {
    showNotice(error.message.includes('HTTP 429') ? t('另一个线程采样正在进行，请稍后再试') : error.message, true)
  } finally {
    loading = false
    $('refresh').disabled = false
  }
  if (refreshPending) refresh()
}

function schedule() {
  clearInterval(timer)
  timer = null
  if (tab !== 'threads' && $('auto-refresh').checked) {
    timer = setInterval(() => { if (!document.hidden) refresh() }, REFRESH_MS)
  }
}

function selectTab(next) {
  tab = next === 'threads' ? next : 'requests'
  for (const name of ['requests', 'threads']) {
    const active = name === tab
    $(`tab-${name}`).classList.toggle('active', active)
    if (active) $(`tab-${name}`).setAttribute('aria-current', 'page')
    else $(`tab-${name}`).removeAttribute('aria-current')
    $(`${name}-panel`).hidden = !active
  }
  $('refresh').textContent = t(tab === 'threads' ? '重新采样' : '刷新')
  schedule()
  if (tab !== 'threads' || !threadReport) refresh()
}

let searchTimer = null
$('refresh').addEventListener('click', refresh)
$('min-elapsed').addEventListener('change', renderRequests)
$('auto-refresh').addEventListener('change', schedule)
$('thread-state').addEventListener('change', renderThreads)
$('group-stacks').addEventListener('change', renderThreads)
$('thread-search').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(renderThreads, 200) })
$('dump').addEventListener('click', downloadDump)
window.addEventListener('hashchange', () => selectTab(location.hash.slice(1)))
initializeEmbeddedAuth().then(() => selectTab(location.hash.slice(1)))
