'use strict';

/*
 * ISPSender control panel. Vanilla JS, no framework.
 * Server data reaches the DOM only through textContent, text nodes and
 * setAttribute, never through markup strings, so names from the server stay text.
 */

const PAGE_SIZE = 100; // rows per /api/logs page (server limit)
const SEARCH_DELAY_MS = 300; // debounce for the log search box
const REFRESH_MS = 10000; // background state refresh while the tab is visible
const RECENT_COUNT = 8; // rows in the overview's recent list
const CHART_HEIGHT = 200;
const CHART_MIN_WIDTH = 320;
const BAR_RADIUS = 4; // rounded data end of a bar, in px; the baseline end stays square
const SVG_NS = 'http://www.w3.org/2000/svg';

const PAGES = {
  overview: { title: '개요', wide: true },
  routing: { title: '서버 · 라우팅', wide: false },
  simulator: { title: '시뮬레이터', wide: false },
  stats: { title: '통계', wide: true },
  logs: { title: '접속 기록', wide: true },
  settings: { title: '설정', wide: false },
};

const REASON_TEXT = {
  DNS: 'DNS 조회 실패',
  REFUSED: '연결 거부',
  TIMEOUT: '시간 초과',
  UNREACHABLE: '네트워크 도달 불가',
  BAD_RESPONSE: 'Minecraft 응답 없음',
};

const SAVED_MESSAGE = '저장했습니다. 변경 사항이 적용되었습니다.';
const SAVED_RESTART_MESSAGE = '설정을 저장했습니다. 일부 변경은 재시작 후 적용됩니다.';

let state = null;
let csrf = '';
let page = 'overview';
let logOffset = 0;
let logRequest = 0;
let statsRequest = 0;
let statsData = null;
let searchTimer = 0;
let chartTimer = 0;
let activeBar = null;

const $ = (id) => document.getElementById(id);

/* ---------- DOM helpers ---------- */

const PROPERTY_ATTRIBUTES = new Set(['value', 'checked', 'required', 'disabled', 'hidden']);

/** Builds an element. Plain strings among the children become text nodes. */
function h(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (name === 'class') node.className = value;
    else if (PROPERTY_ATTRIBUTES.has(name)) node[name] = value;
    else node.setAttribute(name, value === true ? '' : String(value));
  }
  node.append(
    ...children.flat().filter((child) => child !== undefined && child !== null && child !== false),
  );
  return node;
}

function svgEl(tag, attributes = {}, ...children) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [name, value] of Object.entries(attributes)) node.setAttribute(name, String(value));
  node.append(...children);
  return node;
}

const emptyRow = (text) => h('div', { class: 'row empty' }, text);
const orDash = (value) => (value === undefined || value === null || value === '' ? '—' : String(value));
const formatNumber = (value) => Number(value || 0).toLocaleString('ko-KR');
const formatCompact = (value) => (value >= 10000 ? `${(value / 10000).toFixed(1)}만` : formatNumber(value));
const rateText = (kicked, total) => (total > 0 ? `${((kicked / total) * 100).toFixed(1)}%` : '—');
const fallbackOn = () => Boolean(state?.config?.['fallback-enabled']);

function formatDateTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('ko-KR');
}

function formatClock(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleTimeString('ko-KR');
}

function formatLogTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function todayIso() {
  const now = new Date();
  const pad = (value) => String(value).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

function poolText(value) {
  if (Array.isArray(value)) return value.join('\n');
  return value ? String(value) : '';
}

function poolValue(text) {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean);
}

/* ---------- API and feedback ---------- */

async function api(path, options = {}) {
  let response;
  try {
    response = await fetch(path, {
      ...options,
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf, ...options.headers },
    });
  } catch (_) {
    throw new Error('서버에 연결할 수 없습니다.');
  }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    if (response.status === 401 && path !== '/api/login') showLogin();
    throw new Error(data.error || '요청에 실패했습니다.');
  }
  return data;
}

function notify(message, isError = false) {
  const node = $('notice');
  node.textContent = message;
  node.classList.toggle('error', isError);
  node.hidden = false;
}

/** Disables the button while the task runs. Errors go to the notice unless onError is given. */
async function withBusy(button, task, onError = (error) => notify(error.message, true)) {
  if (button) button.disabled = true;
  try {
    await task();
  } catch (error) {
    onError(error);
  } finally {
    if (button) button.disabled = false;
  }
}

/* ---------- Shell: login, navigation, refresh ---------- */

function showLogin() {
  state = null;
  csrf = '';
  $('app').hidden = true;
  $('login').hidden = false;
}

function navigate(next) {
  page = next;
  for (const section of document.querySelectorAll('.page')) {
    section.hidden = section.id !== page;
  }
  for (const item of document.querySelectorAll('.nav-item')) {
    if (item.dataset.page === page) item.setAttribute('aria-current', 'page');
    else item.removeAttribute('aria-current');
  }
  $('page-title').textContent = PAGES[page].title;
  $('content-inner').dataset.width = PAGES[page].wide ? 'wide' : 'narrow';
  $('content').scrollTop = 0;
  if (page === 'logs') loadLogs().catch((error) => notify(error.message, true));
  if (page === 'stats') loadStats();
}

