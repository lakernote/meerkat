import {
  $, DISABLED_MESSAGE, categoryNames, countsSince, formatBytes, formatMs, initializeEmbeddedAuth, lineChart, node, request,
  showNotice
} from './metrics-common.js?v=20261106.13'

const REFRESH_MS = 10000
// Day ranges change slowly and read history files, so poll them less often.
const LONG_RANGE_REFRESH_MS = 60000
const WINDOWS = new Set(['1h', 'today', '7d', '30d'])
const TREND_PERIOD = { '1h': '1h', today: '24h', '7d': '7d', '30d': '30d' }
const NOT_COLLECTING_MESSAGE = t('调用统计来自 Trace 采集点，当前 meerkat.trace.enabled=false，不会记录新的调用')
// Matches OperationStats.OVERFLOW_NAME, the one operation name the backend sends in Chinese.
const OVERFLOW_NAME = '（其他：超出统计数量上限）'
const columns = [
  { key: 'name', label: t('名称') },
  { key: 'count', label: t('次数'), numeric: true },
  { key: 'errors', label: t('错误'), numeric: true },
  { key: 'meanMs', label: t('平均'), numeric: true },
  { key: 'p95Ms', label: 'P95', numeric: true, title: t('由直方图估算，误差约 ±20%') },
  { key: 'maxMs', label: t('最大'), numeric: true },
  { key: 'totalMs', label: t('总耗时'), numeric: true },
  { key: 'share', label: t('耗时占比'), title: t('该操作总耗时占本类别总耗时的比例') }
]
// Entries run on one thread from start to finish, so their CPU time and allocation are measured per call.
const ENTRY_CATEGORIES = new Set(['HTTP', 'JOB', 'KAFKA_CONSUMER'])
const resourceColumns = [
  { key: 'meanCpuMs', label: t('平均 CPU'), numeric: true, title: t('入口线程每次调用消耗的 CPU 时间；异步请求不统计') },
  { key: 'meanAllocatedBytes', label: t('平均内存分配'), numeric: true, title: t('入口线程每次调用分配的堆内存（不是占用量）；异步请求不统计') }
]

function visibleColumns() {
  if (!ENTRY_CATEGORIES.has(activeCategory)) return columns
  return [...columns.slice(0, -1), ...resourceColumns, columns[columns.length - 1]]
}
let snapshot = null
let activeCategory = ''
let sortKey = 'totalMs'
let sortDescending = true
let timer
let searchTimer
// The expanded row survives re-renders; its chart is reloaded on every refresh.
let expanded = null
let detail = null
let detailCharts = []

const displayName = name => name === OVERFLOW_NAME ? t(name) : name

function formatPercent(value) {
  if (!Number.isFinite(value) || value <= 0) return '0%'
  return value < 0.1 ? '<0.1%' : `${value.toFixed(value < 10 ? 1 : 0)}%`
}

function currentCategory() {
  return snapshot?.categories.find(item => item.category === activeCategory)
}

function renderCategories() {
  const nav = $('categories')
  nav.replaceChildren()
  for (const category of snapshot.categories) {
    const button = node('button', category.category === activeCategory ? 'active' : '')
    button.type = 'button'
    button.append(categoryNames[category.category] || category.category,
      node('small', '', Number(category.count).toLocaleString()))
    button.setAttribute('aria-pressed', String(category.category === activeCategory))
    button.addEventListener('click', () => { activeCategory = category.category; collapse(); render() })
    nav.append(button)
  }
}

function renderHead() {
  const head = $('stats-head')
  head.replaceChildren()
  for (const column of visibleColumns()) {
    const th = node('th', column.numeric ? 'numeric' : '')
    th.scope = 'col'
    if (column.title) th.title = column.title
    if (column.key === sortKey) th.setAttribute('aria-sort', sortDescending ? 'descending' : 'ascending')
    const button = node('button', '', column.label)
    button.type = 'button'
    button.addEventListener('click', () => {
      if (sortKey === column.key) sortDescending = !sortDescending
      else { sortKey = column.key; sortDescending = column.key !== 'name' }
      render()
    })
    th.append(button)
    head.append(th)
  }
}

function sortedRows(category) {
  const query = $('search').value.trim().toLocaleLowerCase()
  const total = Number(category.totalMs || 0)
  const rows = category.rows
    .filter(row => !query || String(displayName(row.name)).toLocaleLowerCase().includes(query))
    .map(row => ({ ...row, share: total > 0 ? row.totalMs / total * 100 : 0 }))
  const direction = sortDescending ? -1 : 1
  return rows.sort((left, right) => {
    const a = sortKey === 'errors' ? left.errors / left.count : left[sortKey]
    const b = sortKey === 'errors' ? right.errors / right.count : right[sortKey]
    if (sortKey === 'name') return direction * String(a).localeCompare(String(b))
    return direction * (Number(a) - Number(b)) || String(left.name).localeCompare(String(right.name))
  })
}

