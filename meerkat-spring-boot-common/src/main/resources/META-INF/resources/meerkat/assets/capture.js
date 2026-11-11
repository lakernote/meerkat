import { $, initializeEmbeddedAuth, kindName, node, request, showNotice } from './common.js?v=20261106.11'

const DENIED = t('没有流量抓取的权限，请登录或检查 ObservabilityAccessPolicy.canCapture')
const DISABLED = t('流量抓取不可用：需要开启 meerkat.trace.enabled')
// Order and labels of the values recorded on each node; unknown keys follow in their own order.
const FIELD_LABELS = {
  request: '请求', 'request.params': '表单参数', 'request.body': '请求体', sql: 'SQL', params: 'SQL 参数',
  command: '命令', result: '结果', rows: '查询结果', response: '响应', 'response.body': '响应体', error: '错误'
}
const REFRESH_MS = 5000
let status = null
let selectedSession = ''
let selectedRecord = ''
let timer = null
let recordsRequest = 0

function connected(ok) {
  $('instance').classList.toggle('connected', ok)
  $('instance').lastChild.textContent = t(ok ? '已连接' : '连接失败')
}

function formatMs(ms) {
  return ms >= 1000 ? `${(ms / 1000).toFixed(ms >= 10000 ? 1 : 2)} s` : `${ms} ms`
}

function time(value) {
  return value ? new Date(value).toLocaleTimeString(MeerkatI18n.locale) : '—'
}

function ruleText(rule) {
  let text = `${rule.method || t('全部')} ${rule.pathPattern}`
  if (rule.headerName) text += ` · ${rule.headerName}${rule.headerValue ? '=' + rule.headerValue : ''}`
  return text
}

async function refresh() {
  try {
    status = await request('capture/status', {}, DENIED)
    connected(true)
  } catch (error) {
    connected(false)
    showNotice(error.message, true)
    return
  }
  if (!status.enabled) return blocked(DISABLED)
  if (!status.canCapture) return blocked(DENIED)
  $('capture-form').hidden = false
  $('clear').hidden = false
  const duration = $('rule-duration')
  duration.querySelectorAll('option').forEach(option => {
    option.disabled = Number(option.value) > status.maxDurationSeconds
  })
  if (duration.selectedOptions[0]?.disabled) {
    const allowed = [...duration.options].filter(option => !option.disabled)
    if (allowed.length) duration.value = allowed[allowed.length - 1].value
  }
  renderSessions()
  await loadRecords()
  schedule()
}

function blocked(message) {
  showNotice(message, true)
  $('capture-form').hidden = true
  $('clear').hidden = true
  $('session-rows').replaceChildren()
  $('record-rows').replaceChildren()
  $('sessions-empty').hidden = false
  $('records-empty').hidden = false
}

function schedule() {
  clearTimeout(timer)
  if (status?.sessions?.some(session => session.active)) timer = setTimeout(refresh, REFRESH_MS)
}

function renderSessions() {
  const rows = $('session-rows')
  rows.replaceChildren()
  $('sessions-empty').hidden = status.sessions.length > 0
  for (const session of status.sessions) {
    const row = node('tr')
    row.tabIndex = 0
    row.classList.toggle('is-selected', session.id === selectedSession)
    const state = session.active
      ? t('进行中，{time} 结束', { time: time(session.expiresAt) })
      : t('已结束：{reason}', { reason: endReason(session.endReason) })
    const action = node('td')
    if (session.active) {
      const stop = node('button', 'capture-stop', t('停止'))
      stop.type = 'button'
      stop.addEventListener('click', event => { event.stopPropagation(); stopSession(session.id) })
      action.append(stop)
    }
    row.append(node('td', 'mono', session.id), node('td', 'mono', ruleText(session.rule)),
      node('td', 'numeric', `${session.capturedCount} / ${session.rule.maxCount}`), node('td', '', time(session.startedAt)),
      node('td', session.active ? '' : 'muted', state), node('td', 'mono', session.operator), action)
    const select = () => {
      selectedSession = selectedSession === session.id ? '' : session.id
      renderSessions()
      loadRecords()
    }
    row.addEventListener('click', select)
    row.addEventListener('keydown', event => { if (event.key === 'Enter') select() })
    rows.append(row)
  }
}

function endReason(reason) {
  if (!reason) return '—'
  if (reason.startsWith('reached ')) return t('已达抓取次数')
  if (reason === 'expired') return t('已到时间')
  if (reason.startsWith('stopped by ')) return t('被 {who} 停止', { who: reason.slice(11) })
  return reason
}

async function startSession() {
  const rule = {
    method: $('rule-method').value,
    pathPattern: $('rule-path').value.trim(),
    headerName: $('rule-header').value.trim(),
    headerValue: $('rule-header-value').value.trim(),
    maxCount: Number($('rule-count').value),
    durationSeconds: Number($('rule-duration').value)
  }
  if (!rule.pathPattern.startsWith('/')) {
    showNotice(t('路径必须以 / 开头'), true)
    $('rule-path').focus()
    return
  }
  try {
    const session = await request('capture/sessions', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(rule)
    }, DENIED)
    selectedSession = session.id
    showNotice(t('抓取 #{id} 已开始，匹配的请求会出现在下方', { id: session.id }))
    await refresh()
  } catch (error) {
    showNotice(error.message, true)
  }
}