/** Reloads /api/state. Settings forms are rebuilt only on first load or after a save. */
async function refresh(withConfig = false) {
  const next = await api('/api/state');
  const first = state === null;
  state = next;
  csrf = next.csrf;
  $('login').hidden = true;
  $('app').hidden = false;
  $('restart').hidden = !state.restartRequired;
  renderOverview();
  if (withConfig || first) renderConfig();
}

async function saveConfig(values) {
  return api('/api/config', { method: 'PUT', body: JSON.stringify(values) });
}

async function saveAndReload(values) {
  const result = await saveConfig(values);
  await refresh(true);
  notify(result.restartRequired ? SAVED_RESTART_MESSAGE : SAVED_MESSAGE);
}

/* ---------- Overview ---------- */

function serverStatus(server) {
  if (!server.checkedAt) return { tone: 'pending', text: '확인 대기' };
  if (server.alive) {
    if (server.failures > 0) {
      const limit = state?.healthFailureThreshold;
      return {
        tone: 'warn',
        text: limit ? `불안정 ${server.failures}/${limit}` : `불안정 ${server.failures}회 실패`,
      };
    }
    return { tone: 'ok', text: server.latencyMillis >= 0 ? `정상 ${server.latencyMillis} ms` : '정상' };
  }
  return { tone: 'down', text: REASON_TEXT[server.reason] || '응답 없음' };
}

function renderServers(servers) {
  const list = $('server-list');
  list.replaceChildren();
  if (!servers.length) {
    list.append(emptyRow('서버 · 라우팅에서 서버를 추가하세요.'));
    return;
  }
  for (const server of servers) {
    const status = serverStatus(server);
    let detail = '첫 상태 확인 대기';
    if (server.srv) detail = `SRV → ${orDash(server.target)}`;
    else if (server.checkedAt) detail = `확인 ${formatClock(server.checkedAt)}`;
    list.append(
      h(
        'div',
        { class: 'row' },
        h(
          'div',
          { class: 'row-main' },
          h('span', { class: 'row-title' }, orDash(server.server)),
          h('span', { class: 'row-sub' }, detail),
        ),
        h('span', { class: `status ${status.tone}` }, status.text),
      ),
    );
  }
}

function recentRow(log) {
  const transferred = log.result === 'transferred';
  const region = [log.asn, log.country].filter(Boolean).join(' ');
  const who = [log.ip, region].filter(Boolean).join(', ');
  return h(
    'div',
    { class: 'row' },
    h(
      'div',
      { class: 'row-main' },
      h('span', { class: 'row-title' }, orDash(log.player)),
      h('span', { class: 'row-sub' }, orDash(who)),
    ),
    h(
      'div',
      { class: 'row-end' },
      h('span', { class: transferred ? 'result transferred' : 'result refused' }, transferred ? '전송' : '거절'),
      h('span', { class: 'row-sub' }, formatLogTime(log.time)),
    ),
  );
}

function renderRecent(logs) {
  const list = $('recent-list');
  list.replaceChildren();
  if (!logs.length) {
    list.append(emptyRow('표시할 접속 기록이 없습니다.'));
    return;
  }
  for (const log of logs) list.append(recentRow(log));
}

function renderOverview() {
  const servers = state.servers || [];
  const alive = servers.filter((server) => server.alive).length;
  $('stat-servers').textContent = `${alive} / ${servers.length}`;
  $('stat-fallback').textContent = fallbackOn() ? '켜짐' : '꺼짐';
  $('stat-db').textContent = state.databaseLoaded ? '사용 중' : '대기 중';
  $('stat-db-note').textContent = state.lastDownload
    ? `마지막 다운로드 ${state.lastDownload}`
    : '다운로드 성공 기록 없음';
  $('fallback-toggle').checked = fallbackOn();
  $('db-last').textContent = state.lastDownload ? String(state.lastDownload) : '기록 없음';
  $('db-next').textContent = state.nextDownload ? formatDateTime(state.nextDownload) : '확인 중';
  renderServers(servers);
  renderRecent((state.recentLogs || []).slice(0, RECENT_COUNT));
}

/* ---------- Log rows (shared by the overview and the logs page) ---------- */

function logTableRow(log) {
  const transferred = log.result === 'transferred';
  return h(
    'tr',
    {},
    h('td', { 'data-label': '시각' }, formatLogTime(log.time)),
    h(
      'td',
      { 'data-label': '플레이어 / IP' },
      h('span', {}, orDash(log.player)),
      h('span', { class: 'sub' }, orDash(log.ip)),
    ),
    h('td', { 'data-label': 'ASN / 국가' }, orDash([log.asn, log.country].filter(Boolean).join(' '))),
    h(
      'td',
      { 'data-label': '서버 / 규칙' },
      h('span', {}, orDash(log.server)),
      h('span', { class: 'sub' }, log.rule || '사용 가능한 서버 없음'),
    ),
    h(
      'td',
      { 'data-label': '결과' },
      h('span', { class: transferred ? 'result transferred' : 'result refused' }, transferred ? '전송' : '거절'),
    ),
  );
}

function renderLogRows(rows, searching) {
  const body = $('log-rows');
  body.replaceChildren();
  if (!rows.length) {
    body.append(
      h(
        'tr',
        {},
        h(
          'td',
          { class: 'empty', colspan: 5 },
          searching ? '검색 결과가 없습니다.' : '표시할 접속 기록이 없습니다.',
        ),
      ),
    );
    return;
  }
  for (const log of rows) body.append(logTableRow(log));
}