function isExpanded(row) {
  return expanded && expanded.category === activeCategory && expanded.name === row.name
}

function collapse() {
  expanded = null
  for (const chart of detailCharts) chart.destroy()
  detailCharts = []
  detail = null
}

function toggle(row) {
  if (isExpanded(row)) collapse()
  else {
    collapse()
    expanded = { category: activeCategory, name: row.name }
  }
  render()
  if (expanded) loadTrend()
}

function detailRow() {
  if (!detail) {
    detail = document.createElement('tr')
    detail.className = 'stats-detail'
    const cell = node('td')
    cell.colSpan = visibleColumns().length
    cell.append(node('div', 'stats-detail-body'))
    detail.append(cell)
  }
  return detail
}

// Trace search matches entry names, SQL and span tags, so the operation name finds the recorded slow and failed calls.
function traceLink(target) {
  const actions = node('div', 'stats-detail-actions')
  const link = node('a', '', t('查看相关的慢 / 错误 Trace →'))
  // Remote rows are named "GET host /path"; trace spans only carry the path.
  const remotePath = target.category === 'REMOTE' ? /^[A-Z]+ \S+ (.+)$/.exec(target.name)?.[1] : null
  link.href = `../?q=${encodeURIComponent(remotePath || target.name)}`
  actions.append(link)
  return actions
}

async function loadTrend() {
  if (!expanded) return
  const target = expanded
  const period = TREND_PERIOD[$('window').value] || '1h'
  const body = detailRow().querySelector('.stats-detail-body')
  if (!detailCharts.length) body.replaceChildren(node('p', 'stats-detail-status', t('读取趋势中…')))
  try {
    const trend = await request(`metrics/operations/trend?category=${encodeURIComponent(target.category)}`
      + `&name=${encodeURIComponent(target.name)}&period=${period}`)
    if (expanded !== target) return
    for (const chart of detailCharts) chart.destroy()
    const timestamps = trend.points.map(point => point.timestamp)
    const minute = trend.period === 'HOUR'
    detailCharts = [
      lineChart({ title: t('调用次数'), unit: 'count', timestamps, stepMillis: trend.stepMillis, height: 180, series: [
        { label: t('次数'), values: countsSince(trend, point => point.count) },
        ...(trend.points.some(point => point.errors > 0)
          ? [{ label: t('错误'), values: countsSince(trend, point => point.errors) }] : [])] }),
      lineChart({ title: t('耗时'), unit: 'ms', timestamps, stepMillis: trend.stepMillis, height: 180,
        hint: t(minute ? '按分钟统计时不计算 P95' : 'P95 由直方图估算，误差约 ±20%'), series: [
          { label: t('平均'), values: trend.points.map(point => point.meanMs) },
          ...(minute ? [] : [{ label: 'P95', values: trend.points.map(point => point.p95Ms) }]),
          { label: t('最大'), values: trend.points.map(point => point.maxMs) }] })
    ]
    const grid = node('div', 'stats-detail-grid')
    grid.append(...detailCharts.map(chart => chart.card))
    body.replaceChildren(traceLink(target), grid)
  } catch (error) {
    if (expanded === target) body.replaceChildren(node('p', 'stats-detail-status', error.message))
  }
}

function renderRows(category) {
  const tbody = $('stats-rows')
  // Detach the detail row first so replaceChildren keeps its charts alive.
  if (detail) detail.remove()
  tbody.replaceChildren()
  const rows = sortedRows(category)
  for (const row of rows) {
    const tr = document.createElement('tr')
    const open = isExpanded(row)
    const name = node('td', 'name', displayName(row.name))
    name.title = displayName(row.name)
    const errorRate = row.count > 0 ? row.errors / row.count * 100 : 0
    const errors = node('td', `numeric${row.errors > 0 ? ' bad' : ''}`,
      row.errors > 0 ? t('{count}（{percent}）', { count: Number(row.errors).toLocaleString(), percent: formatPercent(errorRate) }) : '0')
    const share = node('td')
    const shareBox = node('div', 'stats-share')
    const bar = node('div', 'stats-share-bar')
    const fill = node('i')
    fill.style.width = `${Math.min(100, Math.max(0, row.share)).toFixed(1)}%`
    bar.append(fill)
    shareBox.append(bar, node('span', '', formatPercent(row.share)))
    share.append(shareBox)
    tr.append(name, node('td', 'numeric strong', Number(row.count).toLocaleString()), errors,
      node('td', 'numeric', formatMs(row.meanMs)), node('td', 'numeric', `≈ ${formatMs(row.p95Ms)}`),
      node('td', 'numeric', formatMs(row.maxMs)), node('td', 'numeric strong', formatMs(row.totalMs)),
      ...(ENTRY_CATEGORIES.has(activeCategory) ? [
        node('td', 'numeric', row.meanCpuMs == null ? '—' : formatMs(row.meanCpuMs)),
        node('td', 'numeric', formatBytes(row.meanAllocatedBytes))] : []),
      share)
    tr.tabIndex = 0
    tr.setAttribute('aria-expanded', String(open))
    tr.title = t(open ? '收起趋势' : '查看趋势')
    if (open) tr.classList.add('is-expanded')
    tr.addEventListener('click', () => toggle(row))
    tr.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); toggle(row) }
    })
    tbody.append(tr)
    if (open) tbody.append(detailRow())
  }
  const empty = $('empty')
  empty.hidden = rows.length > 0
  empty.textContent = category.rows.length
    ? t('没有名称匹配的操作')
    : t('所选时间范围内还没有{category}调用', { category: categoryNames[category.category] || '' })
}