async function stopSession(id) {
  try {
    await request(`capture/sessions/${encodeURIComponent(id)}/stop`, { method: 'POST' }, DENIED)
    await refresh()
  } catch (error) {
    showNotice(error.message, true)
  }
}

async function clearRecords() {
  if (!window.confirm(t('确定清空所有抓取记录？'))) return
  try {
    await request('capture/records/clear', { method: 'POST' }, DENIED)
    selectedRecord = ''
    await loadRecords()
  } catch (error) {
    showNotice(error.message, true)
  }
}

async function loadRecords() {
  const query = selectedSession ? `?sessionId=${encodeURIComponent(selectedSession)}` : ''
  $('records-filter').textContent = selectedSession ? t('（任务 #{id}）', { id: selectedSession }) : ''
  const requestId = ++recordsRequest
  let records
  try {
    records = await request('capture/records' + query, {}, DENIED)
  } catch (error) {
    if (requestId === recordsRequest) showNotice(error.message, true)
    return
  }
  // A newer load (session switch, auto refresh) started meanwhile; its response wins.
  if (requestId !== recordsRequest) return
  const rows = $('record-rows')
  // Keep an open detail across auto refreshes instead of collapsing it.
  const openDetail = rows.querySelector('tr.live-detail')
  rows.replaceChildren()
  $('records-empty').hidden = records.length > 0
  for (const record of records) {
    const row = node('tr')
    row.tabIndex = 0
    row.classList.toggle('is-expanded', record.id === selectedRecord)
    const entry = node('td', 'mono')
    entry.append(node('span', 'live-kind', record.method), record.path)
    row.append(node('td', '', time(record.finishedAt)), entry,
      node('td', `numeric ${record.error || record.status >= 500 ? 'bad' : record.status >= 400 ? 'warn' : ''}`,
        String(record.status || '—')),
      node('td', 'numeric', formatMs(record.durationMs)), node('td', 'mono', record.traceId))
    const toggle = () => {
      selectedRecord = selectedRecord === record.id ? '' : record.id
      loadRecords()
    }
    row.addEventListener('click', toggle)
    row.addEventListener('keydown', event => { if (event.key === 'Enter') toggle() })
    rows.append(row)
    if (record.id === selectedRecord) {
      if (openDetail?.dataset.id === record.id) rows.append(openDetail)
      else rows.append(detailRow(record.id))
    }
  }
}

function detailRow(id) {
  const row = node('tr', 'live-detail')
  row.dataset.id = id
  const cell = node('td')
  cell.colSpan = 5
  cell.textContent = t('读取中')
  row.append(cell)
  request(`capture/records/${encodeURIComponent(id)}`, { notFoundMessage: t('记录已过期') }, DENIED)
    .then(record => cell.replaceChildren(renderRecord(record)))
    .catch(error => { cell.textContent = error.message })
  return row
}

function renderRecord(record) {
  const wrapper = node('div', 'capture-tree')
  if (record.errorType) {
    wrapper.append(field('error', `${record.errorType}: ${record.errorMessage}\n${record.errorStackTrace}`.trim()))
  }
  for (const item of record.nodes) wrapper.append(renderNode(item, true))
  return wrapper
}

function hasCaptures(item) {
  return Object.keys(item.captures || {}).length > 0 || (item.children || []).some(hasCaptures)
}

function renderNode(item, open) {
  const details = node('details', `capture-node${item.error ? ' error' : ''}`)
  const captures = item.captures || {}
  details.open = open || hasCaptures(item)
  const summary = node('summary')
  summary.append(node('span', 'live-kind', kindName(item.kind)), node('span', 'capture-name', item.name),
    node('span', 'capture-tag', item.tag), node('span', 'capture-ms', formatMs(item.durationMs)))
  details.append(summary)
  const body = node('div', 'capture-body')
  const keys = Object.keys(captures)
  const ordered = [...Object.keys(FIELD_LABELS).filter(key => key in captures), ...keys.filter(key => !(key in FIELD_LABELS))]
  for (const key of ordered) {
    if (captures[key] == null || captures[key] === '') continue
    body.append(field(key, captures[key]))
  }
  if (item.children?.length) {
    const children = node('div', 'capture-children')
    for (const child of item.children) children.append(renderNode(child, false))
    body.append(children)
  }
  if (body.childNodes.length) details.append(body)
  return details
}

function field(key, value) {
  const section = node('section', `capture-field${key === 'error' ? ' error' : ''}`)
  section.append(node('h4', '', FIELD_LABELS[key] ? t(FIELD_LABELS[key]) : key), node('pre', '', value))
  return section
}

$('refresh').addEventListener('click', refresh)
$('start').addEventListener('click', startSession)
$('rule-path').addEventListener('keydown', event => { if (event.key === 'Enter') startSession() })
$('clear').addEventListener('click', clearRecords)
initializeEmbeddedAuth().then(refresh)