/* ---------- Routing and settings forms ---------- */

const SETTING_GROUPS = [
  {
    title: '데이터베이스',
    footer: '자정 업데이트와 IP 조회에 사용하는 설정입니다.',
    rows: [
      {
        key: 'token',
        label: 'IPinfo 토큰',
        type: 'password',
        help: 'IPINFO_TOKEN 환경변수가 우선합니다. 빈 값은 기존 토큰을 유지합니다.',
      },
      { key: 'database-url', label: '다운로드 URL', type: 'text', help: '{token}은 다운로드할 때 실제 토큰으로 바뀝니다.' },
      {
        key: 'database-file',
        label: 'DB 파일 이름',
        type: 'text',
        help: '플러그인 폴더 내 파일 이름. 변경 시 재시작이 필요합니다.',
      },
      {
        key: 'database-timezone',
        label: '업데이트 시간대',
        type: 'text',
        help: '기본 Asia/Seoul. 매일 이 시간대의 00:00에 갱신합니다.',
      },
      { key: 'connect-timeout-seconds', label: '연결 제한 시간 (초)', type: 'number', help: '1–300초' },
      { key: 'read-timeout-seconds', label: '다운로드 제한 시간 (초)', type: 'number', help: '1–3600초' },
    ],
  },
  {
    title: '상태 확인',
    rows: [
      {
        key: 'health-check-mode',
        label: '확인 방식',
        type: 'select',
        options: [
          ['minecraft', 'Minecraft 핑'],
          ['tcp', 'TCP 연결'],
        ],
        help: 'Minecraft 핑은 서버 상태 응답까지 확인합니다. TCP 연결은 포트 연결만 확인합니다.',
      },
      {
        key: 'health-check-timeout-millis',
        label: '확인 제한 시간 (ms)',
        type: 'number',
        help: '100–5000ms. 상태 확인 간격은 10초입니다.',
      },
      {
        key: 'health-failure-threshold',
        label: '연속 실패 판정 횟수',
        type: 'number',
        help: '1–10회. 이 횟수만큼 연속으로 실패하면 서버를 사용하지 않습니다.',
      },
    ],
  },
  {
    title: '접속 기록',
    rows: [
      {
        key: 'connection-log-retention-days',
        label: '보관 기간 (일)',
        type: 'number',
        help: '1–365일. 지난 기록은 다음 로그 기록 시 정리합니다.',
      },
      {
        key: 'log-lookups',
        label: '접속 조회를 콘솔에도 출력',
        type: 'switch',
        help: '파일 접속 기록은 이 설정과 관계없이 저장됩니다.',
      },
    ],
  },
  {
    title: '라우팅',
    rows: [
      {
        key: 'fallback-enabled',
        label: 'Fallback 서버 사용',
        type: 'switch',
        help: '일반 경로를 모두 사용할 수 없을 때만 사용합니다.',
      },
      {
        key: 'no-server-message',
        label: '연결 거절 메시지',
        type: 'text',
        help: 'MiniMessage 형식. 모든 경로를 사용할 수 없을 때 표시합니다.',
      },
    ],
  },
  {
    title: 'Discord 알림',
    footer: '서버 다운과 복구, 사용 가능한 경로가 없을 때 알림을 보냅니다.',
    rows: [
      {
        key: 'discord-alerts-enabled',
        label: '알림 사용',
        type: 'switch',
        help: '웹훅 주소가 없으면 알림을 보내지 않습니다.',
      },
      {
        key: 'discord-webhook-url',
        label: '웹훅 주소',
        type: 'password',
        status: () => (state?.discordConfigured ? '설정됨' : '미설정'),
        help: '비워 두면 기존 주소를 유지합니다. 환경변수 ISPSENDER_DISCORD_WEBHOOK이 있으면 그 값이 우선합니다. 주소는 화면에 표시하지 않습니다.',
      },
    ],
  },
  {
    title: '웹패널 · 보안',
    footer: '이 그룹의 변경은 프록시 재시작 후 적용됩니다.',
    rows: [
      {
        key: 'web-bind',
        label: '바인드 주소',
        type: 'text',
        help: '127.0.0.1 + Tailscale Serve 권장. 또는 서버에 할당된 Tailscale IP.',
      },
      { key: 'web-port', label: '포트', type: 'number', help: '1–65535' },
      {
        key: 'web-password',
        label: '관리자 비밀번호 변경',
        type: 'password',
        help: '12자 이상. 빈 값은 기존 비밀번호 유지. ISPSENDER_WEB_PASSWORD 환경변수가 우선합니다.',
      },
      {
        key: 'web-enabled',
        label: '웹패널 활성화',
        type: 'switch',
        help: '비활성화하면 재시작 후 이 패널에 접속할 수 없습니다.',
      },
    ],
  },
  {
    title: '이전 버전 호환',
    footer: '아래 키는 보존되지만, 일일 갱신 정책이 우선하므로 더 이상 사용하지 않습니다.',
    rows: [
      {
        key: 'update-on-start',
        label: '이전 시작 시 갱신 옵션',
        type: 'switch',
        help: '날짜별 갱신 정책으로 대체되었습니다.',
      },
      {
        key: 'update-interval-minutes',
        label: '이전 갱신 주기 (분)',
        type: 'number',
        help: '자정 갱신으로 대체되었습니다.',
      },
    ],
  },
];