function renderSummary() {
  const since = new Date(snapshot.since)
  const sinceText = Number.isNaN(since.getTime()) ? '' : t('自 {time} 起', { time: since.toLocaleString(MeerkatI18n.locale) })
  const limit = snapshot.keys >= snapshot.maxKeys ? t('，已达上限，新操作计入“其他”') : ''
  const memoryOnly = snapshot.window !== 'HOUR' && !snapshot.persistent
    ? t(' · 存储目录不可用，只含内存中最近约 1 小时') : ''
  $('summary').textContent = `${sinceText} · ${t('内存中 {keys} 个操作（上限 {max}{limit}）', {
    keys: snapshot.keys.toLocaleString(), max: snapshot.maxKeys.toLocaleString(), limit })}${memoryOnly}`
}

function render() {
  if (!snapshot) return
  if (!currentCategory()) activeCategory = snapshot.categories[0]?.category || ''
  renderCategories()
  renderHead()
  renderSummary()
  const category = currentCategory()
  if (category) renderRows(category)
}

async function refresh() {
  try {
    const next = await request(`metrics/operations?window=${encodeURIComponent($('window').value)}`)
    if (!activeCategory) activeCategory = next.categories.find(item => item.count > 0)?.category || 'HTTP'
    snapshot = next
    $('instance').classList.add('connected')
    $('instance').lastChild.textContent = t('已连接')
    if (next.collecting) $('notice').hidden = true
    else showNotice(NOT_COLLECTING_MESSAGE, true)
    render()
    loadTrend()
  } catch (error) {
    $('instance').classList.remove('connected')
    $('instance').lastChild.textContent = t('连接失败')
    showNotice(error.message === DISABLED_MESSAGE ? error.message : t('{message}。请检查应用权限和服务状态。', { message: error.message }))
    if (error.message === DISABLED_MESSAGE) {
      window.clearInterval(timer)
      $('instance').lastChild.textContent = t('未启用')
    }
  }
}

async function loadPermissions() {
  try {
    const permissions = await request('/permissions')
    $('reset-stats').hidden = !permissions.canClear
  } catch {
    $('reset-stats').hidden = true
  }
}

function schedule() {
  window.clearInterval(timer)
  timer = window.setInterval(refresh, $('window').value === '1h' || $('window').value === 'today'
    ? REFRESH_MS : LONG_RANGE_REFRESH_MS)
}

function bind() {
  const requested = new URLSearchParams(window.location.search).get('window')
  if (WINDOWS.has(requested)) $('window').value = requested
  $('window').addEventListener('change', () => {
    const url = new URL(window.location.href)
    url.searchParams.set('window', $('window').value)
    window.history.replaceState(null, '', url)
    for (const chart of detailCharts) chart.destroy()
    detailCharts = []
    schedule()
    refresh()
  })
  $('search').addEventListener('input', () => {
    window.clearTimeout(searchTimer)
    searchTimer = window.setTimeout(render, 140)
  })
  $('refresh').addEventListener('click', refresh)
  $('reset-stats').addEventListener('click', () => $('reset-dialog').showModal())
  $('cancel-reset').addEventListener('click', () => $('reset-dialog').close())
  $('confirm-reset').addEventListener('click', async () => {
    $('confirm-reset').disabled = true
    try {
      await request('/metrics/operations/reset', { method: 'POST' },
        t('没有重置统计的权限，请检查 ObservabilityAccessPolicy#canClear 或 CSRF 配置'))
      $('reset-dialog').close()
      collapse()
      await refresh()
    } catch (error) {
      showNotice(error.message)
    } finally { $('confirm-reset').disabled = false }
  })
}

bind()
initializeEmbeddedAuth().then(() => Promise.all([loadPermissions(), refresh()]))
schedule()
window.addEventListener('beforeunload', () => window.clearInterval(timer), { once: true })
