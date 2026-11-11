// Shared by every dashboard page: API access, embedded auth, notices and small DOM helpers.
// Same labels on every page for span kinds; t() from i18n.js translates them.
const KIND_NAMES = { Http: 'HTTP', Database: 'SQL', Redis: 'Redis', Remote: '远程调用', Kafka: 'Kafka', Transaction: '事务',
  Schedule: '定时任务', Service: '服务', Code: '代码', Other: '其他' }
export const kindName = kind => t(KIND_NAMES[kind] || kind || '调用')

export const api = new URL('../../api/meerkat/', import.meta.url)
export const $ = id => document.getElementById(id)

export const SESSION_EXPIRED_MESSAGE = t('登录已失效或请求被重定向到登录页，请刷新页面后重新登录')

/** The bearer token handed over by an embedding page, or an empty string. */
export function bearerToken() {
  return typeof window.MeerkatAuth?.token === 'function' ? window.MeerkatAuth.token() : ''
}

/** Spring Security's CookieCsrfTokenRepository exposes XSRF-TOKEN; state-changing requests echo it. */
export function csrfToken() {
  const csrf = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return csrf ? decodeURIComponent(csrf[1]) : ''
}

/** Headers for an API call: the embedding page's bearer token and, on state-changing requests, the CSRF token. */
export function authHeaders(method = 'GET', initial = {}) {
  const headers = new Headers(initial)
  const token = bearerToken()
  if (token) headers.set('Authorization', 'Bearer ' + token)
  const csrf = csrfToken()
  const upper = method.toUpperCase()
  if (csrf && upper !== 'GET' && upper !== 'HEAD') headers.set('X-XSRF-TOKEN', csrf)
  return headers
}

/** True when an expired session sent the call to a login page instead of the API. */
export function loginRedirected(response) {
  const type = response.headers.get('Content-Type') || ''
  return /text\/html/i.test(type) || (response.redirected && !/json/i.test(type))
}

/** Fetches {@code path} under the API root and parses JSON; errors carry a message ready to show. */
export async function request(path, requestOptions = {}, deniedMessage = t('没有查看的权限，请登录或检查 ObservabilityAccessPolicy')) {
  const { notFoundMessage, ...options } = requestOptions
  const headers = authHeaders(options.method || 'GET', options.headers || {})
  const response = await fetch(new URL(path.replace(/^\/+/, ''), api),
    { credentials: 'same-origin', ...options, headers })
  if (!response.ok) throw new Error(response.status === 401 || response.status === 403
    ? deniedMessage
    : response.status === 404 && notFoundMessage ? notFoundMessage
      : t('请求失败（HTTP {status}）', { status: response.status }))
  if (response.status === 204) return null
  if (loginRedirected(response)) throw new Error(SESSION_EXPIRED_MESSAGE)
  return response.json()
}

/**
 * Runs {@code task} every {@code intervalMs} (a number or a function returning one), timing each wait from the end
 * of the previous run so slow responses never pile up. A run that resolves to false or throws counts as a failure
 * and stretches the wait up to four times; hidden pages skip runs. Returns {restart, stop}.
 */
export function poll(task, intervalMs) {
  let timer = null
  let failures = 0
  let active = true
  const next = () => {
    window.clearTimeout(timer)
    if (!active) return
    const base = typeof intervalMs === 'function' ? intervalMs() : intervalMs
    timer = window.setTimeout(tick, base * 2 ** failures)
  }
  const tick = async () => {
    if (!document.hidden) {
      let ok = false
      try { ok = (await task()) !== false } catch { /* counted as a failure below */ }
      failures = ok ? 0 : Math.min(failures + 1, 2)
    }
    next()
  }
  next()
  return { restart: next, stop() { active = false; window.clearTimeout(timer) } }
}

export function initializeEmbeddedAuth() {
  if (window.parent === window) return Promise.resolve()
  return new Promise(resolve => {
    let completed = false
    const finish = token => {
      if (completed) return
      completed = true
      clearTimeout(timeout)
      window.removeEventListener('message', receiveAuth)
      window.MeerkatAuth = { token: () => token }
      resolve()
    }
    const receiveAuth = event => {
      if (event.origin !== window.location.origin || event.source !== window.parent
        || event.data?.type !== 'meerkat:auth-response') return
      finish(typeof event.data.token === 'string' ? event.data.token : '')
    }
    const timeout = setTimeout(() => finish(''), 1500)
    window.addEventListener('message', receiveAuth)
    window.parent.postMessage({ type: 'meerkat:auth-request' }, window.location.origin)
  })
}

export function node(tag, className, text) {
  const element = document.createElement(tag)
  if (className) element.className = className
  if (text != null) element.textContent = text
  return element
}

/** Shows {@code message} in the page's #notice banner; an empty message hides it. */
export function showNotice(message, warning = false) {
  const notice = $('notice')
  notice.textContent = message
  notice.classList.toggle('warning', warning)
  notice.hidden = !message
}