function renderConfig() {
  const config = state.config || {};
  $('default-servers').value = poolText(config['default-server']);
  $('fallback-servers').value = poolText(config['fallback-servers']);
  renderRules(config);
  $('settings-fields').replaceChildren(...SETTING_GROUPS.map((group) => settingGroup(group, config)));
}

function renderRules(config) {
  const list = $('rules');
  list.replaceChildren();
  for (const [type, key] of [
    ['asn', 'asn-servers'],
    ['country', 'country-servers'],
  ]) {
    for (const [rule, servers] of Object.entries(config[key] || {})) {
      list.append(ruleRow(type, rule, servers));
    }
  }
  syncRules();
}

/** Shows the empty placeholder only while there are no rule rows. */
function syncRules() {
  const list = $('rules');
  const placeholder = list.querySelector('.rule-empty');
  if (list.querySelector('.rule')) {
    placeholder?.remove();
  } else if (!placeholder) {
    list.append(h('div', { class: 'row empty rule-empty' }, '규칙이 없습니다. 규칙 추가를 눌러 만드세요.'));
  }
}

function ruleRow(type = 'asn', key = '', servers = []) {
  const select = h(
    'select',
    { class: 'rule-type', 'aria-label': '규칙 종류' },
    h('option', { value: 'asn' }, 'ASN'),
    h('option', { value: 'country' }, '국가'),
  );
  select.value = type;
  const row = h(
    'div',
    { class: 'row stack rule' },
    h(
      'div',
      { class: 'rule-head' },
      h('span', { class: 'popup' }, select),
      h('input', {
        class: 'field rule-key',
        type: 'text',
        value: key,
        placeholder: 'AS4766 / KR',
        'aria-label': 'ASN 또는 국가',
        required: true,
        autocomplete: 'off',
        spellcheck: 'false',
      }),
      h('button', { type: 'button', class: 'button plain destructive', 'aria-label': '규칙 삭제' }, '삭제'),
    ),
    h('textarea', {
      class: 'textarea rule-servers',
      rows: 3,
      value: poolText(servers),
      placeholder: 'server1.example.com:25565\nserver2.example.com:25565',
      'aria-label': '서버 목록',
      required: true,
    }),
  );
  row.querySelector('.destructive').addEventListener('click', () => {
    row.remove();
    syncRules();
  });
  return row;
}

function settingGroup(group, config) {
  return h(
    'section',
    { class: 'section' },
    h('h2', { class: 'section-caption' }, group.title),
    h('div', { class: 'group' }, ...group.rows.map((field) => settingRow(field, config))),
    group.footer ? h('p', { class: 'section-footer' }, group.footer) : null,
  );
}

function settingRow(field, config) {
  const id = `field-${field.key}`;
  const main = h(
    'div',
    { class: 'row-main' },
    h('label', { class: 'row-title', for: id }, field.label),
    field.help ? h('span', { class: 'row-help' }, field.help) : null,
  );
  const status = field.status ? h('span', { class: 'row-value' }, field.status()) : null;
  return h(
    'div',
    { class: 'row' },
    main,
    h('div', { class: 'row-controls' }, status, controlFor(field, id, config[field.key])),
  );
}

function controlFor(field, id, value) {
  if (field.type === 'switch') {
    return h(
      'label',
      { class: 'switch' },
      h('input', { id, name: field.key, type: 'checkbox', role: 'switch', checked: Boolean(value) }),
      h('span', { class: 'track', 'aria-hidden': 'true' }),
    );
  }
  if (field.type === 'select') {
    const current = String(value ?? '').toLowerCase();
    const select = h(
      'select',
      { id, name: field.key, 'aria-label': field.label },
      ...field.options.map(([optionValue, text]) => h('option', { value: optionValue }, text)),
    );
    select.value = field.options.some(([optionValue]) => optionValue === current)
      ? current
      : field.options[0][0];
    return h('span', { class: 'popup' }, select);
  }
  // Secrets are never filled in from the server; blank keeps the stored value.
  const secret = field.type === 'password';
  return h('input', {
    id,
    name: field.key,
    class: 'field',
    type: field.type === 'number' ? 'number' : secret ? 'password' : 'text',
    value: secret ? '' : (value ?? ''),
    placeholder: secret ? '변경할 때만 입력' : undefined,
    autocomplete: secret ? 'new-password' : 'off',
    spellcheck: secret ? undefined : 'false',
    step: field.type === 'number' ? '1' : undefined,
  });
}

/* ---------- Routing: collect rules, check an address ---------- */

/** Reads the rule rows. Throws on an empty or duplicate rule (same rules as before). */
function collectRules() {
  const result = { 'asn-servers': {}, 'country-servers': {} };
  const seen = { asn: new Set(), country: new Set() };
  for (const row of $('rules').querySelectorAll('.rule')) {
    const type = row.querySelector('.rule-type').value;
    const key = row.querySelector('.rule-key').value.trim();
    if (!key) throw new Error('규칙 값을 입력하세요.');
    const canonical = type === 'asn' ? key.toUpperCase().replace(/^AS/, '') : key.toUpperCase();
    if (seen[type].has(canonical)) throw new Error(`중복된 규칙입니다: ${key}`);
    seen[type].add(canonical);
    const target = type === 'asn' ? 'asn-servers' : 'country-servers';
    result[target][key] = poolValue(row.querySelector('.rule-servers').value);
  }
  return result;
}

function checkLine(label, ...values) {
  return h(
    'div',
    { class: 'row' },
    h('span', { class: 'row-label' }, label),
    h(
      'div',
      { class: 'row-end' },
      ...values.map((value) => (typeof value === 'string' ? h('span', {}, value) : value)),
    ),
  );
}

function srvLines(result) {
  if (!result.srvEligible) return ['조회하지 않음 (포트를 지정했습니다)'];
  if (!result.srv || result.srv.length === 0) return ['없음'];
  return result.srv.map(
    (record) => `우선순위 ${record.priority}, 가중치 ${record.weight}, ${orDash(record.target)}:${record.port}`,
  );
}

function listLines(values, emptyText) {
  return values && values.length ? values.map(String) : [emptyText];
}

function renderCheck(result) {
  const reachable = Boolean(result.reachable);
  const minecraft = Boolean(result.minecraft);
  const reason = REASON_TEXT[result.reason];
  const connection = reachable
    ? h('span', { class: 'status ok' }, '연결됨')
    : h('span', { class: 'status down' }, `실패 · ${reason || '확인 실패'}`);
  let ping;
  if (minecraft) ping = h('span', { class: 'status ok' }, '정상');
  else if (reachable) ping = h('span', { class: 'status down' }, reason || '응답 없음');
  else ping = h('span', { class: 'status pending' }, '확인 불가');
  $('check-result').replaceChildren(
    checkLine('SRV 레코드', ...srvLines(result)),
    checkLine('CNAME', ...listLines(result.cname, '없음')),
    checkLine('IP 주소', ...listLines(result.addresses, '없음')),
    checkLine('실제 연결 대상', `${orDash(result.target)}${result.usesSrv ? ' (SRV 기준)' : ''}`),
    checkLine('연결 가능 여부', connection),
    checkLine('Minecraft 응답', ping),
    checkLine('지연', result.latencyMillis >= 0 ? `${result.latencyMillis} ms` : '—'),
  );
  $('check-result-section').hidden = false;
}

/* ---------- Simulator ---------- */

function infoRow(label, value) {
  return h(
    'div',
    { class: 'row' },
    h('span', { class: 'row-label' }, label),
    h('span', { class: 'row-value' }, orDash(value)),
  );
}

function stepRow(step) {
  const servers = step.servers || [];
  const available = step.available || [];
  let stateText = '사용 불가';
  if (step.selected) stateText = '선택됨';
  else if (available.length) stateText = '미사용';
  const detail = available.length
    ? `사용 가능 ${available.length} / ${servers.length}: ${available.join(', ')}`
    : `사용 가능한 서버 없음 (후보 ${servers.length})`;
  return h(
    'div',
    { class: step.selected ? 'row step selected' : 'row step' },
    h(
      'div',
      { class: 'row-main' },
      h('span', { class: 'row-title' }, orDash(step.rule)),
      h('span', { class: 'row-sub' }, detail),
    ),
    h('span', { class: step.selected ? 'step-state selected' : 'step-state' }, stateText),
  );
}

function renderSimulation(result) {
  const region = [result.country, result.countryCode ? `(${result.countryCode})` : '']
    .filter(Boolean)
    .join(' ');
  const asn = [result.asn, result.asnName].filter(Boolean).join(' ');
  const info = [infoRow('IP', result.ip), infoRow('ASN', asn), infoRow('국가', region)];
  if (!result.found) {
    info.push(emptyRow('데이터베이스에서 찾을 수 없습니다. ASN · 국가 규칙은 적용되지 않습니다.'));
  }
  $('sim-info').replaceChildren(...info);

  const steps = result.steps || [];
  $('sim-steps').replaceChildren(...(steps.length ? steps.map(stepRow) : [emptyRow('적용할 규칙이 없습니다.')]));

  const route = result.route;
  $('sim-result').replaceChildren(
    ...(route
      ? [
          infoRow('전송 서버', route.server),
          infoRow('실제 연결 대상', route.target),
          infoRow('선택된 규칙', route.rule),
        ]
      : [h('div', { class: 'row' }, h('span', { class: 'result refused' }, '전송할 서버 없음 (접속 거절)'))]),
  );
  $('sim-output').hidden = false;
}

/* ---------- Statistics ---------- */

const hasTraffic = (stats) => stats.transferred + stats.kicked > 0;
const percent = (value) => `${value.toFixed(3)}%`;

async function loadStats() {
  const checked = document.querySelector('input[name="stats-range"]:checked');
  const days = Number(checked ? checked.value : 7);
  const request = ++statsRequest;
  $('stats-error').textContent = '';
  $('stats-period').textContent = '불러오는 중';
  try {
    const stats = await api(`/api/stats?days=${days}`);
    if (request !== statsRequest) return;
    statsData = stats;
    renderStats(stats);
  } catch (error) {
    if (request !== statsRequest) return;
    statsData = null;
    $('stats-error').textContent = error.message;
  }
}

function renderStats(stats) {
  const traffic = hasTraffic(stats);
  const total = stats.transferred + stats.kicked;
  $('stats-transferred').textContent = formatNumber(stats.transferred);
  $('stats-kicked').textContent = formatNumber(stats.kicked);
  $('stats-rate').textContent = rateText(stats.kicked, total);
  $('stats-period').textContent = `${orDash(stats.from)} ~ ${orDash(stats.to)}`;
  $('stats-empty').hidden = traffic;
  $('stats-content').hidden = !traffic;
  if (!traffic) {
    drawChart([]);
    renderTable([]);
    return;
  }
  const days = stats.days || [];
  drawChart(days);
  renderTable(days);
  renderRanking($('stats-asn'), stats.asn || []);
  renderRanking($('stats-country'), stats.country || []);
  renderRanking($('stats-server'), stats.server || []);
}

/** Table twin of the chart, so every daily value is readable without hovering. */
function renderTable(days) {
  $('stats-table-rows').replaceChildren(
    ...days.map((day) => {
      const total = day.transferred + day.kicked;
      return h(
        'tr',
        {},
        h('td', {}, orDash(day.key)),
        h('td', { class: 'num' }, formatNumber(day.transferred)),
        h('td', { class: 'num' }, formatNumber(day.kicked)),
        h('td', { class: 'num' }, rateText(day.kicked, total)),
      );
    }),
  );
}

/** Top-N horizontal bars. Transferred and refused share one track with a 1px surface gap. */
function renderRanking(container, buckets) {
  container.replaceChildren();
  if (!buckets.length) {
    container.append(emptyRow('데이터가 없습니다.'));
    return;
  }
  const max = Math.max(1, ...buckets.map((bucket) => bucket.transferred + bucket.kicked));
  for (const bucket of buckets) {
    const total = bucket.transferred + bucket.kicked;
    const transferredWidth = (bucket.transferred / max) * 100;
    const kickedWidth = (bucket.kicked / max) * 100;
    const gap = bucket.transferred > 0 && bucket.kicked > 0 ? 1 : 0;
    const transferredFill = h('span', { class: 'rank-fill transferred' });
    const kickedFill = h('span', { class: 'rank-fill kicked' });
    transferredFill.style.left = '0%';
    transferredFill.style.width = gap
      ? `max(0px, calc(${percent(transferredWidth)} - ${gap}px))`
      : percent(transferredWidth);
    kickedFill.style.left = gap ? `calc(${percent(transferredWidth)} + ${gap}px)` : percent(transferredWidth);
    kickedFill.style.width = gap ? `max(0px, calc(${percent(kickedWidth)} - ${gap}px))` : percent(kickedWidth);
    container.append(
      h(
        'div',
        { class: 'row rank' },
        h(
          'div',
          { class: 'rank-head' },
          h('span', { class: 'rank-name' }, orDash(bucket.key)),
          h('span', { class: 'rank-value' }, formatNumber(total)),
        ),
        h('div', { class: 'rank-track' }, transferredFill, kickedFill),
        bucket.kicked > 0
          ? h(
              'span',
              { class: 'rank-sub' },
              `전송 ${formatNumber(bucket.transferred)}, 거절 ${formatNumber(bucket.kicked)}`,
            )
          : null,
      ),
    );
  }
}

/** Path of one bar segment. Only the data end (top) can be rounded; the baseline end stays square. */
function barPath(x, y, width, height, roundTop) {
  if (height <= 0) return '';
  const r = roundTop ? Math.min(BAR_RADIUS, width / 2, height) : 0;
  if (r === 0) return `M${x},${y}H${x + width}V${y + height}H${x}Z`;
  return (
    `M${x},${y + height}V${y + r}A${r},${r} 0 0 1 ${x + r},${y}` +
    `H${x + width - r}A${r},${r} 0 0 1 ${x + width},${y + r}V${y + height}Z`
  );
}

/**
 * Daily stacked bars in inline SVG. Transferred sits on the baseline, refused on top,
 * with a 2px surface gap between them. Every bar is focusable and shows the same
 * tooltip on hover and focus. The table view repeats every value.
 */
function drawChart(days) {
  const host = $('stats-chart');
  hideTip();
  host.querySelector('svg')?.remove();
  if (!days.length) return;

  const width = Math.max(CHART_MIN_WIDTH, Math.floor(host.clientWidth) || 640);
  const baseline = CHART_HEIGHT - 26; // date labels sit below the baseline
  const plotTop = 22; // room above the tallest bar for its value label
  const plotHeight = baseline - plotTop;
  const slot = width / days.length;
  const barWidth = Math.min(24, Math.max(6, Math.round(slot * 0.6)));
  const totals = days.map((day) => day.transferred + day.kicked);
  const maxTotal = Math.max(1, ...totals);
  const peak = totals.indexOf(Math.max(...totals));
  const showAllValues = days.length <= 7;
  const labelEvery = Math.max(1, Math.ceil(days.length / Math.floor(width / 44)));

  const svg = svgEl('svg', {
    width,
    height: CHART_HEIGHT,
    viewBox: `0 0 ${width} ${CHART_HEIGHT}`,
    role: 'group',
    'aria-label': '일별 전송과 거절',
  });
  svg.append(svgEl('line', { class: 'chart-axis', x1: 0, x2: width, y1: baseline + 0.5, y2: baseline + 0.5 }));

  days.forEach((day, index) => {
    const total = totals[index];
    const cx = slot * index + slot / 2;
    const x = cx - barWidth / 2;
    const gap = day.transferred > 0 && day.kicked > 0 ? 2 : 0;
    const transferredHeight = day.transferred > 0 ? Math.max(1, (day.transferred / maxTotal) * plotHeight) : 0;
    const kickedHeight = day.kicked > 0 ? Math.max(1, (day.kicked / maxTotal) * plotHeight - gap) : 0;
    const topY = total > 0 ? baseline - transferredHeight - gap - kickedHeight : baseline;

    const bar = svgEl('g', {
      class: 'bar',
      tabindex: 0,
      role: 'img',
      'aria-label': `${orDash(day.key)}: 전송 ${formatNumber(day.transferred)}, 거절 ${formatNumber(day.kicked)}`,
    });
    bar.dataset.cx = cx;
    bar.dataset.top = topY;
    // The hit area is the whole slot, larger than the painted bar.
    bar.append(svgEl('rect', { class: 'bar-hit', x: slot * index, y: 0, width: slot, height: CHART_HEIGHT }));
    if (transferredHeight > 0) {
      bar.append(
        svgEl('path', {
          class: 'seg-transferred',
          d: barPath(x, baseline - transferredHeight, barWidth, transferredHeight, kickedHeight === 0),
        }),
      );
    }
    if (kickedHeight > 0) {
      const kickedTop = baseline - transferredHeight - gap - kickedHeight;
      bar.append(svgEl('path', { class: 'seg-kicked', d: barPath(x, kickedTop, barWidth, kickedHeight, true) }));
    }

    const labelled = total > 0 ? showAllValues || index === peak : showAllValues;
    if (labelled) {
      bar.append(
        svgEl(
          'text',
          { class: 'chart-value', x: cx, y: total > 0 ? topY - 6 : baseline - 6, 'text-anchor': 'middle' },
          total > 0 ? formatCompact(total) : '0',
        ),
      );
    }
    if (index % labelEvery === 0) {
      svg.append(
        svgEl(
          'text',
          { class: 'chart-label', x: cx, y: baseline + 16, 'text-anchor': 'middle' },
          String(day.key || '').slice(5),
        ),
      );
    }

    bar.addEventListener('pointerenter', () => showTip(day, bar));
    bar.addEventListener('pointerleave', hideTip);
    bar.addEventListener('focus', () => showTip(day, bar));
    bar.addEventListener('blur', hideTip);
    svg.append(bar);
  });
  host.insertBefore(svg, $('chart-tip'));
}

function tipRow(className, name, value) {
  return h(
    'div',
    { class: 'chart-tip-row' },
    h('i', { class: `chart-tip-key ${className}`, 'aria-hidden': 'true' }),
    h('span', { class: 'chart-tip-value' }, formatNumber(value)),
    h('span', { class: 'chart-tip-name' }, name),
  );
}

function showTip(day, bar) {
  const tip = $('chart-tip');
  const host = $('stats-chart');
  if (activeBar && activeBar !== bar) activeBar.classList.remove('is-active');
  activeBar = bar;
  bar.classList.add('is-active');
  tip.replaceChildren(
    h('div', { class: 'chart-tip-date' }, orDash(day.key)),
    tipRow('seg-transferred', '전송', day.transferred),
    tipRow('seg-kicked', '거절', day.kicked),
  );
  tip.hidden = false;
  const half = tip.offsetWidth / 2;
  const x = Math.min(Math.max(Number(bar.dataset.cx), half), host.clientWidth - half);
  tip.style.left = `${x}px`;
  tip.style.top = `${Number(bar.dataset.top)}px`;
}

function hideTip() {
  $('chart-tip').hidden = true;
  activeBar?.classList.remove('is-active');
  activeBar = null;
}

/* ---------- Connection logs ---------- */

async function loadLogs() {
  const date = $('log-date').value;
  if (!date) throw new Error('날짜를 선택하세요.');
  const request = ++logRequest;
  const query = $('log-search').value.trim();
  let url = `/api/logs?date=${encodeURIComponent(date)}&offset=${logOffset}`;
  if (query) url += `&q=${encodeURIComponent(query)}`;
  const rows = await api(url);
  if (request !== logRequest) return; // a newer request has already answered
  renderLogRows(rows, Boolean(query));
  $('log-page').textContent = `${logOffset / PAGE_SIZE + 1} 페이지`;
  $('prev-logs').disabled = logOffset === 0;
  $('next-logs').disabled = rows.length < PAGE_SIZE;
}

/** Exports the whole selected date as JSON. The search text is ignored, as before. */
async function exportLogs() {
  const date = $('log-date').value;
  if (!date) throw new Error('날짜를 선택하세요.');
  const rows = [];
  for (let offset = 0; ; offset += PAGE_SIZE) {
    const batch = await api(`/api/logs?date=${encodeURIComponent(date)}&offset=${offset}`);
    rows.push(...batch);
    if (batch.length < PAGE_SIZE) break;
  }
  const url = URL.createObjectURL(new Blob([JSON.stringify(rows, null, 2)], { type: 'application/json' }));
  const link = h('a', { href: url, download: `ispsender-${date}.json` });
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/* ---------- Form helpers ---------- */

function readField(input) {
  if (input.type === 'checkbox') return input.checked;
  if (input.type === 'number') return Number(input.value);
  return input.value;
}

function syncFallbackField() {
  const field = $('field-fallback-enabled');
  if (field) field.checked = fallbackOn();
}

/* ---------- Event wiring ---------- */

$('login-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const error = $('login-error');
  error.textContent = '';
  withBusy(
    event.submitter,
    async () => {
      const result = await api('/api/login', {
        method: 'POST',
        body: JSON.stringify({ password: $('password').value }),
      });
      csrf = result.csrf;
      $('password').value = '';
      await refresh(true);
    },
    (failure) => {
      error.textContent = failure.message;
    },
  );
});

$('logout').addEventListener('click', (event) =>
  withBusy(event.currentTarget, async () => {
    await api('/api/logout', { method: 'POST', body: '{}' });
    showLogin();
  }),
);

$('refresh').addEventListener('click', (event) =>
  withBusy(event.currentTarget, async () => {
    await refresh();
    if (page === 'logs') await loadLogs();
    if (page === 'stats') await loadStats();
  }),
);

for (const item of document.querySelectorAll('.nav-item')) {
  item.addEventListener('click', () => navigate(item.dataset.page));
}

$('fallback-toggle').addEventListener('change', async (event) => {
  const toggle = event.currentTarget;
  toggle.disabled = true;
  let result = null;
  try {
    result = await saveConfig({ 'fallback-enabled': toggle.checked });
  } catch (error) {
    toggle.checked = fallbackOn();
    notify(error.message, true);
  }
  if (result) {
    try {
      notify(result.restartRequired ? SAVED_RESTART_MESSAGE : SAVED_MESSAGE);
      await refresh(false);
      syncFallbackField();
    } catch (error) {
      notify(error.message, true);
    }
  }
  toggle.disabled = false;
});

$('add-rule').addEventListener('click', () => {
  const row = ruleRow();
  $('rules').append(row);
  syncRules();
  row.querySelector('.rule-key').focus();
});

$('routing-form').addEventListener('submit', (event) => {
  event.preventDefault();
  withBusy(event.submitter, async () => {
    const rules = collectRules();
    await saveAndReload({
      ...rules,
      'default-server': poolValue($('default-servers').value),
      'fallback-servers': poolValue($('fallback-servers').value),
    });
  });
});

$('check-form').addEventListener('submit', (event) => {
  event.preventDefault();
  $('check-error').textContent = '';
  withBusy(
    event.submitter,
    async () => {
      const result = await api('/api/check-address', {
        method: 'POST',
        body: JSON.stringify({ address: $('check-address').value.trim() }),
      });
      renderCheck(result);
    },
    (failure) => {
      $('check-error').textContent = failure.message;
      $('check-result-section').hidden = true;
    },
  );
});

$('simulate-form').addEventListener('submit', (event) => {
  event.preventDefault();
  $('sim-error').textContent = '';
  withBusy(
    event.submitter,
    async () => {
      const result = await api('/api/simulate', {
        method: 'POST',
        body: JSON.stringify({ ip: $('sim-ip').value.trim() }),
      });
      renderSimulation(result);
    },
    (failure) => {
      $('sim-error').textContent = failure.message;
      $('sim-output').hidden = true;
    },
  );
});

$('settings-form').addEventListener('submit', (event) => {
  event.preventDefault();
  withBusy(event.submitter, async () => {
    const values = {};
    for (const input of $('settings-fields').querySelectorAll('[name]')) {
      values[input.name] = readField(input);
    }
    await saveAndReload(values);
  });
});

for (const input of document.querySelectorAll('input[name="stats-range"]')) {
  input.addEventListener('change', () => loadStats());
}

window.addEventListener('resize', () => {
  clearTimeout(chartTimer);
  chartTimer = setTimeout(() => {
    if (page === 'stats' && statsData && hasTraffic(statsData)) drawChart(statsData.days || []);
  }, 150);
});

$('log-search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    logOffset = 0;
    loadLogs().catch((error) => notify(error.message, true));
  }, SEARCH_DELAY_MS);
});

$('load-logs').addEventListener('click', () => {
  logOffset = 0;
  loadLogs().catch((error) => notify(error.message, true));
});

$('prev-logs').addEventListener('click', () => {
  logOffset = Math.max(0, logOffset - PAGE_SIZE);
  loadLogs().catch((error) => notify(error.message, true));
});

$('next-logs').addEventListener('click', () => {
  logOffset += PAGE_SIZE;
  loadLogs().catch((error) => notify(error.message, true));
});

$('export').addEventListener('click', (event) => withBusy(event.currentTarget, exportLogs));

/* ---------- Startup ---------- */

$('log-date').value = todayIso();
navigate('overview');
refresh().catch(() => showLogin());

setInterval(() => {
  if (state && !document.hidden) {
    refresh().catch((error) => {
      if (state) notify(error.message, true);
    });
  }
}, REFRESH_MS);
