/* iSMS GW web panel — vanilla JS, no external dependencies (strict-CSP compatible). */
(() => {
'use strict';

// ---------------------------------------------------------------- storage, language, theme
const root = document.documentElement;
const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } },
};

let lang = store.get('isms.lang');
if (lang !== 'ru' && lang !== 'en') lang = 'en'; // English by default
root.lang = lang;
/** i18n: T('русский текст', 'English text') — picks the string for the current language. */
const T = (ru, en) => lang === 'en' ? en : ru;
const locale = () => lang === 'ru' ? 'ru-RU' : 'en-US';
/** Russian plural: plural(n, 'часть', 'части', 'частей'). */
const plural = (n, one, few, many) => { const a = Math.abs(n) % 100, b = a % 10; return a > 10 && a < 20 ? many : b === 1 ? one : b >= 2 && b <= 4 ? few : many; };

// Static strings from index.html (data-i18n / data-i18n-aria)
const STATIC = {
  'login.subtitle': ['Панель управления SMS-шлюзом', 'SMS gateway control panel'],
  'login.username': ['Логин', 'Username'],
  'login.password': ['Пароль', 'Password'],
  'login.submit': ['Войти', 'Sign in'],
  'login.hint': ['Временный пароль администратора показан в приложении на телефоне. Учётные записи пользователей создаёт администратор.', 'The administrator’s temporary password is shown in the app on the phone. User accounts are created by the administrator.'],
  'me.profile': ['Мой профиль', 'My profile'],
  'logout': ['Выйти', 'Sign out'],
  'menu': ['Меню', 'Menu'],
};
const THEMES = ['light', 'dark', 'system'];
let themePref = store.get('isms.theme');
if (!THEMES.includes(themePref)) themePref = 'system';
const mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
const resolveTheme = () => themePref === 'system' ? (mq && !mq.matches ? 'light' : 'dark') : themePref;
let themeAnimTimer = null;
function applyTheme(animate) {
  const t = resolveTheme();
  if (animate) { root.classList.add('theming'); clearTimeout(themeAnimTimer); themeAnimTimer = setTimeout(() => root.classList.remove('theming'), 420); }
  root.setAttribute('data-theme', t); root.setAttribute('data-theme-pref', themePref);
  const m = document.querySelector('meta[name="color-scheme"]'); if (m) m.setAttribute('content', t);
  syncSwitchers();
}
if (mq) {
  const onSys = () => { if (themePref === 'system') applyTheme(true); };
  if (mq.addEventListener) mq.addEventListener('change', onSys); else if (mq.addListener) mq.addListener(onSys);
}

// ---------------------------------------------------------------- icons
const P = {
  dashboard:'<rect x="3" y="3" width="7" height="9" rx="2"/><rect x="14" y="3" width="7" height="5" rx="2"/><rect x="14" y="12" width="7" height="9" rx="2"/><rect x="3" y="16" width="7" height="5" rx="2"/>',
  inbox:'<path d="M22 12h-6l-2 3h-4l-2-3H2"/><path d="M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z"/>',
  send:'<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
  sim:'<path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z"/><rect x="8" y="11" width="8" height="7" rx="1"/>',
  key:'<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
  hook:'<path d="M18 16.98h-5.99c-1.1 0-1.95.94-2.48 1.9A4 4 0 0 1 2 17c.01-.7.2-1.4.57-2"/><path d="m6 17 3.13-5.78c.53-.97.1-2.18-.5-3.1a4 4 0 1 1 6.89-4.06"/><path d="m12 6 3.13 5.73C15.66 12.7 16.9 13 18 13a4 4 0 0 1 0 8"/>',
  cloud:'<path d="M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"/><path d="m9 14 2 2 4-4"/>',
  globe:'<circle cx="12" cy="12" r="10"/><path d="M2 12h20"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/>',
  shield:'<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
  list:'<path d="M3 12h.01M3 18h.01M3 6h.01M8 12h13M8 18h13M8 6h13"/>',
  settings:'<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
  book:'<path d="M4 19.5v-15A2.5 2.5 0 0 1 6.5 2H20v20H6.5a2.5 2.5 0 0 1 0-5H20"/>',
  logout:'<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>',
  menu:'<path d="M4 6h16M4 12h16M4 18h16"/>',
  in:'<path d="M17 7 7 17"/><path d="M17 17H7V7"/>',
  out:'<path d="M7 17 17 7"/><path d="M7 7h10v10"/>',
  clock:'<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
  alert:'<circle cx="12" cy="12" r="10"/><path d="M12 8v4M12 16h.01"/>',
  copy:'<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  trash:'<path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  retry:'<path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M8 16H3v5"/>',
  plus:'<path d="M12 5v14M5 12h14"/>',
  battery:'<rect x="2" y="7" width="16" height="10" rx="2"/><path d="M22 11v2"/>',
  wifi:'<path d="M12 20h.01M2 8.82a15 15 0 0 1 20 0M5 12.86a10 10 0 0 1 14 0M8.5 16.43a5 5 0 0 1 7 0"/>',
  lock:'<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  info:'<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
  bolt:'<path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/>',
  ban:'<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
  check:'<path d="M20 6 9 17l-5-5"/>',
  tg:'<path d="M21.5 3.5 2.8 10.7c-1 .4-1 1.8.1 2.1l4.6 1.4 1.8 5.6c.3.9 1.4 1.1 2 .4l2.6-2.7 4.8 3.5c.8.6 1.9.1 2.1-.9L23 4.9c.2-1-.6-1.8-1.5-1.4z"/><path d="m7.5 14.2 9.5-6.7"/>',
  sun:'<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41"/>',
  moon:'<path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z"/>',
  auto:'<rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/><path d="M12 6.5a3.5 3.5 0 0 0 0 7z" fill="currentColor"/>',
  users:'<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
  user:'<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
  edit:'<path d="M12 20h9"/><path d="M16.38 3.62a2.12 2.12 0 0 1 3 3L7.37 18.64a2 2 0 0 1-.86.5l-2.87.84a.5.5 0 0 1-.62-.62l.84-2.87a2 2 0 0 1 .5-.86z"/>',
  eye:'<path d="M2.06 12.35a1 1 0 0 1 0-.7 10.75 10.75 0 0 1 19.88 0 1 1 0 0 1 0 .7 10.75 10.75 0 0 1-19.88 0"/><circle cx="12" cy="12" r="3"/>',
  wand:'<path d="m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72"/><path d="m14 7 3 3M5 6v4M19 14v4M10 2v2M7 8H3M21 16h-4M11 3H9"/>',
  esim:'<rect x="5" y="5" width="14" height="14" rx="2"/><path d="M9 2v3M15 2v3M9 19v3M15 19v3M2 9h3M2 15h3M19 9h3M19 15h3"/><rect x="9" y="9" width="6" height="6" rx="1"/>',
  forget:'<path d="M3 3l18 18"/><path d="M14 2H7a2 2 0 0 0-2 2v11M19 15V7l-5-5M19 19a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2"/>',
  arrow:'<path d="M5 12h14M12 5l7 7-7 7"/>',
  phone:'<path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
  phoneIn:'<path d="M16 2v6h6M22 2l-6 6"/><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
  phoneOut:'<path d="M22 8V2h-6M16 8l6-6"/><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
  phoneMissed:'<path d="m16 2 6 6M22 2l-6 6"/><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.13.96.36 1.9.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.91.34 1.85.57 2.81.7A2 2 0 0 1 22 16.92z"/>',
  hash:'<path d="M4 9h16M4 15h16M10 3 8 21M16 3l-2 18"/>',
  wallet:'<path d="M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1"/><path d="M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4"/>',
  db:'<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14a9 3 0 0 0 18 0V5"/><path d="M3 12a9 3 0 0 0 18 0"/>',
  download:'<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m7 10 5 5 5-5M12 15V3"/>',
  upload:'<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="m17 8-5-5-5 5M12 3v12"/>',
  chipMem:'<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M9 9h6v6H9z"/><path d="M9 1v3M15 1v3M9 20v3M15 20v3M20 9h3M20 14h3M1 9h3M1 14h3"/>',
  palette:'<circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.93 0 1.65-.75 1.65-1.69 0-.44-.18-.84-.44-1.13-.29-.29-.44-.65-.44-1.13a1.64 1.64 0 0 1 1.67-1.67h2c3.05 0 5.56-2.5 5.56-5.55C21.97 6.01 17.46 2 12 2z"/>',
};
const icon = (n) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${P[n] || ''}</svg>`;
const hydrateIcons = (r = document) => r.querySelectorAll('i[data-icon]').forEach(el => { if (!el.firstChild) el.innerHTML = icon(el.dataset.icon); });

// Flags as inline SVG (flag emoji render as letters on Windows)
const usStars = (() => { let s = ''; for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) s += `<circle cx="${1.9 + c * 3.1 + (r % 2) * 1.55}" cy="${1.9 + r * 3.1}" r=".75"/>`; return s; })();
const FLAGS = {
  en: `<svg viewBox="0 0 26 26" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><rect width="26" height="26" fill="#B22234"/>${[1, 3, 5, 7, 9, 11].map(i => `<rect y="${i * 2}" width="26" height="2" fill="#fff"/>`).join('')}<rect width="14" height="14" fill="#3C3B6E"/><g fill="#fff">${usStars}</g></svg>`,
  ru: `<svg viewBox="0 0 26 26" preserveAspectRatio="xMidYMid slice" aria-hidden="true"><rect width="26" height="8.67" fill="#fff"/><rect y="8.67" width="26" height="8.67" fill="#0039A6"/><rect y="17.33" width="26" height="8.67" fill="#D52B1E"/></svg>`,
};
const LANG_NAMES = { en: 'English', ru: 'Русский' };
const themeName = (t) => ({ light: T('Светлая', 'Light'), dark: T('Тёмная', 'Dark'), system: T('Как в системе', 'System') })[t];
const themeIcon = { light: 'sun', dark: 'moon', system: 'auto' };

const langSwitch = () => `<div class="lang-seg" role="group" aria-label="${esc(T('Язык', 'Language'))}">${['en', 'ru'].map(l =>
  `<button type="button" class="tt${lang === l ? ' on' : ''}" data-lang="${l}" aria-label="${LANG_NAMES[l]}" aria-pressed="${lang === l}"><span class="flag">${FLAGS[l]}</span></button>`).join('')}</div>`;
const themeSwitch = () => `<div class="theme-seg" role="radiogroup" aria-label="${esc(T('Тема', 'Theme'))}" style="--i:${THEMES.indexOf(themePref)}"><span class="ind"></span>${THEMES.map(t =>
  `<button type="button" role="radio" class="tt${themePref === t ? ' on' : ''}" data-theme-set="${t}" aria-checked="${themePref === t}" aria-label="${esc(themeName(t))}"><i data-icon="${themeIcon[t]}"></i></button>`).join('')}</div>`;

/** Updates every switcher instance in place (keeps the sliding animation). */
function syncSwitchers() {
  document.querySelectorAll('.theme-seg').forEach(seg => {
    seg.style.setProperty('--i', THEMES.indexOf(themePref));
    seg.setAttribute('aria-label', T('Тема', 'Theme'));
    seg.querySelectorAll('[data-theme-set]').forEach(b => {
      const on = b.dataset.themeSet === themePref;
      b.classList.toggle('on', on); b.setAttribute('aria-checked', on); b.setAttribute('aria-label', themeName(b.dataset.themeSet));
    });
  });
  document.querySelectorAll('.lang-seg').forEach(seg => {
    seg.setAttribute('aria-label', T('Язык', 'Language'));
    seg.querySelectorAll('[data-lang]').forEach(b => { const on = b.dataset.lang === lang; b.classList.toggle('on', on); b.setAttribute('aria-pressed', on); });
  });
}

const footerHtml = () => `© 2026 ${T('Разработано', 'Developed by')}&nbsp;<a href="https://www.ismarty.pro" target="_blank" rel="noopener noreferrer">iSmartyPro</a>`;

function applyStatic() {
  document.querySelectorAll('[data-i18n]').forEach(el => { const s = STATIC[el.dataset.i18n]; if (s) el.textContent = T(s[0], s[1]); });
  document.querySelectorAll('[data-i18n-aria]').forEach(el => { const s = STATIC[el.dataset.i18nAria]; if (s) el.setAttribute('aria-label', T(s[0], s[1])); });
  document.querySelectorAll('[data-footer]').forEach(el => { el.innerHTML = footerHtml(); });
  document.querySelectorAll('[data-switchers]').forEach(el => { if (!el.firstChild) { el.innerHTML = langSwitch() + themeSwitch(); hydrateIcons(el); } });
  if (!$('#login').classList.contains('hidden')) document.title = 'iSMS GW — ' + T('вход', 'sign in');
  syncSwitchers();
}

function setLang(l) {
  if (l === lang || !LANG_NAMES[l]) return;
  lang = l; store.set('isms.lang', l); root.lang = l;
  applyStatic();
  $('#loginError').textContent = '';
  if (!$('#app').classList.contains('hidden')) { closeModal(); route(); }
}
function setTheme(t) {
  if (!THEMES.includes(t)) return;
  themePref = t; store.set('isms.theme', t); applyTheme(true);
}

// ---------------------------------------------------------------- utils
const $ = (s, r = document) => r.querySelector(s);
const esc = (v) => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmtDate = (ts) => ts ? new Date(ts).toLocaleString(locale(), {day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit', second:'2-digit'}) : '—';
const ago = (ts) => {
  if (!ts) return T('никогда', 'never');
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 60) return T('только что', 'just now');
  if (s < 3600) return Math.floor(s/60) + T(' мин назад', ' min ago');
  if (s < 86400) return Math.floor(s/3600) + T(' ч назад', ' h ago');
  return Math.floor(s/86400) + T(' дн назад', ' d ago');
};
const uptime = (s) => { const d = Math.floor(s/86400), h = Math.floor(s%86400/3600), m = Math.floor(s%3600/60); return (d ? d + T('д ', 'd ') : '') + h + T('ч ', 'h ') + m + T('м', 'm'); };

function toast(msg, err = false) {
  const t = document.createElement('div'); t.className = 'toast' + (err ? ' err' : ''); t.textContent = msg;
  $('#toasts').appendChild(t); setTimeout(() => t.remove(), 4200);
}

async function api(method, path, body) {
  const opt = { method, credentials: 'same-origin', headers: { 'X-ISMS-CSRF': '1', 'X-ISMS-Lang': lang } };
  if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  let r;
  try { r = await fetch('/admin/api/' + path, opt); } catch (e) { throw new Error(T('Нет связи с телефоном', 'No connection to the phone')); }
  const j = await r.json().catch(() => ({}));
  if (r.status === 401 && path !== 'login') { showLogin(); throw new Error(j.error || T('Требуется вход', 'Sign-in required')); }
  if (!r.ok || j.ok === false) { const e = new Error(j.error || ('HTTP ' + r.status)); e.status = r.status; throw e; }
  return j;
}
const GET = (p) => api('GET', p), POST = (p, b) => api('POST', p, b ?? {}), PUT = (p, b) => api('PUT', p, b), DEL = (p) => api('DELETE', p);

function copyText(t) {
  const done = () => toast(T('Скопировано', 'Copied'));
  if (navigator.clipboard && window.isSecureContext) navigator.clipboard.writeText(t).then(done);
  else { const a = document.createElement('textarea'); a.value = t; document.body.appendChild(a); a.select(); document.execCommand('copy'); a.remove(); done(); }
}

const STATUS = () => ({
  queued: [T('В очереди', 'Queued'), 'am'], sending: [T('Отправка', 'Sending'), 'sky'], sent: [T('Отправлено', 'Sent'), 'in'],
  delivered: [T('Доставлено', 'Delivered'), 'em'], failed: [T('Ошибка', 'Failed'), 'red'], received: [T('Получено', 'Received'), 'em'],
});
const statusPill = (s) => { const [l, c] = STATUS()[s] || [s, 'mu']; return `<span class="pill ${c}">${esc(l)}</span>`; };
const sw = (id, on, attrs = '') => `<label class="switch"><input type="checkbox" id="${id}" ${on ? 'checked' : ''} ${attrs}><span></span></label>`;

// ---------------------------------------------------------------- current user, roles, SIM helpers
let ME = null, MY_SIMS = [];
const isAdmin = () => !!ME && ME.role === 'admin';
async function loadMe() { const r = await GET('me'); ME = r.user || null; MY_SIMS = Array.isArray(r.sims) ? r.sims : []; return r; }

const HEX = /^#[0-9a-f]{6}$/i;
const PALETTE = ['#34D399', '#818CF8', '#38BDF8', '#FBBF24', '#F472B6', '#A78BFA', '#FB923C', '#2DD4BF', '#111827'];
const hash = (s) => { let h = 0; for (const ch of String(s ?? '')) h = (h * 31 + ch.codePointAt(0)) | 0; return Math.abs(h); };
/** Validated SIM color (server data goes into style attributes — never trust it blindly). */
const simColor = (s) => (s && HEX.test(s.color || '')) ? s.color : PALETTE[hash(s && s.simId) % PALETTE.length];
const rgbOf = (hex) => [1, 3, 5].map(i => parseInt(hex.slice(i, i + 2), 16));
const shade = (hex, f) => '#' + rgbOf(hex).map(v => Math.round(f < 0 ? v * (1 + f) : v + (255 - v) * f).toString(16).padStart(2, '0')).join('');
const luminance = (hex) => { const [r, g, b] = rgbOf(hex).map(v => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }); return .2126 * r + .7152 * g + .0722 * b; };
const simName = (s) => (s && (s.label || s.displayName || s.carrier)) || (s && s.slot ? 'SIM ' + s.slot : 'SIM');
const simSub = (s) => [s.carrier || s.displayName, s.number].filter(Boolean).join(' • ');
const simById = (list, id) => (list || []).find(s => String(s.simId) === String(id));
const slotBadge = (s) => s.active && s.slot ? `<span class="badge">SIM ${esc(s.slot)}</span>` : `<span class="badge off">${T('Неактивна', 'Inactive')}</span>`;
const kindBadge = (s) => s.embedded ? `<span class="badge esim"><i data-icon="esim"></i>eSIM</span>` : `<span class="badge"><i data-icon="sim"></i>${T('Физическая', 'Physical')}</span>`;
/** Small colored SIM chip. */
const simChip = (s) => `<span class="sim-chip${s.active === false ? ' off' : ''}" style="--c:${simColor(s)}" title="${esc(simSub(s) || simName(s))}"><i class="dot"></i>${esc(simName(s))}</span>`;
const simChipsFor = (ids, all, sims) => {
  if (all) return `<span class="sim-chip all"><i data-icon="sim"></i>${T('Все SIM-карты', 'All SIM cards')}</span>`;
  const list = (ids || []).map(id => simById(sims, id) || { simId: id, label: '#' + id, active: false });
  return list.length ? list.map(simChip).join('') : `<span class="muted small">${T('Нет доступа к SIM', 'No SIM access')}</span>`;
};

const initials = (u) => {
  const n = String((u && (u.displayName || u.username)) || '?').trim();
  const p = n.split(/[\s._-]+/).filter(Boolean);
  return ((p.length > 1 ? p[0][0] + p[1][0] : n.slice(0, 2)) || '?').toUpperCase();
};
const avatar = (u, cls = '') => { const h = hash(u && (u.id || u.username)); return `<span class="avatar ${cls}" style="--a:${PALETTE[h % 8]};--b:${PALETTE[(h + 3) % 8]}" aria-hidden="true">${esc(initials(u))}</span>`; };
const roleName = (u) => u && u.role === 'admin' ? T('Администратор', 'Administrator') : T('Пользователь', 'User');
const roleBadge = (u) => `<span class="role ${u.role === 'admin' ? 'admin' : ''}">${u.role === 'admin' ? '<i data-icon="shield"></i>' : '<i data-icon="user"></i>'}${esc(roleName(u))}</span>`;
const PERMS = () => [
  ['canSend', T('Отправка', 'Send'), 'send', T('Отправка SMS из панели и через API', 'Send SMS from the panel and via the API')],
  ['canRead', T('Чтение', 'Read'), 'inbox', T('Просмотр входящих SMS на своих SIM-картах', 'View incoming SMS on their SIM cards')],
  ['canUseApi', 'API', 'key', T('Создание собственных API-ключей', 'Create their own API keys')],
  ['canWeb', T('Веб-панель', 'Web panel'), 'dashboard', T('Вход в эту панель управления', 'Sign in to this control panel')],
  ['canUssd', 'USSD', 'hash', T('USSD-запросы и проверка баланса на своих SIM', 'USSD requests and balance checks on their SIMs')],
  ['canCall', T('Звонки', 'Calls'), 'phone', T('Журнал звонков и набор номера с телефона', 'Call log and dialing from the phone')],
];
const permChips = (u) => PERMS().map(([k, l, ic]) => `<span class="perm ${u[k] ? 'on' : 'off'}" title="${esc(u[k] ? T('Разрешено', 'Allowed') : T('Запрещено', 'Denied'))}"><i data-icon="${u[k] ? ic : 'ban'}"></i>${esc(l)}</span>`).join('');

/** Selectable SIM cards. `allowed` — SIMs that may be picked (others are hidden). */
const simPicker = (sims, selected, id = 'simPick') => {
  const sel = new Set((selected || []).map(String));
  if (!sims.length) return `<div class="muted small" id="${id}">${T('SIM-карты не обнаружены', 'No SIM cards detected')}</div>`;
  return `<div class="sim-picker" id="${id}" role="group">${sims.map(s => `<button type="button" class="sim-pick${sel.has(String(s.simId)) ? ' on' : ''}${s.active ? '' : ' inactive'}" data-pick="${esc(s.simId)}" aria-pressed="${sel.has(String(s.simId))}" style="--c:${simColor(s)}">
    <i class="dot"></i><span class="t"><b>${esc(simName(s))}</b><small>${esc(simSub(s) || '—')}</small><span class="badges">${slotBadge(s)}${s.embedded ? `<span class="badge esim">eSIM</span>` : ''}${s.active ? `<span class="badge ok">${T('Активна', 'Active')}</span>` : ''}</span></span>
    <span class="check"><i data-icon="check"></i></span></button>`).join('')}</div>`;
};
const bindPicker = (root, id = 'simPick') => {
  const p = $('#' + id, root); if (!p || !p.classList.contains('sim-picker')) return;
  p.addEventListener('click', (e) => { const b = e.target.closest('[data-pick]'); if (!b || p.classList.contains('disabled')) return; const on = !b.classList.contains('on'); b.classList.toggle('on', on); b.setAttribute('aria-pressed', on); p.dispatchEvent(new Event('change')); });
};
const pickedSims = (root, id = 'simPick') => [...root.querySelectorAll(`#${id} [data-pick].on`)].map(b => +b.dataset.pick);
const toggleRow = (id, title, desc, on, attrs = '') => `<div class="opt"><div class="t"><b>${esc(title)}</b>${desc ? `<small>${esc(desc)}</small>` : ''}</div>${sw(id, on, attrs)}</div>`;

const USERNAME_RE = /^[A-Za-z0-9._-]{3,32}$/;
function genPassword() {
  const a = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const r = new Uint32Array(14); crypto.getRandomValues(r);
  return [...r].map(x => a[x % a.length]).join('');
}
/** Password input with show/hide and generate buttons. */
const pwdField = (id, label, hint) => `<label class="field"><span>${esc(label)}</span><div class="pwd"><input id="${id}" type="password" autocomplete="new-password" minlength="8" spellcheck="false"><button type="button" class="icon-btn" data-pwd-eye="${id}" title="${esc(T('Показать', 'Show'))}"><i data-icon="eye"></i></button><button type="button" class="icon-btn" data-pwd-gen="${id}" title="${esc(T('Сгенерировать', 'Generate'))}"><i data-icon="wand"></i></button></div>${hint ? `<small>${esc(hint)}</small>` : ''}</label>`;
const bindPwd = (root) => {
  root.querySelectorAll('[data-pwd-eye]').forEach(b => b.onclick = () => { const i = $('#' + b.dataset.pwdEye, root); i.type = i.type === 'password' ? 'text' : 'password'; });
  root.querySelectorAll('[data-pwd-gen]').forEach(b => b.onclick = () => { const i = $('#' + b.dataset.pwdGen, root); i.value = genPassword(); i.type = 'text'; i.dispatchEvent(new Event('input')); });
};
const fieldErr = (root, id, msg) => { const el = $('#' + id, root); if (el) { el.textContent = msg || ''; el.classList.toggle('show', !!msg); } return !msg; };

// SMS part counting (GSM-7 / UCS-2)
const GSM = '@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !"#¤%&\'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà';
const GSM_EXT = '^{}\\[~]|€';
function smsParts(t) {
  let gsm = true, len = 0;
  for (const ch of t) { if (GSM.includes(ch)) len++; else if (GSM_EXT.includes(ch)) len += 2; else { gsm = false; break; } }
  if (!gsm) len = [...t].length;
  const single = gsm ? 160 : 70, multi = gsm ? 153 : 67;
  const parts = len === 0 ? 0 : len <= single ? 1 : Math.ceil(len / multi);
  return { len, parts, enc: gsm ? 'GSM-7' : 'UCS-2 (Unicode)', left: parts <= 1 ? single - len : parts * multi - len };
}

// ---------------------------------------------------------------- modal
function modal(html, onMount, cls = '') {
  $('#modalCard').className = 'modal-card' + (cls ? ' ' + cls : '');
  $('#modalCard').innerHTML = html; $('#modal').classList.remove('hidden'); hydrateIcons($('#modalCard'));
  onMount && onMount($('#modalCard'));
}
const closeModal = () => $('#modal').classList.add('hidden');
$('#modal').addEventListener('click', (e) => { if (e.target.id === 'modal' || e.target.closest('[data-action="closeModal"]')) closeModal(); });
function confirmBox(title, text, okText) {
  okText = okText || T('Удалить', 'Delete');
  return new Promise(res => {
    modal(`<h3>${esc(title)}</h3><p class="muted">${esc(text)}</p><div class="modal-actions"><button class="btn" data-action="closeModal">${T('Отмена', 'Cancel')}</button><button class="btn danger" id="cOk">${esc(okText)}</button></div>`,
      (m) => { $('#cOk', m).onclick = () => { closeModal(); res(true); }; });
  });
}

// ---------------------------------------------------------------- routing
/** Navigation is built from the signed-in user's role and permissions (the server enforces the same rules). */
const NAV = () => {
  const a = isAdmin(), u = ME || {};
  const n = [[T('Мониторинг', 'Monitoring')], ['dashboard', T('Обзор', 'Overview'), 'dashboard'], ['messages', T('Сообщения', 'Messages'), 'inbox']];
  if (a || u.canSend) n.push(['send', T('Отправить SMS', 'Send SMS'), 'send']);
  if (a || u.canUssd) n.push(['ussd', 'USSD', 'hash']);
  if (a || u.canCall) n.push(['calls', T('Звонки', 'Calls'), 'phone']);
  n.push([a ? T('Конфигурация', 'Configuration') : T('Доступ', 'Access')], ['sims', T('SIM-карты', 'SIM cards'), 'sim']);
  if (a || u.canUseApi) n.push(['keys', T('API-ключи', 'API keys'), 'key']);
  if (a) n.push(['users', T('Пользователи', 'Users'), 'users'], ['webhooks', T('Вебхуки', 'Webhooks'), 'hook'], ['remote', T('SMS-сервер', 'SMS server'), 'cloud'],
    ['telegram', T('Telegram-бот', 'Telegram bot'), 'tg'], ['destinations', T('Фильтр направлений', 'Destination filter'), 'globe'], ['firewall', T('Файрвол', 'Firewall'), 'shield']);
  n.push([T('Система', 'System')]);
  if (a) n.push(['logs', T('Журнал', 'Log'), 'list'], ['settings', T('Настройки', 'Settings'), 'settings']);
  n.push(['profile', T('Профиль', 'Profile'), 'user'], ['docs', T('API документация', 'API docs'), 'book']);
  return n;
};
let timer = null, current = '';

function renderNav() {
  $('#nav').innerHTML = NAV().map(n => n.length === 1
    ? `<div class="nav-group">${esc(n[0])}</div>`
    : `<a class="nav-item ${current === n[0] ? 'active' : ''}" href="#/${n[0]}"><i data-icon="${n[2]}"></i>${esc(n[1])}</a>`).join('');
  hydrateIcons($('#nav'));
  const mc = $('#meChip');
  if (mc) {
    mc.classList.toggle('hidden', !ME);
    if (ME) {
      mc.innerHTML = `${avatar(ME, 'sm')}<span class="t"><b>${esc(ME.displayName || ME.username)}</b><small>@${esc(ME.username)} • ${esc(roleName(ME))}</small></span>`;
      mc.classList.toggle('active', current === 'profile');
      mc.setAttribute('aria-label', T('Мой профиль', 'My profile'));
    }
  }
}

async function route() {
  const page = (location.hash.replace(/^#\//, '') || 'dashboard').split('?')[0];
  const nav = NAV();
  const def = nav.find(n => n.length > 1 && n[0] === page) || nav[1];
  current = def[0]; clearInterval(timer); timer = null; closeModal();
  renderNav(); $('#pageTitle').textContent = def[1]; document.title = def[1] + ' — iSMS GW';
  if (current !== 'dashboard') $('#topStatus').innerHTML = '';
  $('#sidebar').classList.remove('open'); $('#scrim').classList.remove('show');
  const view = $('#view');
  view.innerHTML = `<div class="muted">${T('Загрузка…', 'Loading…')}</div>`;
  try { await PAGES[current](view); hydrateIcons(view); }
  catch (e) { view.innerHTML = `<div class="callout"><i data-icon="alert"></i><div>${esc(e.message)}</div></div>`; hydrateIcons(view); }
}

// ---------------------------------------------------------------- pages
const PAGES = {};

PAGES.dashboard = async (v) => {
  const draw = async () => {
    const d = await GET('dashboard');
    if (d.me) { const changed = !ME || ME.role !== d.me.role; ME = d.me; if (changed) renderNav(); }
    const adm = isAdmin();
    $('#brandDevice').textContent = d.device.name;
    const s = d.stats, dev = d.device;
    const max = Math.max(1, ...d.hourly.map(h => h[0] + h[1]));
    const now = new Date();
    const bars = d.hourly.map((h, i) => {
      const hr = new Date(now.getTime() - (23 - i) * 3600e3).getHours();
      return `<div class="b" data-tip="${hr}:00 — ↑${h[0]} ↓${h[1]}"><span class="o" style="height:${h[0]/max*100}%"></span><span class="i" style="height:${h[1]/max*100}%"></span></div>`;
    }).join('');
    const remotePill = { connected: [T('Онлайн', 'Online'),'em'], connecting: [T('Подключение', 'Connecting'),'am'], disconnected: [T('Переподключение', 'Reconnecting'),'am'], disabled: [d.remote.enabled ? T('Ожидание', 'Waiting') : T('Не настроен', 'Not configured'),'mu'] }[d.remote.state] || ['—','mu'];
    $('#topStatus').innerHTML = `<span class="pill ${dev.defaultSmsApp ? 'em' : 'red'}">${dev.defaultSmsApp ? T('Шлюз активен', 'Gateway active') : T('Не SMS-приложение по умолч.', 'Not the default SMS app')}</span>`;
    const fwMode = { lan: T('только LAN', 'LAN only'), whitelist: T('белый список', 'whitelist'), all: T('открыт', 'open') }[d.firewallMode];
    v.innerHTML = `
      ${dev.defaultSmsApp ? '' : `<div class="callout"><i data-icon="alert"></i><div>${T('iSMS GW не назначен SMS-приложением по умолчанию на телефоне — отправка и приём SMS остановлены. Откройте приложение на телефоне.', 'iSMS GW is not set as the default SMS app on the phone — sending and receiving SMS are stopped. Open the app on the phone.')}</div></div>`}
      <div class="hero">
        <div class="row"><div class="grow"><div class="muted small">${T('УСТРОЙСТВО', 'DEVICE')}</div><h1>${esc(dev.name)}</h1><div class="muted">${esc(dev.model)} • Android ${esc(dev.android)} • iSMS GW ${esc(dev.app)}</div></div></div>
        <div class="meta">
          <span><i data-icon="battery"></i> ${T('Батарея', 'Battery')} <b>${dev.battery}%</b>${dev.charging ? ' ⚡' : ''}</span>
          <span><i data-icon="clock"></i> ${T('Аптайм', 'Uptime')} <b>${uptime(dev.uptimeSec)}</b></span>
          <span><i data-icon="sim"></i> ${adm ? T('SIM-карт', 'SIM cards') : T('Моих SIM', 'My SIMs')} <b>${d.sims.filter(x => x.active !== false).length}</b></span>
          ${adm ? `<span><i data-icon="shield"></i> ${T('Файрвол', 'Firewall')} <b>${esc(fwMode)}</b></span>
          <span><i data-icon="users"></i> ${T('Пользователей', 'Users')} <b>${esc(d.users ?? '—')}</b></span>` : ''}
          ${ME ? `<span><i data-icon="user"></i> ${T('Вы', 'You')} <b>${esc(ME.displayName || ME.username)}</b> (${esc(roleName(ME))})</span>` : ''}
        </div>
      </div>
      <div class="grid g4" style="margin-top:16px">
        ${stat(T('Отправлено сегодня', 'Sent today'), s.sentToday, 'out', 'tint-em')}
        ${stat(T('Получено сегодня', 'Received today'), s.receivedToday, 'in', 'tint-in')}
        ${stat(T('В очереди', 'Queued'), s.queued, 'clock', 'tint-am')}
        ${stat(T('Ошибок сегодня', 'Failed today'), s.failedToday, 'alert', 'tint-red')}
      </div>
      <div class="grid ${adm ? 'g3 dash-split' : ''}" style="margin-top:16px">
        <div class="card"><div class="card-head"><h3>${T('Активность за 24 часа', 'Activity, last 24 hours')}</h3><div class="right legend"><span style="--c:var(--em)">${T('Исходящие', 'Outgoing')}</span><span style="--c:var(--in)">${T('Входящие', 'Incoming')}</span></div></div><div class="chart">${bars}</div></div>
        ${adm ? `<div class="card"><h3>${T('Каналы', 'Channels')}</h3>
          <div class="opt"><span class="stat"><span class="ico tint-sky"><i data-icon="wifi"></i></span></span><div class="t"><b>${T('Локальный API', 'Local API')}</b><small>${d.server.urls.map(esc).join('<br>') || '—'}</small></div></div>
          <div class="opt"><span class="stat"><span class="ico tint-in"><i data-icon="cloud"></i></span></span><div class="t"><b>${T('SMS-сервер', 'SMS server')}</b><small>${esc(d.remote.url || T('не задан', 'not set'))}</small></div><span class="pill ${remotePill[1]}">${remotePill[0]}</span></div>
          <div class="opt"><span class="stat"><span class="ico tint-sky"><i data-icon="tg"></i></span></span><div class="t"><b>${T('Telegram-бот', 'Telegram bot')}</b><small>${d.telegram.bot ? '@' + esc(d.telegram.bot) + ' • ' : ''}${T('чатов', 'chats')}: ${d.telegram.chats}</small></div><span class="pill ${d.telegram.state === 'online' ? 'em' : d.telegram.enabled ? 'am' : 'mu'}">${d.telegram.state === 'online' ? T('Онлайн', 'Online') : d.telegram.enabled ? T('Ожидание', 'Waiting') : T('Выкл', 'Off')}</span></div>
          <div class="opt"><span class="stat"><span class="ico tint-am"><i data-icon="hook"></i></span></span><div class="t"><b>${T('Вебхуки', 'Webhooks')}</b><small>${T('активных', 'active')}: ${d.webhooks}</small></div></div>
          <div class="opt"><span class="stat"><span class="ico tint-em"><i data-icon="key"></i></span></span><div class="t"><b>${T('API-ключи', 'API keys')}</b><small>${T('активных', 'active')}: ${d.apiKeys} • ${T('банов', 'bans')}: ${d.bans}</small></div></div>
        </div>` : ''}
      </div>
      <div class="grid g2" style="margin-top:16px">${d.sims.filter(x => x.active !== false).map(simMini(s)).join('') || `<div class="card muted">${adm ? T('Активные SIM-карты не обнаружены', 'No active SIM cards detected') : T('Вам пока не назначены SIM-карты — обратитесь к администратору.', 'No SIM cards are assigned to you yet — contact the administrator.')}</div>`}</div>
      <div class="card" style="margin-top:16px"><div class="card-head"><h3>${T('Последние сообщения', 'Recent messages')}</h3><div class="right"><a class="btn sm" href="#/messages">${T('Все сообщения', 'All messages')}</a></div></div>${msgTable(d.recent, false, { sims: d.sims })}</div>`;
    hydrateIcons(v);
  };
  await draw();
  timer = setInterval(() => draw().catch(() => {}), 5000);
};
const stat = (l, val, ic, tint) => `<div class="card stat"><span class="ico ${tint}"><i data-icon="${ic}"></i></span><div class="v">${val}</div><div class="l">${esc(l)}</div></div>`;
/** perSimToday is keyed by simId (JSON object → string keys). Returns null when stats are unavailable. */
const usageOf = (stats, sim) => { const m = stats && stats.perSimToday; if (!m || typeof m !== 'object') return null; return +m[String(sim.simId)] || 0; };
const usageBar = (used, lim) => used == null ? '' : `<div class="usage"><div class="row"><span class="muted small">${T('Отправлено сегодня', 'Sent today')}</span><b class="small" style="margin-left:auto">${used}${lim ? ' / ' + lim : ''}</b>${lim ? '' : `<span class="muted small">${T('без лимита', 'no limit')}</span>`}</div>
  ${lim ? `<div class="progress${used >= lim ? ' full' : used / lim > .8 ? ' warn' : ''}"><div style="width:${Math.min(100, used / lim * 100).toFixed(1)}%"></div></div>` : ''}</div>`;
/** Bank-card style SIM visual. Colors are validated by simColor(). */
const bankCard = (s, compact) => {
  const c = simColor(s), darkInk = luminance(c) > .4, black = luminance(c) < .05;
  // Black cards get a graphite metallic gradient instead of a darker shade of black.
  const c2 = black ? '#3a4356' : shade(c, darkInk ? -.3 : -.5);
  const slot = s.active !== false && s.slot ? 'SIM ' + esc(s.slot) : T('Неактивна', 'Inactive');
  // A SIM card on its plastic carrier ("рубашка"): punch-out outline with micro/nano break lines and the chip in the middle.
  return `<div class="bank${darkInk ? ' ink-dark' : ''}${black ? ' black' : ''}${s.active === false ? ' inactive' : ''}${compact ? ' compact' : ''}" style="--c:${c};--c2:${c2}">
    <div class="bank-head">
      <div class="bank-title"><div class="bank-label">${esc(simName(s))}</div><div class="bank-sub">${esc(s.carrier || s.displayName || '—')}${s.country ? ' • ' + esc(String(s.country).toUpperCase()) : ''}</div></div>
      <span class="bank-tags">${s.isDefault ? `<span class="bank-tag">${T('по умолчанию', 'default')}</span>` : ''}<span class="bank-tag">${s.embedded ? 'eSIM' : T('Физическая', 'Physical')}</span></span>
    </div>
    <div class="bank-body">
      <div class="sim-punch${s.embedded ? ' esim' : ''}" aria-hidden="true"><span class="bank-chip"></span>${s.embedded ? `<span class="esim-mark">${T('встроенная', 'embedded')}</span>` : ''}</div>
      <div class="bank-info">
        <div class="bank-num">${esc(s.number || T('номер не сообщён', 'number not provided'))}</div>
        <div class="bank-icc">ICCID •••• ${esc(s.iccTail || '····')}</div>
        <span class="bank-slot">${slot}</span>
      </div>
    </div>
    <div class="bank-brand"><span class="logo-mark"></span>iSMS GW</div>
  </div>`;
};
const simMini = (st) => (sim) => `<div class="card sim-dash">${bankCard(sim, true)}
  <div class="row" style="margin-top:14px"><span class="pill ${sim.sendEnabled ? 'em' : 'mu'}">${T('Отправка', 'Sending')}</span><span class="pill ${sim.receiveEnabled ? 'em' : 'mu'}">${T('Приём', 'Receiving')}</span></div>
  ${usageBar(usageOf(st, sim), sim.dailyLimit)}</div>`;

function msgTable(list, actions, ctx = {}) {
  if (!list.length) return `<div class="empty"><i data-icon="inbox"></i><div>${T('Сообщений пока нет', 'No messages yet')}</div></div>`;
  return `<div class="table-wrap"><table class="msg-table"><thead><tr><th></th><th>${T('Номер', 'Number')}</th><th>${T('Текст', 'Text')}</th><th>SIM</th><th>${T('Статус', 'Status')}</th><th>${T('Время', 'Time')}</th>${actions ? '<th></th>' : ''}</tr></thead><tbody>
  ${list.map(m => `<tr>
    <td class="c-dir"><span class="dir ${m.direction === 'in' ? 'tint-in' : 'tint-em'}" title="${m.direction === 'in' ? T('Входящее', 'Incoming') : T('Исходящее', 'Outgoing')}"><i data-icon="${m.direction === 'in' ? 'in' : 'out'}"></i></span></td>
    <td class="c-num mono">${esc(m.phone)}<div class="muted small">#${esc(m.id)}${m.source ? ' • ' + esc(m.source) : ''}${ctx.users && m.owner ? ' • @' + esc((ctx.users.find(u => u.id === m.owner) || { username: m.owner }).username) : ''}</div></td>
    <td class="c-text"><div class="msg-text">${esc(m.text)}</div>${m.error ? `<div class="small" style="color:var(--red)">${esc(m.error)}</div>` : ''}</td>
    <td class="c-sim">${(() => { const s = m.simId != null && m.simId >= 0 ? simById(ctx.sims, m.simId) : null; return s ? simChip(s) : m.sim ? 'SIM ' + esc(m.sim) : '—'; })()}</td>
    <td class="c-st">${statusPill(m.status)}${m.parts > 1 ? `<div class="muted small">${m.parts} ${T(plural(m.parts, 'часть', 'части', 'частей'), 'parts')}</div>` : ''}</td>
    <td class="c-time muted small">${fmtDate(m.createdAt)}</td>
    ${actions ? `<td class="c-act" style="white-space:nowrap">${m.direction === 'out' && (m.status === 'failed') && (isAdmin() || (ME && ME.canSend)) ? `<button class="icon-btn" title="${T('Повторить', 'Retry')}" data-retry="${esc(m.id)}"><i data-icon="retry"></i></button> ` : ''}<button class="icon-btn" title="${T('Удалить', 'Delete')}" data-del="${esc(m.id)}"><i data-icon="trash"></i></button></td>` : ''}
  </tr>`).join('')}</tbody></table></div>`;
}

PAGES.messages = async (v) => {
  const st = { direction: '', status: '', q: '', offset: 0, limit: 50 };
  const adm = isAdmin();
  const [sims, users] = await Promise.all([GET('sims').catch(() => []), adm ? GET('users').catch(() => null) : null]);
  const ctx = { sims, users: Array.isArray(users) ? users : null };
  v.innerHTML = `${!adm && ME && !ME.canRead ? `<div class="callout info"><i data-icon="info"></i><div>${T('У вас нет права чтения входящих — показаны только отправленные вами сообщения.', 'You do not have the read permission — only messages you sent are shown.')}</div></div>` : ''}<div class="card">
    <div class="row" style="margin-bottom:16px">
      <div class="seg" id="dirSeg"><button data-d="" class="on">${T('Все', 'All')}</button><button data-d="in">${T('Входящие', 'Incoming')}</button><button data-d="out">${T('Исходящие', 'Outgoing')}</button></div>
      <select id="stSel" style="width:auto"><option value="">${T('Любой статус', 'Any status')}</option>${Object.entries(STATUS()).map(([k, [l]]) => `<option value="${k}">${esc(l)}</option>`).join('')}</select>
      <input class="grow" id="qIn" placeholder="${T('Поиск по номеру или тексту', 'Search by number or text')}">
      ${adm ? `<button class="btn danger sm" id="clearBtn"><i data-icon="trash"></i>${T('Очистить', 'Clear')}</button>` : ''}
    </div>
    <div id="mt"></div><div class="pager" id="pager"></div></div>`;
  const load = async () => {
    const qs = new URLSearchParams({ limit: st.limit, offset: st.offset });
    if (st.direction) qs.set('direction', st.direction); if (st.status) qs.set('status', st.status); if (st.q) qs.set('q', st.q);
    const r = await GET('messages?' + qs);
    $('#mt').innerHTML = msgTable(r.messages, true, ctx);
    const page = Math.floor(st.offset / st.limit) + 1, pages = Math.max(1, Math.ceil(r.total / st.limit));
    $('#pager').innerHTML = `<span>${T('Всего', 'Total')}: ${r.total}</span><button class="btn sm" id="pp" ${page <= 1 ? 'disabled' : ''}>←</button><span>${page} / ${pages}</span><button class="btn sm" id="np" ${page >= pages ? 'disabled' : ''}>→</button>`;
    $('#pp').onclick = () => { st.offset -= st.limit; load(); };
    $('#np').onclick = () => { st.offset += st.limit; load(); };
    hydrateIcons(v);
  };
  $('#dirSeg').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; v.querySelectorAll('#dirSeg button').forEach(x => x.classList.toggle('on', x === b)); st.direction = b.dataset.d; st.offset = 0; load(); };
  $('#stSel').onchange = (e) => { st.status = e.target.value; st.offset = 0; load(); };
  let deb; $('#qIn').oninput = (e) => { clearTimeout(deb); deb = setTimeout(() => { st.q = e.target.value.trim(); st.offset = 0; load(); }, 300); };
  if ($('#clearBtn')) $('#clearBtn').onclick = async () => {
    if (!await confirmBox(T('Очистить историю?', 'Clear history?'), st.direction
      ? T('Будут удалены все сообщения выбранного направления из журнала шлюза.', 'All messages of the selected direction will be removed from the gateway log.')
      : T('Будет удалена вся история шлюза (системные SMS на телефоне не затрагиваются).', 'The entire gateway history will be deleted (system SMS on the phone are not affected).'), T('Очистить', 'Clear'))) return;
    await POST('messages/clear', st.direction ? { direction: st.direction } : {}); toast(T('История очищена', 'History cleared')); load();
  };
  $('#mt').onclick = async (e) => {
    const r = e.target.closest('[data-retry]'), d = e.target.closest('[data-del]');
    try {
      if (r) { await POST(`messages/${r.dataset.retry}/retry`); toast(T('Поставлено в очередь', 'Queued for sending')); load(); }
      if (d) {
        const id = d.dataset.del;
        modal(`<h3>${T('Удалить сообщение?', 'Delete message?')}</h3><p class="muted">${T('Сообщение #' + esc(id) + ' будет удалено из журнала шлюза.', 'Message #' + esc(id) + ' will be removed from the gateway log.')}</p>
          <label class="check-row"><input type="checkbox" id="delPhone"><span><b>${T('Удалить и из переписок телефона', 'Also delete from phone conversations')}</b><small>${T('SMS исчезнет из приложения сообщений на телефоне', 'The SMS disappears from the phone’s messaging app')}</small></span></label>
          <div class="modal-actions"><button class="btn" data-action="closeModal">${T('Отмена', 'Cancel')}</button><button class="btn danger" id="delOk"><i data-icon="trash"></i>${T('Удалить', 'Delete')}</button></div>`, (m) => {
          $('#delOk', m).onclick = async () => {
            const phone = $('#delPhone', m).checked; closeModal();
            try { const r = await DEL(`messages/${encodeURIComponent(id)}${phone ? '?phone=1' : ''}`); toast(r.deletedFromPhone ? T('Удалено из журнала и с телефона', 'Deleted from the log and the phone') : T('Сообщение удалено', 'Message deleted')); load(); }
            catch (err) { toast(err.message, true); }
          };
        });
      }
    } catch (err) { toast(err.message, true); }
  };
  await load();
  timer = setInterval(() => { if (!document.hidden && $('#modal').classList.contains('hidden')) load().catch(() => {}); }, 8000);
};

PAGES.send = async (v) => {
  if (!isAdmin() && ME && !ME.canSend) { v.innerHTML = `<div class="callout info"><i data-icon="lock"></i><div>${T('У вашей учётной записи нет права на отправку SMS. Обратитесь к администратору.', 'Your account does not have the permission to send SMS. Contact the administrator.')}</div></div>`; return; }
  const sims = await GET('sims');
  const usable = (s) => s.active !== false && s.sendEnabled;
  const reason = (s) => s.active === false ? T('неактивна', 'inactive') : T('отправка отключена', 'sending disabled');
  const sorted = [...sims].sort((a, b) => (usable(b) - usable(a)) || ((a.slot || 99) - (b.slot || 99)));
  v.innerHTML = `<div class="grid g2" style="grid-template-columns:3fr 2fr">
    <form class="card" id="sf">
      <h3><i data-icon="send"></i>${T('Новое сообщение', 'New message')}</h3>
      <label class="field"><span>${T('Получатели', 'Recipients')}</span><textarea id="to" rows="3" style="min-height:70px" placeholder="+79991234567, +77001234567" required></textarea><small>${T('Через запятую или с новой строки, до 100 номеров', 'Comma- or newline-separated, up to 100 numbers')}</small></label>
      <label class="field"><span>${T('Отправить через', 'Send via')}</span><select id="sim"><option value="">${T('Авто — по стратегии', 'Auto — by strategy')}</option>${sorted.map(s => `<option value="${esc(s.simId)}" ${usable(s) ? '' : 'disabled'}>${esc(simName(s))}${simSub(s) ? ' — ' + esc(simSub(s)) : ''}${s.active !== false && s.slot ? ' (SIM ' + esc(s.slot) + (s.embedded ? ', eSIM' : '') + ')' : ''}${usable(s) ? '' : ' — ' + esc(reason(s))}</option>`).join('')}</select>
        ${sims.some(usable) ? '' : `<small style="color:var(--amber)">${T('Нет доступных SIM-карт для отправки', 'No SIM cards available for sending')}</small>`}</label>
      <label class="field"><span>${T('Текст', 'Text')}</span><textarea id="txt" maxlength="1600" required></textarea></label>
      <div class="counter" id="cnt">${T('0 символов', '0 characters')}</div>
      <div id="sErr" class="form-error"></div>
      <button class="btn primary" type="submit"><i data-icon="send"></i>${T('Отправить', 'Send')}</button>
    </form>
    <div class="card"><h3><i data-icon="info"></i>${T('Подсказки', 'Tips')}</h3>
      <div class="opt"><div class="t"><b>${T('Кириллица', 'Cyrillic and other Unicode')}</b><small>${T('Сообщения в Unicode: 70 символов в одной SMS, 67 — в каждой части длинной.', 'Unicode messages: 70 characters in a single SMS, 67 in each part of a long one.')}</small></div></div>
      <div class="opt"><div class="t"><b>${T('Латиница (GSM-7)', 'Latin (GSM-7)')}</b><small>${T('160 символов в одной SMS, 153 — в части.', '160 characters in a single SMS, 153 per part.')}</small></div></div>
      <div class="opt"><div class="t"><b>${T('Очередь', 'Queue')}</b><small>${T('Сообщения отправляются последовательно с паузой, заданной в настройках, — это снижает риск блокировки оператором.', 'Messages are sent one by one with the pause set in Settings — this lowers the risk of being blocked by the carrier.')}</small></div></div>
      <div class="opt"><div class="t"><b>${T('Статусы', 'Statuses')}</b><small>${T('Отслеживайте доставку на странице «Сообщения» или через вебхуки.', 'Track delivery on the “Messages” page or via webhooks.')}</small></div></div>
    </div></div>`;
  const upd = () => { const p = smsParts($('#txt').value); $('#cnt').textContent = `${p.len} ${T('симв.', 'chars')} • ${p.parts} SMS • ${p.enc}${p.parts ? ' • ' + T('осталось ' + p.left, p.left + ' left') : ''}`; };
  $('#txt').oninput = upd;
  $('#sf').onsubmit = async (e) => {
    e.preventDefault(); $('#sErr').textContent = '';
    const to = $('#to').value.split(/[\n,;]+/).map(s => s.trim()).filter(Boolean);
    try {
      const body = { to, text: $('#txt').value };
      if ($('#sim').value !== '') body.simId = +$('#sim').value;
      const r = await POST('messages/send', body);
      toast(T(`В очереди: ${r.messages.length} сообщ.`, `Queued: ${r.messages.length} message(s)`)); $('#txt').value = ''; upd();
    } catch (err) { $('#sErr').textContent = err.message; }
  };
};

// ---------------------------------------------------------------- USSD, balance, SIM memory, calls
const USSD_RE = /^[*#][0-9*#]{1,40}#$/;
const canUssd = () => isAdmin() || !!(ME && ME.canUssd);
const canCall = () => isAdmin() || !!(ME && ME.canCall);
const spinner = (text) => `<div class="spin-wrap"><span class="spinner" aria-hidden="true"></span><span>${esc(text)}</span></div>`;
const activeSims = (sims) => sims.filter(s => s.active !== false);
const simOptions = (sims, selId) => sims.map(s => `<option value="${esc(s.simId)}" ${String(s.simId) === String(selId) ? 'selected' : ''}>${esc(simName(s))}${simSub(s) ? ' — ' + esc(simSub(s)) : ''}${s.slot ? ' (SIM ' + esc(s.slot) + (s.embedded ? ', eSIM' : '') + ')' : ''}</option>`).join('');
const USSD_ST = () => ({ ok: [T('Ответ получен', 'Response received'), 'em'], error: [T('Ошибка', 'Error'), 'red'], pending: [T('Ожидание', 'Pending'), 'am'] });
/** Result card for a USSD record (carrier text is untrusted → esc). */
const ussdResult = (rec, sims) => {
  const [l, c] = USSD_ST()[rec.status] || [rec.status, 'mu'];
  const s = simById(sims, rec.simId);
  return `<div class="ussd-result ${esc(rec.status)}"><div class="row"><span class="tag">${esc(rec.code)}</span>${s ? simChip(s) : rec.slot ? 'SIM ' + esc(rec.slot) : ''}<span class="pill ${c}" style="margin-left:auto">${esc(l)}</span></div>
    <div class="ussd-text">${esc(rec.status === 'error' ? (rec.error || T('Оператор не ответил', 'No response from the carrier')) : (rec.response || (rec.status === 'pending' ? T('Ответ ещё не получен', 'No response yet') : '—')))}</div>
    <div class="muted small">${fmtDate(rec.createdAt)}${rec.finishedAt && rec.createdAt ? ' • ' + ((rec.finishedAt - rec.createdAt) / 1000).toFixed(1) + T(' с', ' s') : ''}</div></div>`;
};

function balanceModal(sim) {
  modal(`<div class="modal-head"><span class="stat"><span class="ico tint-em"><i data-icon="wallet"></i></span></span><div class="grow"><h3>${T('Баланс', 'Balance')} — ${esc(simName(sim))}</h3><small class="muted mono">${esc(sim.balanceCode)}</small></div></div>
    <div id="balBody">${spinner(T('Запрос к оператору… это может занять до 45 секунд', 'Asking the carrier… this can take up to 45 seconds'))}</div>
    <div class="modal-actions"><button class="btn" data-action="closeModal">${T('Закрыть', 'Close')}</button></div>`, async (m) => {
    try { const rec = await POST('sims/' + encodeURIComponent(sim.simId) + '/balance'); if ($('#balBody', m)) $('#balBody', m).innerHTML = ussdResult(rec, [sim]); }
    catch (e) { if ($('#balBody', m)) $('#balBody', m).innerHTML = `<div class="callout"><i data-icon="alert"></i><div>${esc(e.message)}</div></div>`; }
    hydrateIcons(m);
  });
}

// Long SMS stored on the SIM as several records: "3 parts", or "2 of 3 parts" when some are missing.
function iccParts(x) {
  const total = x.total || 1, got = (x.parts || [x.index]).length;
  if (total < 2) return '';
  return got < total
    ? `<span class="pill am" title="${esc(T('Часть фрагментов отсутствует на карте', 'Some parts are missing from the card'))}">${esc(T(`${got} из ${total} частей`, `${got} of ${total} parts`))}</span>`
    : `<span class="pill mu">${esc(T(`Частей: ${total}`, `${total} parts`))}</span>`;
}

function iccModal(sim) {
  const adm = isAdmin();
  const ST = { read: [T('прочитано', 'read'), 'mu'], unread: [T('новое', 'unread'), 'in'], sent: [T('отправлено', 'sent'), 'em'], unsent: [T('не отправлено', 'unsent'), 'am'] };
  modal(`<div class="modal-head"><span class="stat"><span class="ico tint-in"><i data-icon="chipMem"></i></span></span><div class="grow"><h3>${T('Память SIM', 'SIM memory')} — ${esc(simName(sim))}</h3><small class="muted">${T('SMS, сохранённые на самой карте', 'SMS stored on the card itself')}</small></div><button type="button" class="icon-btn close" data-action="closeModal" aria-label="${esc(T('Закрыть', 'Close'))}"><i data-icon="plus"></i></button></div>
    <div id="iccBody">${spinner(T('Чтение памяти SIM…', 'Reading SIM memory…'))}</div>`, (m) => {
    const load = async () => {
      const body = $('#iccBody', m); if (!body) return;
      try {
        const r = await GET('sims/' + encodeURIComponent(sim.simId) + '/icc');
        const list = r.messages || [];
        const sync = adm && list.length ? `<div class="icc-sync"><div class="grow"><b>${T('Синхронизация', 'Sync')}</b><div class="muted small">${T('Перенесите все SMS с карты в переписки телефона. Очистка освобождает память SIM.', 'Copy every SMS from the card into the phone conversations. Clearing frees the SIM memory.')}</div></div>
            <button type="button" class="btn sm" data-sync><i data-icon="copy"></i>${T('Перенести всё', 'Copy all')}</button><button type="button" class="btn danger sm" data-sync-clear><i data-icon="trash"></i>${T('Перенести и очистить SIM', 'Copy all & clear SIM')}</button></div>` : '';
        body.innerHTML = list.length ? sync + `<div class="icc-list">${list.map(x => { const [l, c] = ST[x.status] || [x.status, 'mu']; return `<div class="icc-item" data-idx="${esc(x.index)}">
            <div class="row"><b class="mono">${esc(x.address || '—')}</b><span class="pill ${c}">${esc(l)}</span>${iccParts(x)}<span class="muted small" style="margin-left:auto">#${esc((x.parts || [x.index]).join(', #'))} • ${x.date ? fmtDate(x.date) : '—'}</span></div>
            <div class="msg-text">${esc(x.body)}</div>
            ${adm ? `<div class="icc-actions"><button class="btn sm" data-copy-icc><i data-icon="copy"></i>${T('Копировать в телефон', 'Copy to phone')}</button><button class="btn sm" data-move-icc><i data-icon="arrow"></i>${T('Переместить', 'Move')}</button><button class="icon-btn" data-del data-del-icc title="${esc(T('Удалить с SIM', 'Delete from SIM'))}" aria-label="${esc(T('Удалить с SIM', 'Delete from SIM'))}"><i data-icon="trash"></i></button></div>` : ''}</div>`; }).join('')}</div>
          <p class="muted small">${T('«Копировать» добавляет SMS в переписки телефона, «Переместить» — копирует и удаляет с карты.', '“Copy” adds the SMS to the phone conversations, “Move” copies it and removes it from the card.')}</p>`
          : `<div class="empty"><i data-icon="chipMem"></i><div>${T('В памяти SIM нет сообщений', 'No messages in SIM memory')}</div></div>`;
      } catch (e) {
        body.innerHTML = `<div class="callout ${e.status === 422 ? 'info' : ''}"><i data-icon="${e.status === 422 ? 'info' : 'alert'}"></i><div>${e.status === 422 ? `<b>${T('Память этой SIM недоступна', 'This SIM’s memory is not available')}</b><br>` : ''}${esc(e.message)}</div></div>`;
      }
      hydrateIcons(body);
    };
    m.addEventListener('click', async (e) => {
      const sy = e.target.closest('[data-sync]'), sc = e.target.closest('[data-sync-clear]');
      if (sy || sc) {
        if (sc && !sc.dataset.armed) { sc.dataset.armed = '1'; sc.innerHTML = esc(T('Нажмите ещё раз — SMS будут удалены с карты', 'Click again — SMS will be deleted from the card')); return; }
        (sy || sc).disabled = true;
        try {
          const r = await POST('sims/' + encodeURIComponent(sim.simId) + '/icc/sync', { clearSim: !!sc });
          toast(T(`Перенесено в телефон: ${r.copied}`, `Copied to phone: ${r.copied}`) + (sc ? T(`, удалено с SIM: ${r.deleted}`, `, deleted from SIM: ${r.deleted}`) : ''));
          load();
        } catch (err) { toast(err.message, true); (sy || sc).disabled = false; }
        return;
      }
      const item = e.target.closest('.icc-item'); if (!item) return;
      const idx = item.dataset.idx, base = 'sims/' + encodeURIComponent(sim.simId) + '/icc/' + encodeURIComponent(idx);
      const cp = e.target.closest('[data-copy-icc]'), mv = e.target.closest('[data-move-icc]'), dl = e.target.closest('[data-del-icc]');
      try {
        if (cp || mv) { (cp || mv).disabled = true; await POST(base + '/copy', { move: !!mv }); toast(mv ? T('SMS перемещено в телефон', 'SMS moved to the phone') : T('SMS скопировано в телефон', 'SMS copied to the phone')); if (mv) load(); else cp.disabled = false; }
        if (dl) {
          dl.closest('.icc-actions').innerHTML = `<span class="small icc-confirm">${esc(T('Удалить это SMS с карты без возможности восстановления?', 'Permanently delete this SMS from the card?'))}</span><button type="button" class="btn sm" data-icc-no>${esc(T('Отмена', 'Cancel'))}</button><button type="button" class="btn danger sm" data-icc-yes><i data-icon="trash"></i>${esc(T('Удалить', 'Delete'))}</button>`;
          hydrateIcons(item); return;
        }
        if (e.target.closest('[data-icc-no]')) { load(); return; }
        if (e.target.closest('[data-icc-yes]')) { e.target.closest('[data-icc-yes]').disabled = true; await DEL(base); toast(T('SMS удалено с SIM', 'SMS deleted from the SIM')); load(); }
      } catch (err) { toast(err.message, true); if (cp || mv) (cp || mv).disabled = false; }
    });
    load();
  }, 'wide');
}

PAGES.ussd = async (v) => {
  if (!canUssd()) { v.innerHTML = `<div class="callout info"><i data-icon="lock"></i><div>${T('У вашей учётной записи нет права на USSD-запросы. Обратитесь к администратору.', 'Your account does not have the USSD permission. Contact the administrator.')}</div></div>`; return; }
  const sims = activeSims(await GET('sims'));
  const def = sims.find(s => s.isDefault) || sims[0];
  v.innerHTML = `<div class="grid g2 ussd-grid">
    <form class="card" id="uForm" novalidate><h3><i data-icon="hash"></i>${T('USSD-запрос', 'USSD request')}</h3>
      ${sims.length ? '' : `<div class="callout"><i data-icon="alert"></i><div>${T('Нет активных SIM-карт', 'No active SIM cards')}</div></div>`}
      <label class="field"><span>SIM</span><select id="uSim">${simOptions(sims, def && def.simId)}</select></label>
      <label class="field"><span>${T('Код', 'Code')}</span><input id="uCode" class="mono" placeholder="*100#" maxlength="32" spellcheck="false" autocomplete="off"><em class="ferr" id="uCodeErr"></em></label>
      <div class="chips" id="uChips"></div>
      <div class="callout info" style="margin:14px 0"><i data-icon="info"></i><div>${T('Поддерживаются только одношаговые USSD-запросы: интерактивные меню (выбор пунктов ответом) недоступны.', 'Only single-step USSD requests are supported: interactive menus (replying with a choice) are not available.')}</div></div>
      <button class="btn primary" type="submit" id="uRun" ${sims.length ? '' : 'disabled'}><i data-icon="send"></i>${T('Выполнить', 'Run')}</button>
      <div id="uOut" style="margin-top:16px"></div>
    </form>
    <div class="card"><div class="card-head"><h3><i data-icon="clock"></i>${T('История', 'History')}</h3><div class="right"><button class="btn sm" id="uRef"><i data-icon="retry"></i></button></div></div><div id="uHist"></div></div>
  </div>`;
  const chips = () => {
    const s = simById(sims, $('#uSim').value);
    const codes = [...new Set([s && s.balanceCode, '*100#', '*102#', '*111#'].filter(Boolean))];
    $('#uChips').innerHTML = codes.map(c => `<button type="button" class="chip-btn${s && c === s.balanceCode ? ' accent' : ''}" data-code="${esc(c)}">${s && c === s.balanceCode ? `<i data-icon="wallet"></i>` : ''}${esc(c)}</button>`).join('');
    hydrateIcons($('#uChips'));
  };
  const hist = async () => {
    const list = await GET('ussd?limit=50');
    $('#uHist').innerHTML = list.length ? `<div class="ussd-hist">${list.map(r => ussdResult(r, sims)).join('')}</div>` : `<div class="empty"><i data-icon="hash"></i><div>${T('Запросов пока не было', 'No requests yet')}</div></div>`;
    hydrateIcons($('#uHist'));
  };
  chips(); $('#uSim').onchange = chips;
  $('#uChips').onclick = (e) => { const b = e.target.closest('[data-code]'); if (b) { $('#uCode').value = b.dataset.code; fieldErr(v, 'uCodeErr', ''); $('#uCode').focus(); } };
  $('#uRef').onclick = () => hist().catch(e => toast(e.message, true));
  $('#uForm').onsubmit = async (e) => {
    e.preventDefault();
    const code = $('#uCode').value.trim();
    if (!fieldErr(v, 'uCodeErr', USSD_RE.test(code) ? '' : T('Код должен начинаться с * или # и заканчиваться #, например *100#', 'The code must start with * or # and end with #, e.g. *100#'))) return;
    const btn = $('#uRun'); btn.disabled = true;
    $('#uOut').innerHTML = spinner(T('Ожидание ответа оператора… до 45 секунд', 'Waiting for the carrier… up to 45 seconds'));
    try { const rec = await POST('ussd', { code, simId: +$('#uSim').value }); if ($('#uOut')) { $('#uOut').innerHTML = ussdResult(rec, sims); hydrateIcons($('#uOut')); } hist().catch(() => {}); }
    catch (err) { if ($('#uOut')) $('#uOut').innerHTML = `<div class="callout"><i data-icon="alert"></i><div>${esc(err.message)}</div></div>`; hydrateIcons(v); }
    finally { if ($('#uRun')) btn.disabled = false; }
  };
  await hist();
};

const CALL_ST = () => ({ ringing: [T('Звонит', 'Ringing'), 'am'], answered: [T('Принят', 'Answered'), 'em'], missed: [T('Пропущен', 'Missed'), 'red'], ended: [T('Завершён', 'Ended'), 'mu'],
  dialing: [T('Набор', 'Dialing'), 'sky'], active: [T('Разговор', 'In call'), 'em'], failed: [T('Ошибка', 'Failed'), 'red'] });
const dur = (c) => { const s = c.answeredAt > 0 && c.endedAt > c.answeredAt ? Math.round((c.endedAt - c.answeredAt) / 1000) : 0; return s ? (s >= 3600 ? Math.floor(s / 3600) + ':' : '') + String(Math.floor(s % 3600 / 60)).padStart(s >= 3600 ? 2 : 1, '0') + ':' + String(s % 60).padStart(2, '0') : ''; };

PAGES.calls = async (v) => {
  if (!canCall()) { v.innerHTML = `<div class="callout info"><i data-icon="lock"></i><div>${T('У вашей учётной записи нет права на звонки. Обратитесь к администратору.', 'Your account does not have the calls permission. Contact the administrator.')}</div></div>`; return; }
  const all = await GET('sims'), sims = activeSims(all);
  const def = sims.find(s => s.isDefault) || sims[0];
  let filt = '';
  v.innerHTML = `<div class="grid g2 calls-grid">
    <form class="card" id="cForm" novalidate><h3><i data-icon="phoneOut"></i>${T('Позвонить с телефона', 'Call from the phone')}</h3>
      <label class="field"><span>${T('Номер', 'Number')}</span><input id="cTo" type="tel" class="mono" placeholder="+79991234567" autocomplete="off"><em class="ferr" id="cToErr"></em></label>
      <label class="field"><span>SIM</span><select id="cSim">${simOptions(sims, def && def.simId)}</select></label>
      <button class="btn primary" type="submit" id="cDial" ${sims.length ? '' : 'disabled'}><i data-icon="phone"></i>${T('Набрать', 'Dial')}</button>
      <div class="callout info" style="margin:16px 0 0"><i data-icon="info"></i><div>${T('Телефон наберёт номер сам — разговор ведётся на телефоне. Голос в браузер не передаётся (ограничение Android).', 'The phone dials the number itself — you talk on the phone. Voice is not transmitted to the browser (an Android limitation).')}</div></div>
    </form>
    <div class="card"><div class="card-head wrap"><h3><i data-icon="list"></i>${T('Журнал звонков', 'Call log')}</h3><div class="right"><div class="seg" id="cSeg">
      ${[['', T('Все', 'All')], ['missed', T('Пропущенные', 'Missed')], ['in', T('Входящие', 'Incoming')], ['out', T('Исходящие', 'Outgoing')]].map(([k, l]) => `<button type="button" data-f="${k}" class="${k === '' ? 'on' : ''}">${esc(l)}</button>`).join('')}</div></div></div>
      <div id="cLog"></div></div></div>`;
  let calls = [];
  const draw = () => {
    const list = calls.filter(c => !filt || (filt === 'missed' ? c.status === 'missed' : c.direction === filt));
    $('#cLog').innerHTML = list.length ? `<div class="call-list">${list.map(c => {
      const missed = c.status === 'missed', [l, cl] = CALL_ST()[c.status] || [c.status, 'mu'], s = simById(all, c.simId), d = dur(c);
      const ic = missed ? 'phoneMissed' : c.direction === 'in' ? 'phoneIn' : 'phoneOut';
      return `<div class="call-row${missed ? ' missed' : ''}"><span class="dir ${missed ? 'tint-red' : c.direction === 'in' ? 'tint-in' : 'tint-em'}" title="${esc(c.direction === 'in' ? T('Входящий', 'Incoming') : T('Исходящий', 'Outgoing'))}"><i data-icon="${ic}"></i></span>
        <div class="t"><b class="mono">${c.number ? esc(c.number) : `<span class="muted">${T('номер скрыт', 'hidden')}</span>`}</b><small>${fmtDate(c.startedAt)}${s ? '' : c.slot ? ' • SIM ' + esc(c.slot) : ''}${c.source ? ' • ' + esc(c.source) : ''}</small></div>
        <div class="call-meta">${s ? simChip(s) : ''}${d ? `<span class="mono small call-dur">${d}</span>` : ''}<span class="pill ${cl}">${esc(l)}</span></div>
        ${c.number ? `<button class="icon-btn" data-redial="${esc(c.number)}" data-sim="${esc(c.simId)}" title="${esc(T('Перезвонить', 'Call back'))}" aria-label="${esc(T('Перезвонить', 'Call back'))}"><i data-icon="phone"></i></button>` : ''}</div>`;
    }).join('')}</div>` : `<div class="empty"><i data-icon="phone"></i><div>${T('Звонков нет', 'No calls')}</div></div>`;
    hydrateIcons($('#cLog'));
  };
  const load = async () => { calls = await GET('calls?limit=100'); draw(); };
  $('#cSeg').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; filt = b.dataset.f; v.querySelectorAll('#cSeg button').forEach(x => x.classList.toggle('on', x === b)); draw(); };
  const dial = async (to, simId) => {
    const btn = $('#cDial'); btn.disabled = true;
    try { await POST('calls', { to, simId: +simId }); toast(T('Телефон набирает ' + to + ' — возьмите трубку на телефоне', 'The phone is dialing ' + to + ' — pick it up on the phone')); setTimeout(() => load().catch(() => {}), 1500); }
    catch (err) { toast(err.message, true); } finally { btn.disabled = false; }
  };
  $('#cForm').onsubmit = (e) => {
    e.preventDefault();
    const to = $('#cTo').value.trim();
    if (!fieldErr(v, 'cToErr', /^\+?[0-9*#()\-\s]{3,20}$/.test(to) ? '' : T('Введите номер телефона', 'Enter a phone number'))) return;
    dial(to.replace(/[\s()-]/g, ''), $('#cSim').value);
  };
  $('#cLog').onclick = (e) => { const b = e.target.closest('[data-redial]'); if (!b) return; $('#cTo').value = b.dataset.redial; if (simById(sims, b.dataset.sim)) $('#cSim').value = b.dataset.sim; $('#cTo').scrollIntoView({ block: 'center', behavior: 'smooth' }); $('#cTo').focus(); };
  await load();
  timer = setInterval(() => { if (!document.hidden) load().catch(() => {}); }, 6000);
};

PAGES.sims = async (v) => {
  const adm = isAdmin();
  const [sims, users, dash] = await Promise.all([GET('sims'), adm ? GET('users').catch(() => []) : [], GET('dashboard').catch(() => null)]);
  const stats = dash && dash.stats;
  const explainer = `<div class="callout info"><i data-icon="info"></i><div>${T('SIM-карты и профили eSIM определяются по постоянному идентификатору, а не по слоту — права пользователей и ключей сохраняются при перестановке карт и переключении профилей eSIM. Извлечённые карты остаются в списке как неактивные вместе с настройками.', 'SIM cards and eSIM profiles are identified by a stable id, not by slot — user and key permissions survive slot swaps and eSIM profile switches. Removed cards stay listed as inactive, with their settings.')}${adm ? ' ' + T('Отключите отправку на SIM без SMS-пакета и задайте дневной лимит — шлюз сам переключится на другую карту по выбранной стратегии.', 'Disable sending on a SIM without an SMS plan and set a daily limit — the gateway switches to another card according to the selected strategy.') : ''}</div></div>`;
  if (!sims.length) {
    v.innerHTML = explainer + `<div class="card empty"><i data-icon="sim"></i><div>${adm ? T('SIM-карты не найдены. Проверьте разрешение «Телефон» в приложении.', 'No SIM cards found. Check the “Phone” permission in the app.') : T('Вам пока не назначены SIM-карты — обратитесь к администратору.', 'No SIM cards are assigned to you yet — contact the administrator.')}</div></div>`;
    return;
  }
  const list = [...sims].sort((a, b) => ((b.active !== false) - (a.active !== false)) || ((a.slot || 99) - (b.slot || 99)) || ((b.lastSeen || 0) - (a.lastSeen || 0)));
  const accessFor = (s) => users.filter(u => u.role === 'admin' || u.allSims || (u.sims || []).map(String).includes(String(s.simId)));
  const accessHtml = (s) => {
    const us = accessFor(s), shown = us.slice(0, 5);
    return `<div class="sim-access"><span class="muted small">${T('Доступ', 'Access')}</span><span class="avatars" title="${esc(us.map(u => '@' + u.username).join(', '))}">${shown.map(u => avatar(u, 'xs')).join('')}${us.length > shown.length ? `<span class="avatar xs more">+${us.length - shown.length}</span>` : ''}</span>
      <span class="small">${us.length} ${lang === 'en' ? (us.length === 1 ? 'user' : 'users') : plural(us.length, 'пользователь', 'пользователя', 'пользователей')}</span></div>`;
  };
  const swatches = (s) => {
    const cur = simColor(s).toUpperCase(), pal = PALETTE.includes(cur) ? PALETTE : [...PALETTE, cur];
    return `<div class="swatches" role="radiogroup" aria-label="${esc(T('Цвет', 'Color'))}">${pal.map(c => `<button type="button" class="swatch${c === cur ? ' on' : ''}" role="radio" aria-checked="${c === cur}" data-color="${c}" style="--c:${c}" aria-label="${c}"></button>`).join('')}</div>`;
  };
  const tile = (s, i) => {
    const used = s.active !== false ? usageOf(stats, s) : null;
    const state = s.active === false ? `<div class="sim-note"><i data-icon="clock"></i>${T('Неактивна • последний раз в сети', 'Inactive • last seen')} ${s.lastSeen ? fmtDate(s.lastSeen) : '—'}</div>` : '';
    const ro = `<div class="sim-ro">
        <div class="kv"><span>${T('Отправка', 'Sending')}</span><span class="pill ${s.sendEnabled ? 'em' : 'mu'}">${s.sendEnabled ? T('включена', 'on') : T('выключена', 'off')}</span></div>
        <div class="kv"><span>${T('Приём', 'Receiving')}</span><span class="pill ${s.receiveEnabled ? 'em' : 'mu'}">${s.receiveEnabled ? T('включён', 'on') : T('выключен', 'off')}</span></div>
        <div class="kv"><span>${T('Дневной лимит', 'Daily limit')}</span><b>${s.dailyLimit ? esc(s.dailyLimit) : T('без лимита', 'no limit')}</b></div>
        ${s.balanceCode ? `<div class="kv"><span>${T('Код баланса', 'Balance code')}</span><b class="mono">${esc(s.balanceCode)}</b></div>` : ''}</div>`;
    const edit = `<div class="sim-edit">
        <label class="field"><span>${T('Название', 'Label')}</span><input class="lb" value="${esc(s.label)}" maxlength="30" placeholder="${esc(s.carrier || s.displayName || 'SIM')}"></label>
        <div class="field"><span>${T('Цвет', 'Color')}</span>${swatches(s)}</div>
        ${toggleRow('se_' + i, T('Отправка SMS', 'Send SMS'), T('Использовать эту SIM для исходящих', 'Use this SIM for outgoing messages'), s.sendEnabled, 'class="se"')}
        ${toggleRow('re_' + i, T('Приём SMS', 'Receive SMS'), T('Пересылать входящие на вебхуки, сервер и в Telegram', 'Forward incoming messages to webhooks, server and Telegram'), s.receiveEnabled, 'class="re"')}
        <div class="grid g2 tight" style="margin-top:12px">
          <label class="field"><span>${T('Дневной лимит отправки', 'Daily sending limit')}</span><input class="dl" type="number" min="0" inputmode="numeric" value="${esc(s.dailyLimit)}"><small>${T('0 — без ограничений', '0 — unlimited')}</small></label>
          <label class="field"><span>${T('USSD-код баланса', 'Balance USSD code')}</span><input class="bc mono" value="${esc(s.balanceCode || '')}" maxlength="32" placeholder="*100#" spellcheck="false"><small class="bc-hint">${T('Например *100#; пусто — не задан', 'E.g. *100#; empty — not set')}</small></label>
        </div>
        ${accessHtml(s)}
        <div class="sim-actions">${s.active === false ? `<button class="btn danger sm" data-forget><i data-icon="forget"></i>${T('Забыть', 'Forget')}</button>` : ''}<span class="dirty muted small"></span><button class="btn primary sm" data-save disabled><i data-icon="check"></i>${T('Сохранить', 'Save')}</button></div>
      </div>`;
    const tools = s.active !== false ? `<div class="sim-tools">
        ${canUssd() ? `<button class="btn sm" data-balance ${s.balanceCode ? '' : `disabled title="${esc(T('Код баланса не задан', 'No balance code set'))}"`}><i data-icon="wallet"></i>${T('Проверить баланс', 'Check balance')}</button>` : ''}
        ${adm || (ME && ME.canRead) ? `<button class="btn sm" data-icc><i data-icon="chipMem"></i>${T('Память SIM', 'SIM memory')}</button>` : ''}</div>` : '';
    return `<div class="card sim-tile" data-sim="${esc(s.simId)}" data-i="${i}">
      <div class="bank-wrap">${bankCard(s)}</div>${state}${usageBar(used, s.dailyLimit)}${tools}${adm ? edit : ro}</div>`;
  };
  v.innerHTML = explainer + `<div class="sim-grid">${list.map(tile).join('')}</div>`;
  v.querySelectorAll('.sim-tile').forEach(card => {
    const s = list[+card.dataset.i];
    const bb = $('[data-balance]', card); if (bb) bb.onclick = () => balanceModal(s);
    const ib = $('[data-icc]', card); if (ib) ib.onclick = () => iccModal(s);
  });
  if (!adm) return;
  v.querySelectorAll('.sim-tile').forEach(card => {
    const s = list[+card.dataset.i];
    let color = simColor(s).toUpperCase();
    const read = () => ({ simId: s.simId, label: $('.lb', card).value.trim(), color, sendEnabled: $('.se', card).checked, receiveEnabled: $('.re', card).checked, dailyLimit: Math.max(0, Math.floor(+$('.dl', card).value || 0)), balanceCode: $('.bc', card).value.trim() });
    const orig = JSON.stringify(read());
    const sync = () => {
      const cur = read(), dirty = JSON.stringify(cur) !== orig;
      const bcOk = !cur.balanceCode || USSD_RE.test(cur.balanceCode);
      $('.bc', card).classList.toggle('invalid', !bcOk);
      $('.bc-hint', card).textContent = bcOk ? T('Например *100#; пусто — не задан', 'E.g. *100#; empty — not set') : T('Код должен начинаться с * или # и заканчиваться #', 'The code must start with * or # and end with #');
      $('[data-save]', card).disabled = !dirty || !bcOk;
      $('.dirty', card).textContent = dirty ? T('Есть изменения', 'Unsaved changes') : '';
      $('.bank-wrap', card).innerHTML = bankCard({ ...s, label: cur.label, color: cur.color });
    };
    card.addEventListener('input', sync); card.addEventListener('change', sync);
    $('.swatches', card).onclick = (e) => {
      const b = e.target.closest('[data-color]'); if (!b) return;
      color = b.dataset.color;
      card.querySelectorAll('.swatch').forEach(x => { const on = x === b; x.classList.toggle('on', on); x.setAttribute('aria-checked', on); });
      sync();
    };
    $('[data-save]', card).onclick = async (e) => {
      const btn = e.currentTarget; btn.disabled = true;
      try { await PUT('sims', { sims: [read()] }); toast(T('SIM «' + simName({ ...s, ...read() }) + '» сохранена', 'SIM “' + simName({ ...s, ...read() }) + '” saved')); route(); }
      catch (err) { toast(err.message, true); btn.disabled = false; }
    };
    const fg = $('[data-forget]', card);
    if (fg) fg.onclick = async () => {
      if (!await confirmBox(T('Забыть SIM-карту?', 'Forget this SIM card?'), T('Неактивная SIM «' + simName(s) + '» будет удалена из реестра, а также из прав пользователей и API-ключей. Если карту вставят снова, она появится как новая с настройками по умолчанию.', 'The inactive SIM “' + simName(s) + '” will be removed from the registry and from user and API key permissions. If the card is inserted again, it will appear as new with default settings.'), T('Забыть', 'Forget'))) return;
      try { await DEL('sims/' + encodeURIComponent(s.simId)); toast(T('SIM-карта удалена из реестра', 'SIM card removed from the registry')); route(); } catch (err) { toast(err.message, true); }
    };
  });
};

// ---------------------------------------------------------------- API keys
PAGES.keys = async (v) => {
  const adm = isAdmin();
  if (!adm && ME && !ME.canUseApi) { v.innerHTML = `<div class="callout info"><i data-icon="lock"></i><div>${T('Доступ к API для вашей учётной записи отключён администратором.', 'API access for your account is disabled by the administrator.')}</div></div>`; return; }
  const [sims, users] = await Promise.all([GET('sims').catch(() => []), adm ? GET('users').catch(() => []) : Promise.resolve([])]);
  const userOf = (id) => users.find(u => u.id === id) || (ME && ME.id === id ? ME : null);
  const ownerSims = (ownerId) => {
    if (!adm) return sims;
    const u = userOf(ownerId); if (!u) return [];
    return (u.role === 'admin' || u.allSims) ? sims : sims.filter(s => (u.sims || []).map(String).includes(String(s.simId)));
  };
  let filter = new URLSearchParams(location.hash.split('?')[1] || '').get('owner') || '';
  const owners = users.filter(u => u.role === 'admin' || u.canUseApi);

  const tokenModal = (token) => modal(`<h3><i data-icon="key"></i>${T('Ключ создан', 'Key created')}</h3><div class="callout"><i data-icon="alert"></i><div>${T('Скопируйте ключ сейчас — он показывается только один раз и хранится на телефоне лишь в виде хеша.', 'Copy the key now — it is shown only once and the phone stores only its hash.')}</div></div><div class="secret" id="tok">${esc(token)}</div>
    <div class="modal-actions"><button class="btn" data-action="closeModal">${T('Готово', 'Done')}</button><button class="btn primary" id="cp"><i data-icon="copy"></i>${T('Скопировать', 'Copy')}</button></div>`, (m) => { $('#cp', m).onclick = () => copyText(token); });

  const keyModal = (k, done) => {
    const isNew = !k;
    k = k || { name: '', scopes: ['send', 'read'], allowedIps: [], sims: [], ownerId: ME ? ME.id : '' };
    const ownerSel = adm && isNew ? `<label class="field"><span>${T('Владелец', 'Owner')}</span><select id="kOwner">${owners.map(u => `<option value="${esc(u.id)}" ${u.id === k.ownerId ? 'selected' : ''} ${u.enabled ? '' : 'disabled'}>${esc(u.displayName || u.username)} (@${esc(u.username)})${u.enabled ? '' : ' — ' + T('отключён', 'disabled')}</option>`).join('')}</select><small>${T('Права ключа никогда не шире прав владельца', 'A key never has more rights than its owner')}</small></label>` : '';
    const ownerLine = !isNew && adm ? `<div class="key-owner">${avatar(userOf(k.ownerId) || { username: k.owner }, 'xs')}<span class="small">${T('Владелец', 'Owner')}: <b>@${esc(k.owner || (userOf(k.ownerId) || {}).username || '—')}</b></span></div>` : '';
    modal(`<div class="modal-head"><span class="stat"><span class="ico tint-em"><i data-icon="key"></i></span></span><div><h3>${isNew ? T('Новый API-ключ', 'New API key') : T('Ключ', 'Key') + ' «' + esc(k.name) + '»'}</h3>${isNew ? '' : `<small class="muted mono">${esc(k.prefix)}…</small>`}</div><button type="button" class="icon-btn close" data-action="closeModal" aria-label="${esc(T('Закрыть', 'Close'))}"><i data-icon="plus"></i></button></div>
      <form id="kf" novalidate>
      <section class="msec"><h4>${T('Основное', 'General')}</h4>${ownerLine}
        <label class="field"><span>${T('Название', 'Name')}</span><input id="kn" value="${esc(k.name)}" placeholder="${T('CRM, 1С, бот…', 'CRM, ERP, bot…')}" maxlength="40"><em class="ferr" id="knErr"></em></label>
        ${ownerSel}</section>
      <section class="msec"><h4>${T('Права', 'Permissions')}</h4>
        ${toggleRow('ks', T('Отправка SMS', 'Send SMS'), 'POST /api/v1/sms', k.scopes.includes('send'))}
        ${toggleRow('kr', T('Чтение', 'Read'), T('Входящие, статусы сообщений, SIM-карты, память SIM, состояние', 'Incoming messages, statuses, SIM cards, SIM memory, status'), k.scopes.includes('read'))}
        ${toggleRow('ku', 'USSD', 'POST /api/v1/ussd', k.scopes.includes('ussd'))}
        ${toggleRow('kc', T('Звонки', 'Calls'), T('Журнал звонков и набор номера', 'Call log and dialing'), k.scopes.includes('call'))}
        <div class="small" id="kOwnerHint" style="color:var(--amber)"></div><em class="ferr" id="ksErr"></em></section>
      <section class="msec"><h4>${T('SIM-карты ключа', 'Key SIM cards')}</h4>
        <p class="muted small">${T('Ничего не выбрано — ключ работает со всеми SIM-картами владельца.', 'Nothing selected — the key works with all of the owner’s SIM cards.')}</p>
        <div id="kSimWrap"></div></section>
      <section class="msec"><h4>${T('Ограничение по IP', 'IP restriction')}</h4>
        <label class="field"><span>${T('Разрешённые IP / подсети (необязательно)', 'Allowed IPs / subnets (optional)')}</span><input id="ki" value="${esc((k.allowedIps || []).join(', '))}" placeholder="192.168.1.10, 10.0.0.0/24"><small>${T('Если указаны — ключ работает только с этих адресов', 'If set, the key works only from these addresses')}</small></label></section>
      <div class="form-error" id="ke"></div>
      <div class="modal-actions"><button type="button" class="btn" data-action="closeModal">${T('Отмена', 'Cancel')}</button><button class="btn primary" type="submit"><i data-icon="check"></i>${isNew ? T('Создать ключ', 'Create key') : T('Сохранить', 'Save')}</button></div></form>`, (m) => {
      const ownerId = () => adm && isNew ? $('#kOwner', m).value : k.ownerId;
      const drawSims = (keep) => {
        const allowed = ownerSims(ownerId());
        const sel = keep.filter(id => allowed.some(s => String(s.simId) === String(id)));
        $('#kSimWrap', m).innerHTML = allowed.length ? simPicker(allowed, sel, 'kSims') : `<div class="muted small">${T('У владельца нет доступных SIM-карт', 'The owner has no SIM cards available')}</div>`;
        hydrateIcons($('#kSimWrap', m)); bindPicker(m, 'kSims');
        const u = userOf(ownerId());
        const miss = u && u.role !== 'admin' ? [!u.canSend && T('отправку', 'send'), !u.canRead && T('чтение', 'read'), !u.canUssd && 'USSD', !u.canCall && T('звонки', 'calls')].filter(Boolean) : [];
        $('#kOwnerHint', m).textContent = miss.length ? T('У владельца нет прав: ' + miss.join(', ') + ' — ключ не сможет это делать, даже если право отмечено.', 'The owner lacks: ' + miss.join(', ') + ' — the key cannot do it even if the scope is checked.') : '';
      };
      drawSims(k.sims || []);
      if ($('#kOwner', m)) $('#kOwner', m).onchange = () => drawSims(pickedSims(m, 'kSims'));
      $('#kf', m).onsubmit = async (e) => {
        e.preventDefault(); $('#ke', m).textContent = '';
        const name = $('#kn', m).value.trim();
        const scopes = [$('#ks', m).checked && 'send', $('#kr', m).checked && 'read', $('#ku', m).checked && 'ussd', $('#kc', m).checked && 'call'].filter(Boolean);
        const ok1 = fieldErr(m, 'knErr', name ? '' : T('Введите название ключа', 'Enter a key name'));
        const ok2 = fieldErr(m, 'ksErr', scopes.length ? '' : T('Выберите хотя бы одно право', 'Select at least one permission'));
        if (!ok1 || !ok2) return;
        const body = { name, scopes, allowedIps: $('#ki', m).value, sims: pickedSims(m, 'kSims') };
        const btn = $('button[type="submit"]', m); btn.disabled = true;
        try {
          if (isNew) {
            if (adm) body.ownerId = ownerId();
            const r = await POST('keys', body); tokenModal(r.token);
          } else { await PUT('keys/' + encodeURIComponent(k.id), body); closeModal(); toast(T('Ключ сохранён', 'Key saved')); }
          done();
        } catch (err) { $('#ke', m).textContent = err.message; btn.disabled = false; }
      };
      setTimeout(() => $('#kn', m).focus(), 30);
    }, 'wide');
  };

  const draw = async () => {
    const keys = await GET('keys');
    const shown = filter ? keys.filter(k => k.ownerId === filter) : keys;
    const ownerCell = (k) => { const u = userOf(k.ownerId); return `<span class="owner-cell">${avatar(u || { username: k.owner, id: k.ownerId }, 'xs')}<span>${esc(u ? (u.displayName || u.username) : k.owner)}<small class="muted">@${esc(k.owner)}</small></span></span>`; };
    v.innerHTML = `<div class="card"><div class="card-head wrap"><h3><i data-icon="key"></i>${adm ? T('API-ключи', 'API keys') : T('Мои API-ключи', 'My API keys')} <span class="count-badge">${keys.length}</span></h3><div class="right">
        ${adm ? `<select id="kFilter" class="sm-select" aria-label="${esc(T('Фильтр по владельцу', 'Filter by owner'))}"><option value="">${T('Все владельцы', 'All owners')}</option>${users.map(u => `<option value="${esc(u.id)}" ${filter === u.id ? 'selected' : ''}>@${esc(u.username)} (${keys.filter(k => k.ownerId === u.id).length})</option>`).join('')}</select>` : ''}
        <button class="btn primary sm" id="nk"><i data-icon="plus"></i>${T('Создать ключ', 'Create key')}</button></div></div>
      <p class="muted" style="margin-top:-6px">${T('Ключи хранятся в виде SHA-256 хеша — полный ключ показывается только при создании. Ключ действует в пределах прав и SIM-карт своего владельца. Передавайте ключ в заголовке', 'Keys are stored as SHA-256 hashes — the full key is shown only once, at creation. A key is limited to its owner’s permissions and SIM cards. Pass the key in the header')} <span class="tag">Authorization: Bearer sg_…</span></p>
      ${shown.length ? `<div class="table-wrap"><table class="rt"><thead><tr><th>${T('Название', 'Name')}</th>${adm ? `<th>${T('Владелец', 'Owner')}</th>` : ''}<th>${T('Ключ', 'Key')}</th><th>${T('Права', 'Scopes')}</th><th>SIM</th><th>${T('Разрешённые IP', 'Allowed IPs')}</th><th>${T('Использование', 'Last used')}</th><th>${T('Активен', 'Active')}</th><th></th></tr></thead><tbody>
      ${shown.map(k => `<tr class="${k.enabled ? '' : 'row-off'}"><td data-label="${esc(T('Название', 'Name'))}"><div class="cv"><b>${esc(k.name)}</b><div class="muted small">${T('создан', 'created')} ${fmtDate(k.createdAt)}</div></div></td>
        ${adm ? `<td data-label="${esc(T('Владелец', 'Owner'))}"><div class="cv">${ownerCell(k)}</div></td>` : ''}
        <td data-label="${esc(T('Ключ', 'Key'))}" class="mono"><div class="cv">${esc(k.prefix)}…</div></td>
        <td data-label="${esc(T('Права', 'Scopes'))}"><div class="cv">${(k.scopes || []).map(s => `<span class="tag">${esc(s)}</span>`).join('')}</div></td>
        <td data-label="SIM"><div class="cv"><div class="chips">${(k.sims || []).length ? simChipsFor(k.sims, false, sims) : `<span class="muted small">${T('все SIM владельца', 'all owner’s SIMs')}</span>`}</div></div></td>
        <td data-label="${esc(T('Разрешённые IP', 'Allowed IPs'))}"><div class="cv">${(k.allowedIps || []).length ? k.allowedIps.map(s => `<span class="tag">${esc(s)}</span>`).join('') : `<span class="muted small">${T('любые (по файрволу)', 'any (per firewall)')}</span>`}</div></td>
        <td data-label="${esc(T('Использование', 'Last used'))}" class="small"><div class="cv">${ago(k.lastUsedAt)}${k.lastIp ? `<div class="muted">${esc(k.lastIp)}</div>` : ''}</div></td>
        <td data-label="${esc(T('Активен', 'Active'))}"><div class="cv">${sw('k_' + esc(k.id), k.enabled, `data-toggle="${esc(k.id)}" aria-label="${esc(T('Ключ активен', 'Key active'))}"`)}</div></td>
        <td class="actions"><button class="icon-btn" title="${esc(T('Изменить', 'Edit'))}" aria-label="${esc(T('Изменить', 'Edit'))}" data-edit="${esc(k.id)}"><i data-icon="edit"></i></button> <button class="icon-btn" title="${esc(T('Удалить', 'Delete'))}" aria-label="${esc(T('Удалить', 'Delete'))}" data-del="${esc(k.id)}"><i data-icon="trash"></i></button></td></tr>`).join('')}
      </tbody></table></div>` : `<div class="empty"><i data-icon="key"></i><div>${filter ? T('У этого владельца нет ключей.', 'This owner has no keys.') : T('Ключей пока нет — создайте первый, чтобы подключить внешнюю систему.', 'No keys yet — create the first one to connect an external system.')}</div></div>`}</div>`;
    hydrateIcons(v);
    $('#nk').onclick = () => keyModal(null, draw);
    if ($('#kFilter')) $('#kFilter').onchange = (e) => { filter = e.target.value; history.replaceState(null, '', '#/keys' + (filter ? '?owner=' + encodeURIComponent(filter) : '')); draw(); };
    v.querySelectorAll('[data-toggle]').forEach(el => el.onchange = async () => { try { await PUT('keys/' + encodeURIComponent(el.dataset.toggle), { enabled: el.checked }); toast(el.checked ? T('Ключ включён', 'Key enabled') : T('Ключ отключён', 'Key disabled')); el.closest('tr').classList.toggle('row-off', !el.checked); } catch (e) { el.checked = !el.checked; toast(e.message, true); } });
    v.querySelectorAll('[data-del]').forEach(el => el.onclick = async () => {
      const k = keys.find(x => x.id === el.dataset.del);
      if (await confirmBox(T('Удалить ключ «' + k.name + '»?', 'Delete key “' + k.name + '”?'), T('Клиенты с этим ключом сразу потеряют доступ. Действие нельзя отменить.', 'Clients using this key will lose access immediately. This cannot be undone.'))) {
        try { await DEL('keys/' + encodeURIComponent(k.id)); toast(T('Ключ удалён', 'Key deleted')); draw(); } catch (e) { toast(e.message, true); }
      }
    });
    v.querySelectorAll('[data-edit]').forEach(el => el.onclick = () => keyModal(keys.find(x => x.id === el.dataset.edit), draw));
  };
  await draw();
};

// ---------------------------------------------------------------- users (admin)
function userModal(u, sims, done) {
  const isNew = !u, isAdm = !!u && u.role === 'admin';
  u = u || { username: '', displayName: '', canSend: true, canRead: true, canUseApi: true, canWeb: true, canUssd: false, canCall: false, allSims: false, sims: [], role: 'user' };
  const dis = isAdm ? 'disabled' : '';
  modal(`<div class="modal-head"><span id="uAv">${avatar(u, 'lg')}</span><div class="grow"><h3>${isNew ? T('Новый пользователь', 'New user') : T('Редактирование пользователя', 'Edit user')}</h3><small class="muted" id="uAt">${u.username ? '@' + esc(u.username) : T('заполните данные учётной записи', 'fill in the account details')}</small></div><button type="button" class="icon-btn close" data-action="closeModal" aria-label="${esc(T('Закрыть', 'Close'))}"><i data-icon="plus"></i></button></div>
    <form id="uf" novalidate autocomplete="off">
    <section class="msec"><h4><i data-icon="user"></i>${T('Учётная запись', 'Account')}</h4>
      <div class="grid g2 tight">
        <label class="field"><span>${T('Отображаемое имя', 'Display name')}</span><input id="uDn" value="${esc(u.displayName)}" maxlength="60" placeholder="${T('Иван Петров', 'John Smith')}"></label>
        <label class="field"><span>${T('Логин', 'Username')}</span><input id="uUn" value="${esc(u.username)}" maxlength="32" autocapitalize="off" spellcheck="false" placeholder="ivan.petrov"><small>${T('3–32 символа: латиница, цифры, . _ -', '3–32 characters: latin letters, digits, . _ -')}</small><em class="ferr" id="uUnErr"></em></label>
      </div>
      ${isNew ? pwdField('uPw', T('Пароль', 'Password'), T('Минимум 8 символов. Сообщите пароль пользователю безопасным способом.', 'At least 8 characters. Share it with the user securely.')) + '<em class="ferr" id="uPwErr"></em>' : ''}
    </section>
    <section class="msec"><h4><i data-icon="shield"></i>${T('Права', 'Permissions')}</h4>
      ${isAdm ? `<div class="callout info"><i data-icon="info"></i><div>${T('Администратор всегда имеет полный доступ ко всем функциям и SIM-картам — у него можно изменить только имя и логин.', 'The administrator always has full access to every feature and SIM card — only the name and username can be changed.')}</div></div>` : ''}
      ${PERMS().map(([k, l, , d]) => toggleRow('p_' + k, l, d, u[k], `data-perm="${k}" ${dis}`)).join('')}
    </section>
    <section class="msec"><h4><i data-icon="sim"></i>${T('Доступ к SIM-картам', 'SIM card access')}</h4>
      ${toggleRow('uAll', T('Все SIM-карты, включая будущие', 'All SIM cards, including future ones'), T('Новые SIM и профили eSIM станут доступны автоматически', 'New SIMs and eSIM profiles become available automatically'), u.allSims, dis)}
      <div class="pick-wrap" id="uPickWrap">${simPicker(sims, u.sims, 'uSims')}</div>
      <div class="muted small" id="uSimHint"></div>
    </section>
    <div class="form-error" id="uErr"></div>
    <div class="modal-actions"><button type="button" class="btn" data-action="closeModal">${T('Отмена', 'Cancel')}</button><button class="btn primary" type="submit"><i data-icon="check"></i>${isNew ? T('Создать пользователя', 'Create user') : T('Сохранить', 'Save')}</button></div>
    </form>`, (m) => {
    bindPwd(m); bindPicker(m, 'uSims');
    const syncAll = () => {
      const all = $('#uAll', m).checked, p = $('#uSims', m);
      if (p) { p.classList.toggle('disabled', all || isAdm); p.querySelectorAll('button').forEach(b => b.disabled = all || isAdm); }
      const n = pickedSims(m, 'uSims').length;
      $('#uSimHint', m).textContent = all || isAdm ? T('Доступны все SIM-карты устройства.', 'All SIM cards of the device are available.')
        : n ? T('Выбрано SIM-карт: ' + n, 'Selected SIM cards: ' + n) : T('SIM не выбраны — пользователь не сможет отправлять и видеть входящие.', 'No SIMs selected — the user will not be able to send or see incoming messages.');
    };
    $('#uAll', m).onchange = syncAll; const pk = $('#uSims', m); if (pk) pk.addEventListener('change', syncAll); syncAll();
    const preview = () => {
      const tmp = { ...u, displayName: $('#uDn', m).value.trim(), username: $('#uUn', m).value.trim() };
      $('#uAv', m).innerHTML = avatar(tmp.id ? tmp : { ...tmp, id: tmp.username }, 'lg');
      $('#uAt', m).textContent = tmp.username ? '@' + tmp.username : T('заполните данные учётной записи', 'fill in the account details');
    };
    $('#uDn', m).oninput = preview;
    $('#uUn', m).oninput = () => { preview(); const val = $('#uUn', m).value.trim(); if (val.length >= 3) fieldErr(m, 'uUnErr', USERNAME_RE.test(val) ? '' : T('Допустимы только латиница, цифры и . _ -', 'Only latin letters, digits and . _ - are allowed')); };
    $('#uf', m).onsubmit = async (e) => {
      e.preventDefault(); $('#uErr', m).textContent = '';
      const username = $('#uUn', m).value.trim();
      let ok = fieldErr(m, 'uUnErr', USERNAME_RE.test(username) ? '' : T('Логин: 3–32 символа, латиница, цифры, . _ -', 'Username: 3–32 characters, latin letters, digits, . _ -'));
      if (isNew) ok = fieldErr(m, 'uPwErr', $('#uPw', m).value.length >= 8 ? '' : T('Пароль: минимум 8 символов', 'Password: at least 8 characters')) && ok;
      if (!ok) return;
      const body = { username, displayName: $('#uDn', m).value.trim() };
      if (!isAdm) {
        PERMS().forEach(([k]) => { body[k] = $('#p_' + k, m).checked; });
        body.allSims = $('#uAll', m).checked; body.sims = pickedSims(m, 'uSims');
      }
      if (isNew) body.password = $('#uPw', m).value;
      const btn = $('button[type="submit"]', m); btn.disabled = true;
      try {
        if (isNew) await POST('users', body); else await PUT('users/' + encodeURIComponent(u.id), body);
        closeModal(); toast(isNew ? T('Пользователь @' + username + ' создан', 'User @' + username + ' created') : T('Изменения сохранены', 'Changes saved'));
        if (!isNew && ME && u.id === ME.id) { await loadMe().catch(() => {}); renderNav(); }
        done();
      } catch (err) {
        if (err.status === 409) fieldErr(m, 'uUnErr', err.message); else $('#uErr', m).textContent = err.message;
        btn.disabled = false;
      }
    };
    setTimeout(() => $(isNew ? '#uDn' : '#uDn', m).focus(), 30);
  }, 'wide');
}

function resetPwdModal(u) {
  modal(`<div class="modal-head">${avatar(u, 'lg')}<div class="grow"><h3>${T('Новый пароль', 'New password')}</h3><small class="muted">${esc(u.displayName || u.username)} • @${esc(u.username)}</small></div></div>
    <form id="rpf" novalidate>${pwdField('rpPw', T('Новый пароль', 'New password'), T('Минимум 8 символов', 'At least 8 characters'))}<em class="ferr" id="rpErr"></em>
    <p class="muted small">${T('Все активные сеансы пользователя будут завершены.', 'All of the user’s active sessions will be ended.')}</p>
    <div class="form-error" id="rpE"></div>
    <div class="modal-actions"><button type="button" class="btn" data-action="closeModal">${T('Отмена', 'Cancel')}</button><button type="button" class="btn" id="rpCopy"><i data-icon="copy"></i>${T('Скопировать', 'Copy')}</button><button class="btn primary" type="submit"><i data-icon="lock"></i>${T('Сменить пароль', 'Change password')}</button></div></form>`, (m) => {
    bindPwd(m);
    $('#rpCopy', m).onclick = () => { const p = $('#rpPw', m).value; if (p) copyText(p); };
    $('#rpf', m).onsubmit = async (e) => {
      e.preventDefault();
      const p = $('#rpPw', m).value;
      if (!fieldErr(m, 'rpErr', p.length >= 8 ? '' : T('Пароль: минимум 8 символов', 'Password: at least 8 characters'))) return;
      try {
        await POST('users/' + encodeURIComponent(u.id) + '/password', { password: p }); closeModal();
        if (ME && u.id === ME.id) { toast(T('Пароль изменён, войдите снова', 'Password changed, please sign in again')); setTimeout(showLogin, 1200); }
        else toast(T('Пароль @' + u.username + ' изменён', 'Password of @' + u.username + ' changed'));
      } catch (err) { $('#rpE', m).textContent = err.message; }
    };
    setTimeout(() => $('#rpPw', m).focus(), 30);
  });
}

PAGES.users = async (v) => {
  let q = '';
  const draw = async () => {
    const [users, sims] = await Promise.all([GET('users'), GET('sims').catch(() => [])]);
    const list = [...users].sort((a, b) => ((b.role === 'admin') - (a.role === 'admin')) || ((a.createdAt || 0) - (b.createdAt || 0)));
    const active = users.filter(u => u.enabled).length;
    const card = (u) => {
      const adm = u.role === 'admin', me = ME && u.id === ME.id;
      return `<article class="card ucard${u.enabled ? '' : ' off'}" data-uid="${esc(u.id)}" data-q="${esc((u.username + ' ' + (u.displayName || '')).toLowerCase())}">
        <div class="ucard-head">${avatar(u, 'lg')}<div class="grow ucard-name"><b>${esc(u.displayName || u.username)}</b>${me ? `<span class="me-tag">${T('это вы', 'you')}</span>` : ''}<div class="muted small">@${esc(u.username)}</div></div>
          <div class="ucard-badges">${roleBadge(u)}<span class="pill ${u.enabled ? 'em' : 'mu'}">${u.enabled ? T('Активен', 'Active') : T('Отключён', 'Disabled')}</span></div></div>
        <div class="ucard-sec"><span class="lbl">${T('Права', 'Permissions')}</span><div class="chips">${permChips(u)}</div></div>
        <div class="ucard-sec"><span class="lbl">${T('SIM-карты', 'SIM cards')}</span><div class="chips">${simChipsFor(u.sims, u.allSims || adm, sims)}</div></div>
        <div class="ucard-meta"><span title="${esc(u.lastLoginAt ? fmtDate(u.lastLoginAt) : '')}"><i data-icon="clock"></i>${u.lastLoginAt ? esc(ago(u.lastLoginAt)) + (u.lastLoginIp ? ' • <span class="mono">' + esc(u.lastLoginIp) + '</span>' : '') : T('ещё не входил', 'never signed in')}</span>
          <a href="#/keys?owner=${encodeURIComponent(u.id)}"><i data-icon="key"></i>${esc(u.apiKeys || 0)} ${lang === 'en' ? ((u.apiKeys || 0) === 1 ? 'key' : 'keys') : plural(u.apiKeys || 0, 'ключ', 'ключа', 'ключей')}</a></div>
        <div class="ucard-actions">
          <button class="btn sm" data-uedit="${esc(u.id)}"><i data-icon="edit"></i>${T('Изменить', 'Edit')}</button>
          <button class="btn sm" data-upwd="${esc(u.id)}"><i data-icon="lock"></i>${T('Пароль', 'Password')}</button>
          <span class="grow-sp"></span>
          ${adm ? `<span class="muted small" title="${esc(T('Администратора нельзя отключить или удалить', 'The administrator cannot be disabled or deleted'))}"><i data-icon="lock"></i></span>` : `<label class="row en-toggle" title="${esc(u.enabled ? T('Отключить', 'Disable') : T('Включить', 'Enable'))}">${sw('ue_' + esc(u.id), u.enabled, `data-uen="${esc(u.id)}" aria-label="${esc(T('Учётная запись активна', 'Account enabled'))}"`)}</label>
          <button class="icon-btn" data-del data-udel="${esc(u.id)}" title="${esc(T('Удалить', 'Delete'))}" aria-label="${esc(T('Удалить', 'Delete'))}"><i data-icon="trash"></i></button>`}
        </div></article>`;
    };
    v.innerHTML = `<div class="card page-head"><div class="card-head wrap"><span class="stat"><span class="ico tint-in"><i data-icon="users"></i></span></span><div><h3>${T('Пользователи', 'Users')}</h3><div class="muted small">${T('Всего', 'Total')}: ${users.length} • ${T('активных', 'active')}: ${active}</div></div>
        <div class="right"><input id="uq" class="sm-input" type="search" placeholder="${esc(T('Поиск', 'Search'))}" value="${esc(q)}" aria-label="${esc(T('Поиск пользователей', 'Search users'))}"><button class="btn primary sm" id="nu"><i data-icon="plus"></i>${T('Добавить пользователя', 'Add user')}</button></div></div>
        <p class="muted small" style="margin:0">${T('Каждый пользователь видит только назначенные ему SIM-карты и их сообщения, а его API-ключи ограничены теми же правами.', 'Each user sees only the SIM cards assigned to them and their messages; their API keys are limited to the same permissions.')}</p></div>
      <div class="ugrid" id="ug">${list.map(card).join('')}</div>
      <div class="empty hidden" id="uEmpty"><i data-icon="users"></i><div>${T('Никого не найдено', 'Nobody found')}</div></div>`;
    hydrateIcons(v);
    const applyQ = () => { let n = 0; v.querySelectorAll('.ucard').forEach(c => { const hit = !q || c.dataset.q.includes(q); c.classList.toggle('hidden', !hit); n += hit; }); $('#uEmpty').classList.toggle('hidden', n > 0); };
    $('#uq').oninput = (e) => { q = e.target.value.trim().toLowerCase(); applyQ(); }; applyQ();
    $('#nu').onclick = () => userModal(null, sims, draw);
    const byId = (id) => users.find(x => x.id === id);
    v.querySelectorAll('[data-uedit]').forEach(b => b.onclick = () => userModal(byId(b.dataset.uedit), sims, draw));
    v.querySelectorAll('[data-upwd]').forEach(b => b.onclick = () => resetPwdModal(byId(b.dataset.upwd)));
    v.querySelectorAll('[data-uen]').forEach(el => el.onchange = async () => {
      const u = byId(el.dataset.uen);
      try { await PUT('users/' + encodeURIComponent(u.id), { enabled: el.checked }); toast(el.checked ? T('@' + u.username + ' включён', '@' + u.username + ' enabled') : T('@' + u.username + ' отключён — его сеансы завершены', '@' + u.username + ' disabled — their sessions were ended')); draw(); }
      catch (e) { el.checked = !el.checked; toast(e.message, true); }
    });
    v.querySelectorAll('[data-udel]').forEach(b => b.onclick = async () => {
      const u = byId(b.dataset.udel), n = u.apiKeys || 0;
      const txt = T(`Пользователь @${u.username} будет удалён без возможности восстановления.` + (n ? ` Вместе с ним будут удалены его API-ключи (${n}) — внешние системы, использующие их, сразу потеряют доступ.` : ' Его API-ключи (если появятся) также удаляются.'),
        `User @${u.username} will be permanently deleted.` + (n ? ` Their API keys (${n}) will be deleted too — external systems using them will lose access immediately.` : ' Their API keys are deleted along with them.'));
      if (!await confirmBox(T('Удалить пользователя?', 'Delete user?'), txt)) return;
      try { await DEL('users/' + encodeURIComponent(u.id)); toast(T('Пользователь удалён', 'User deleted')); draw(); } catch (e) { toast(e.message, true); }
    });
  };
  await draw();
};

// ---------------------------------------------------------------- profile (everyone)
PAGES.profile = async (v) => {
  await loadMe(); renderNav();
  const u = ME, adm = isAdmin();
  const permRows = PERMS().map(([k, l, ic, d]) => `<div class="opt"><span class="stat"><span class="ico ${u[k] ? 'tint-em' : 'tint-red'}"><i data-icon="${ic}"></i></span></span><div class="t"><b>${esc(l)}</b><small>${esc(d)}</small></div><span class="pill ${u[k] ? 'em' : 'mu'}">${u[k] ? T('Разрешено', 'Allowed') : T('Запрещено', 'Denied')}</span></div>`).join('');
  const simRows = MY_SIMS.length ? MY_SIMS.map(s => `<div class="sim-row" style="--c:${simColor(s)}"><i class="dot"></i><div class="t"><b>${esc(simName(s))}</b><small>${esc(simSub(s) || '—')}</small></div><span class="badges">${slotBadge(s)}${s.embedded ? '<span class="badge esim">eSIM</span>' : ''}</span></div>`).join('')
    : `<div class="muted">${T('SIM-карты не назначены — обратитесь к администратору.', 'No SIM cards assigned — contact the administrator.')}</div>`;
  v.innerHTML = `<div class="hero profile-hero"><div class="row">${avatar(u, 'xl')}<div class="grow"><h1>${esc(u.displayName || u.username)}</h1><div class="muted">@${esc(u.username)}</div><div class="row" style="margin-top:10px;gap:8px">${roleBadge(u)}<span class="pill ${u.enabled ? 'em' : 'mu'}">${u.enabled ? T('Активен', 'Active') : T('Отключён', 'Disabled')}</span></div></div></div>
      <div class="meta"><span><i data-icon="clock"></i>${T('Последний вход', 'Last sign-in')} <b>${u.lastLoginAt ? esc(fmtDate(u.lastLoginAt)) : '—'}</b>${u.lastLoginIp ? ' • <span class="mono">' + esc(u.lastLoginIp) + '</span>' : ''}</span>
      <span><i data-icon="user"></i>${T('Создан', 'Created')} <b>${u.createdAt ? esc(fmtDate(u.createdAt)) : '—'}</b></span>
      <span><i data-icon="key"></i>${T('API-ключей', 'API keys')} <b>${esc(u.apiKeys || 0)}</b></span></div></div>
    <div class="grid g2" style="margin-top:16px">
      <div class="card"><h3><i data-icon="shield"></i>${T('Мои права', 'My permissions')}</h3>${adm ? `<div class="callout info"><i data-icon="info"></i><div>${T('Вы администратор: полный доступ ко всем функциям, настройкам и SIM-картам.', 'You are the administrator: full access to every feature, setting and SIM card.')}</div></div>` : ''}${permRows}</div>
      <div class="card"><h3><i data-icon="sim"></i>${T('Мои SIM-карты', 'My SIM cards')}</h3>${u.allSims ? `<p class="muted small" style="margin-top:-6px">${T('Доступны все SIM-карты, включая будущие.', 'All SIM cards are available, including future ones.')}</p>` : ''}${simRows}
        <div class="row" style="margin-top:14px"><a class="btn sm" href="#/sims"><i data-icon="sim"></i>${T('Открыть SIM-карты', 'Open SIM cards')}</a></div></div>
      <form class="card" id="pf" novalidate autocomplete="off"><h3><i data-icon="lock"></i>${T('Смена пароля', 'Change password')}</h3>
        <label class="field"><span>${T('Текущий пароль', 'Current password')}</span><input id="pc" type="password" autocomplete="current-password"><em class="ferr" id="pcErr"></em></label>
        ${pwdField('pn', T('Новый пароль', 'New password'), T('Минимум 8 символов', 'At least 8 characters'))}<em class="ferr" id="pnErr"></em>
        <label class="field"><span>${T('Повторите новый пароль', 'Repeat new password')}</span><input id="pn2" type="password" autocomplete="new-password"><em class="ferr" id="pn2Err"></em></label>
        <p class="muted small">${T('После смены пароля все ваши сеансы завершатся — войдите заново.', 'After the change all your sessions end — sign in again.')}</p>
        <div class="form-error" id="perr"></div>
        <button class="btn primary" type="submit"><i data-icon="check"></i>${T('Сменить пароль', 'Change password')}</button></form>
      <div class="card"><h3><i data-icon="key"></i>${T('Доступ к API', 'API access')}</h3>
        ${adm || u.canUseApi ? `<p class="muted">${T('Подключайте CRM, сайты и скрипты через REST API с собственными ключами. Ключ работает только в пределах ваших прав и SIM-карт.', 'Connect CRMs, websites and scripts via the REST API with your own keys. A key works only within your permissions and SIM cards.')}</p>
          <div class="row"><a class="btn primary sm" href="#/keys"><i data-icon="key"></i>${T('Мои API-ключи', 'My API keys')} (${esc(u.apiKeys || 0)})</a><a class="btn sm" href="#/docs"><i data-icon="book"></i>${T('Документация', 'Documentation')}</a></div>`
        : `<div class="callout info"><i data-icon="lock"></i><div>${T('Доступ к API для вашей учётной записи отключён администратором.', 'API access for your account is disabled by the administrator.')}</div></div>`}
        ${adm ? `<div class="row" style="margin-top:12px"><a class="btn sm" href="#/users"><i data-icon="users"></i>${T('Управление пользователями', 'Manage users')}</a></div>` : ''}</div>
    </div>`;
  bindPwd(v);
  $('[data-pwd-gen="pn"]').addEventListener('click', () => { $('#pn2').value = $('#pn').value; $('#pn2').type = 'text'; });
  $('#pf').onsubmit = async (e) => {
    e.preventDefault(); $('#perr').textContent = '';
    const cur = $('#pc').value, nw = $('#pn').value;
    let ok = fieldErr(v, 'pcErr', cur ? '' : T('Введите текущий пароль', 'Enter your current password'));
    ok = fieldErr(v, 'pnErr', nw.length >= 8 ? '' : T('Минимум 8 символов', 'At least 8 characters')) && ok;
    ok = fieldErr(v, 'pn2Err', $('#pn2').value === nw ? '' : T('Пароли не совпадают', 'Passwords do not match')) && ok;
    if (!ok) return;
    try { await POST('password', { current: cur, new: nw }); toast(T('Пароль изменён, войдите снова', 'Password changed, please sign in again')); setTimeout(showLogin, 1200); }
    catch (err) { if (err.status === 403) fieldErr(v, 'pcErr', err.message); else $('#perr').textContent = err.message; }
  };
};

const EVENTS = () => [['sms.received', T('Входящее SMS', 'Incoming SMS')], ['sms.sent', T('Отправлено', 'Sent')], ['sms.delivered', T('Доставлено', 'Delivered')], ['sms.failed', T('Ошибка отправки', 'Sending failed')],
  ['ussd.result', T('Результат USSD', 'USSD result')], ['call.ringing', T('Входящий звонок', 'Incoming call')], ['call.missed', T('Пропущенный звонок', 'Missed call')], ['call.ended', T('Звонок завершён', 'Call ended')]];
PAGES.webhooks = async (v) => {
  const draw = async () => {
    const hooks = await GET('webhooks');
    v.innerHTML = `<div class="card"><div class="card-head"><h3><i data-icon="hook"></i>${T('Вебхуки', 'Webhooks')}</h3><div class="right"><button class="btn primary sm" id="nh"><i data-icon="plus"></i>${T('Добавить', 'Add')}</button></div></div>
      <p class="muted" style="margin-top:-6px">${T('Шлюз отправляет POST с JSON на ваш URL при событиях. Запросы подписаны HMAC-SHA256, при ошибке — до 5 повторов с нарастающей задержкой.', 'The gateway sends a JSON POST to your URL on events. Requests are signed with HMAC-SHA256; on failure there are up to 5 retries with increasing delay.')}</p>
      ${hooks.length ? hooks.map(h => `<div class="opt"><span class="stat"><span class="ico tint-am"><i data-icon="hook"></i></span></span>
        <div class="t"><b class="mono" style="word-break:break-all">${esc(h.url)}</b><small>${h.events.map(e => `<span class="tag">${esc(e)}</span>`).join('')}</small></div>
        <button class="btn sm" data-test="${esc(h.id)}">${T('Тест', 'Test')}</button><button class="btn sm" data-secret="${esc(h.id)}">${T('Секрет', 'Secret')}</button>${sw('h' + esc(h.id), h.enabled, `data-toggle="${esc(h.id)}"`)}<button class="icon-btn" title="${T('Удалить', 'Delete')}" data-del="${esc(h.id)}"><i data-icon="trash"></i></button></div>`).join('')
      : `<div class="empty"><i data-icon="hook"></i><div>${T('Вебхуков нет', 'No webhooks')}</div></div>`}</div>
      <div class="card" style="margin-top:16px"><h3>${T('Формат запроса', 'Request format')}</h3>
      <div class="code">POST https://your-server/hook
<span class="k">X-ISMS-Event</span>: sms.received
<span class="k">X-ISMS-Timestamp</span>: 1760000000
<span class="k">X-ISMS-Signature</span>: sha256=<span class="c">HMAC_SHA256(secret, timestamp + "." + body)</span>

{ <span class="s">"event"</span>: "sms.received", <span class="s">"deviceId"</span>: "…", <span class="s">"timestamp"</span>: 1760000000000,
  <span class="s">"data"</span>: { "id": 42, "direction": "in", "phone": "+79991234567", "text": "${T('Привет', 'Hello')}", "sim": 1, "simId": 3, "status": "received", "createdAt": … } }</div></div>`;
    hydrateIcons(v);
    $('#nh').onclick = () => modal(`<h3>${T('Новый вебхук', 'New webhook')}</h3><label class="field"><span>URL</span><input id="hu" placeholder="https://example.com/sms-hook"></label>
      <div class="field"><span>${T('События', 'Events')}</span>${EVENTS().map(([k, l]) => `<label class="row"><input type="checkbox" value="${k}" class="he" ${k === 'sms.received' ? 'checked' : ''} style="width:auto"> ${esc(l)} <span class="tag">${k}</span></label>`).join('')}</div>
      <label class="field"><span>${T('Секрет подписи', 'Signing secret')}</span><input id="hs" placeholder="${T('сгенерируется автоматически', 'generated automatically')}"></label><div class="form-error" id="herr"></div>
      <div class="modal-actions"><button class="btn" data-action="closeModal">${T('Отмена', 'Cancel')}</button><button class="btn primary" id="hc">${T('Добавить', 'Add')}</button></div>`, (m) => {
      $('#hc', m).onclick = async () => {
        try { await POST('webhooks', { url: $('#hu', m).value, secret: $('#hs', m).value, events: [...m.querySelectorAll('.he:checked')].map(x => x.value) }); closeModal(); draw(); }
        catch (e) { $('#herr', m).textContent = e.message; }
      };
    });
    v.querySelectorAll('[data-toggle]').forEach(el => el.onchange = () => PUT('webhooks/' + el.dataset.toggle, { enabled: el.checked }).catch(e => toast(e.message, true)));
    v.querySelectorAll('[data-del]').forEach(el => el.onclick = async () => { if (await confirmBox(T('Удалить вебхук?', 'Delete webhook?'), T('События перестанут отправляться на этот адрес.', 'Events will no longer be sent to this address.'))) { await DEL('webhooks/' + el.dataset.del); draw(); } });
    v.querySelectorAll('[data-test]').forEach(el => el.onclick = async () => {
      el.disabled = true;
      try { const r = await POST(`webhooks/${el.dataset.test}/test`); r.delivered ? toast(T('Тестовый запрос доставлен', 'Test request delivered')) : toast(T('Ошибка: ', 'Error: ') + r.error, true); }
      catch (e) { toast(e.message, true); } finally { el.disabled = false; }
    });
    v.querySelectorAll('[data-secret]').forEach(el => el.onclick = () => {
      const h = hooks.find(x => x.id === el.dataset.secret);
      modal(`<h3>${T('Секрет подписи', 'Signing secret')}</h3><div class="secret">${esc(h.secret)}</div><div class="modal-actions"><button class="btn" data-action="closeModal">${T('Закрыть', 'Close')}</button><button class="btn primary" id="cp">${T('Скопировать', 'Copy')}</button></div>`, (m) => { $('#cp', m).onclick = () => copyText(h.secret); });
    });
  };
  await draw();
};

// Parses a "connect to SMS server" link/QR text (mirrors net/ConnectLink.kt): the connect link,
// an App Links URL with the data after "#", or the legacy two-line "URL\ntoken" QR.
function parseConnect(text) {
  const s = (text || '').trim();
  if (!s) return { error: T('Пустая ссылка', 'Empty link') };
  const TOKEN = /^smsgw_[A-Za-z0-9]{8}_[A-Za-z0-9]{32}$/;
  const URLRE = /^(wss|ws|https|http):\/\/.+/i;
  const dec = (x) => { try { return decodeURIComponent(x); } catch (e) { return x; } };
  const params = (q) => { const m = {}; (q || '').split('&').forEach((p) => { if (!p) return; const i = p.indexOf('='); const k = dec(i < 0 ? p : p.slice(0, i)); const val = i < 0 ? '' : dec(p.slice(i + 1)); if (k) m[k] = val; }); return m; };
  const build = (url, token, name) => {
    url = (url || '').trim(); token = (token || '').trim();
    if (!url || !token) return { error: T('Не удалось распознать данные подключения', 'Could not read the connection data') };
    if (!URLRE.test(url)) return { error: T('Неверный адрес сервера', 'Invalid server address') };
    const scheme = url.split('://')[0].toLowerCase();
    name = ((name || '').trim().slice(0, 120)) || null;
    return { url, token, name, insecure: scheme === 'ws' || scheme === 'http', knownToken: TOKEN.test(token) };
  };
  if (/^ismsgw:\/\//i.test(s)) {
    const p = params(s.split('?')[1] || '');
    if (p.v && parseInt(p.v, 10) !== 1) return { error: T('Обновите приложение', 'Please update the app') };
    return build(p.url, p.token, p.name);
  }
  if (/^https?:\/\//i.test(s) && s.indexOf('#') >= 0 && /url=|token=/i.test(s.split('#')[1] || '')) {
    const p = params(s.split('#')[1] || '');
    if (p.v && parseInt(p.v, 10) !== 1) return { error: T('Обновите приложение', 'Please update the app') };
    return build(p.url, p.token, p.name);
  }
  const parts = s.split(/\s+/).filter(Boolean);
  if (parts.length < 2) return { error: T('Не удалось распознать данные подключения', 'Could not read the connection data') };
  return build(parts[0], parts[1], null);
}

PAGES.remote = async (v) => {
  const draw = async () => {
    const r = await GET('remote');
    const st = { connected: [T('Подключено', 'Connected'),'em'], connecting: [T('Подключение…', 'Connecting…'),'am'], disconnected: [T('Нет связи, переподключение', 'Disconnected, reconnecting'),'am'], disabled: [T('Отключено', 'Disabled'),'mu'] }[r.state] || [r.state, 'mu'];
    v.innerHTML = `<div class="grid g2">
      <div class="card"><div class="card-head"><h3><i data-icon="cloud"></i>${T('Подключение к SMS-серверу', 'SMS server connection')}</h3><div class="right"><span class="pill ${st[1]}">${esc(st[0])}</span></div></div>
        <p class="muted" style="margin-top:-6px">${T('Телефон сам устанавливает постоянное WebSocket-соединение с вашим сервером: работает через мобильный интернет и NAT, не требует белого IP и открытых портов.', 'The phone itself keeps a persistent WebSocket connection to your server: it works over mobile internet and NAT, and needs no public IP or open ports.')}</p>
        ${r.error ? `<div class="callout"><i data-icon="alert"></i><div>${esc(r.error)}</div></div>` : ''}
        <div class="opt"><div class="t"><b>${T('Включить подключение', 'Enable connection')}</b></div>${sw('ren', r.enabled)}</div>
        <label class="field" style="margin-top:12px"><span>${T('Вставить ссылку подключения', 'Paste connection link')}</span><input id="rlink" placeholder="ismsgw://connect?v=1&url=…&token=…"><small>${T('Вставьте ссылку или текст QR-кода из мастера сервера — адрес и токен заполнятся сами', 'Paste the link or QR text from the server wizard — the address and token fill in automatically')}</small></label>
        <div class="muted small" id="rlinkmsg" style="margin:-4px 0 8px"></div>
        <label class="field"><span>${T('URL сервера', 'Server URL')}</span><input id="rurl" value="${esc(r.url)}" placeholder="wss://sms.example.com/device"></label>
        <label class="field"><span>${T('Токен устройства', 'Device token')}</span><input id="rtok" value="${esc(r.token)}" placeholder="${T('выдаётся вашим сервером', 'issued by your server')}"><small>${T('Передаётся в заголовке Authorization: Bearer', 'Sent in the Authorization: Bearer header')}</small></label>
        <div class="form-error" id="rerr"></div>
        <button class="btn primary" id="rsave"><i data-icon="check"></i>${T('Сохранить и переподключиться', 'Save and reconnect')}</button>
      </div>
      <div class="card"><h3>${T('Протокол', 'Protocol')}</h3>
        <div class="code"><span class="c">// ${T('сервер → телефон', 'server → phone')}</span>
{ "type": "send", "id": "ext-1", "to": ["+7999…"], "text": "${T('Код: 1234', 'Code: 1234')}", "sim": 1 }
{ "type": "ack_event", "messageId": 42 }
{ "type": "get_status" }   { "type": "ping" }

<span class="c">// ${T('телефон → сервер', 'phone → server')}</span>
{ "type": "hello", "deviceId": "…", "sims": [ … ] }
{ "type": "ack", "id": "ext-1", "messageId": 57, "status": "queued" }
{ "type": "event", "event": "sms.received", "data": { … } }
{ "type": "event", "event": "sms.delivered", "data": { … } }</div>
        <p class="muted small">${T('Входящие SMS повторно отправляются при переподключении, пока сервер не подтвердит их через', 'Incoming SMS are re-sent after reconnecting until the server confirms them with')} <span class="tag">ack_event</span> — ${T('сообщения не теряются при обрыве связи.', 'no messages are lost when the connection drops.')}</p>
      </div></div>`;
    hydrateIcons(v);
    const applyLink = () => {
      const msg = $('#rlinkmsg'); const text = $('#rlink').value;
      if (!text.trim()) { msg.textContent = ''; return; }
      const p = parseConnect(text);
      if (p.error) { msg.style.color = 'var(--red)'; msg.textContent = p.error; return; }
      // Same rules as the app: fill the fields, never apply without the Save (confirmation) below.
      $('#rurl').value = p.url; $('#rtok').value = p.token; $('#ren').checked = true;
      const warns = [];
      if (p.insecure) warns.push(T('соединение не шифруется', 'the connection is not encrypted'));
      if (!p.knownToken) warns.push(T('необычный токен', 'unusual token'));
      msg.style.color = warns.length ? 'var(--amber)' : 'var(--em)';
      msg.textContent = (p.name ? T('Имя устройства: ', 'Device name: ') + p.name + '. ' : '')
        + (warns.length ? T('Внимание: ', 'Warning: ') + warns.join(', ') + '. ' : '')
        + T('Проверьте адрес и нажмите «Сохранить».', 'Check the address and press “Save”.');
    };
    $('#rlink').oninput = applyLink;
    $('#rsave').onclick = async () => {
      try { await PUT('remote', { enabled: $('#ren').checked, url: $('#rurl').value.trim(), token: $('#rtok').value.trim() }); toast(T('Сохранено', 'Saved')); setTimeout(draw, 1500); }
      catch (e) { $('#rerr').textContent = e.message; }
    };
  };
  await draw();
};

PAGES.telegram = async (v) => {
  const draw = async () => {
    const t = await GET('telegram');
    const st = { online: [T('Онлайн', 'Online'),'em'], connecting: [T('Подключение…', 'Connecting…'),'am'], error: [T('Ошибка связи', 'Connection error'),'red'], disabled: [t.enabled ? T('Запустится со шлюзом', 'Starts with the gateway') : T('Выключен', 'Off'),'mu'] }[t.state] || [t.state,'mu'];
    const cmds = lang === 'en'
      ? `/status        gateway status
/send &lt;number&gt; &lt;text&gt;   send an SMS
/send1, /send2 via a specific SIM
/last [N]      recent messages
/queue         queue
/sims          SIM cards
/server on|off local server
/ussd *100# [sim] USSD request
/balance       balance of all SIMs
/calls         recent calls
/unpair        unlink this chat
<span class="c">↩ reply to an SMS notification — answers the sender</span>`
      : `/status        состояние шлюза
/send &lt;номер&gt; &lt;текст&gt;   отправить SMS
/send1, /send2 через конкретную SIM
/last [N]      последние сообщения
/queue         очередь
/sims          SIM-карты
/server on|off локальный сервер
/ussd *100# [sim] USSD-запрос
/balance       баланс всех SIM
/calls         последние звонки
/unpair        отвязать чат
<span class="c">↩ reply на уведомление о SMS — ответ отправителю</span>`;
    v.innerHTML = `<div class="grid g2">
      <div class="card"><div class="card-head"><h3><i data-icon="tg"></i>${T('Telegram-бот', 'Telegram bot')}${t.bot ? ' @' + esc(t.bot) : ''}</h3><div class="right"><span class="pill ${st[1]}">${esc(st[0])}</span></div></div>
        <p class="muted" style="margin-top:-6px">${T('Уведомления о SMS и управление шлюзом из Telegram. Бот работает через long polling — телефону не нужен белый IP.', 'SMS notifications and gateway control from Telegram. The bot uses long polling — the phone needs no public IP.')}</p>
        ${t.error ? `<div class="callout"><i data-icon="alert"></i><div>${esc(t.error)}</div></div>` : ''}
        <div class="opt"><div class="t"><b>${T('Включить бота', 'Enable bot')}</b></div>${sw('ten', t.enabled)}</div>
        <label class="field" style="margin-top:12px"><span>${T('Токен бота', 'Bot token')}</span><input id="ttok" value="${esc(t.token)}" placeholder="123456789:AA…" autocomplete="off"><small>${T('Создайте бота у @BotFather командой /newbot и вставьте токен', 'Create a bot with @BotFather using /newbot and paste the token')}</small></label>
        <div class="opt"><div class="t"><b>${T('Входящие SMS', 'Incoming SMS')}</b><small>${T('Пересылать текст входящих в Telegram', 'Forward incoming message text to Telegram')}</small></div>${sw('tin', t.notifyIncoming)}</div>
        <div class="opt"><div class="t"><b>${T('Ошибки отправки', 'Sending errors')}</b></div>${sw('tfa', t.notifyFailed)}</div>
        <div class="opt"><div class="t"><b>${T('Доставка', 'Delivery')}</b><small>${T('Уведомление о каждом доставленном SMS', 'A notification for every delivered SMS')}</small></div>${sw('tde', t.notifyDelivered)}</div>
        <div class="opt"><div class="t"><b>${T('Отправка SMS из бота', 'Send SMS from the bot')}</b><small>${T('Команды /send и ответы на уведомления', '/send commands and replies to notifications')}</small></div>${sw('tse', t.allowSend)}</div>
        <div class="form-error" id="terr"></div>
        <button class="btn primary" id="tsave"><i data-icon="check"></i>${T('Сохранить', 'Save')}</button>
      </div>
      <div class="card"><h3><i data-icon="lock"></i>${T('Привязка чата', 'Chat pairing')}</h3>
        <p class="muted">${T('Бот отвечает только привязанным чатам. Отправьте боту команду (код одноразовый, после привязки меняется):', 'The bot replies only to paired chats. Send this command to the bot (the code is single-use and changes after pairing):')}</p>
        <div class="secret" style="font-size:16px">/start ${esc(t.pairingCode)}</div>
        <div class="row" style="margin:12px 0 20px"><button class="btn sm" id="tcp"><i data-icon="copy"></i>${T('Скопировать', 'Copy')}</button><button class="btn sm" id="tnew"><i data-icon="retry"></i>${T('Новый код', 'New code')}</button></div>
        <h3>${T('Привязанные чаты', 'Paired chats')}</h3>
        ${t.chats.length ? t.chats.map(c => `<div class="opt"><div class="t"><b>${esc(c.name)}</b><small>ID ${esc(c.id)} • ${T('с', 'since')} ${fmtDate(c.pairedAt)}</small></div><button class="btn danger sm" data-unpair="${esc(c.id)}">${T('Отвязать', 'Unpair')}</button></div>`).join('') : `<div class="muted">${T('Пока нет', 'None yet')}</div>`}
        <h3 style="margin-top:20px">${T('Команды', 'Commands')}</h3>
        <div class="code">${cmds}</div>
        <div class="callout" style="margin-top:14px"><i data-icon="info"></i><div>${T('Текст SMS проходит через серверы Telegram. Не включайте пересылку, если сообщения содержат чувствительные данные.', 'SMS text passes through Telegram servers. Do not enable forwarding if messages contain sensitive data.')}</div></div>
      </div></div>`;
    hydrateIcons(v);
    $('#tsave').onclick = async () => {
      $('#terr').textContent = '';
      try {
        await PUT('telegram', { enabled: $('#ten').checked, token: $('#ttok').value.trim(), notifyIncoming: $('#tin').checked,
          notifyFailed: $('#tfa').checked, notifyDelivered: $('#tde').checked, allowSend: $('#tse').checked });
        toast(T('Сохранено', 'Saved')); setTimeout(() => draw().catch(() => {}), 2500);
      } catch (e) { $('#terr').textContent = e.message; }
    };
    $('#tcp').onclick = () => copyText('/start ' + t.pairingCode);
    $('#tnew').onclick = async () => { await POST('telegram/code'); draw(); };
    v.querySelectorAll('[data-unpair]').forEach(b => b.onclick = async () => { if (await confirmBox(T('Отвязать чат?', 'Unpair chat?'), T('Чат перестанет получать уведомления и управлять шлюзом.', 'The chat will stop receiving notifications and controlling the gateway.'), T('Отвязать', 'Unpair'))) { await DEL('telegram/chats/' + b.dataset.unpair); draw(); } });
  };
  await draw();
};

PAGES.destinations = async (v) => {
  const cfg = await GET('settings');
  const d = Object.assign({ enabled: true, blocklist: [], allowlistOnly: false, allowlist: [], blockShortCodes: false, allowLocal: true, defaultCountryCode: '', trunkPrefix: '' }, (cfg.sending || {}).destinations || {});
  const lines = (a) => esc((a || []).join('\n'));
  const PRESETS = [
    ['africa', T('Африка', 'Africa'), ['+20 # Egypt', '+21 # North Africa', '+22 # West Africa', '+23 # West/Central Africa', '+24 # Central Africa', '+25 # East Africa', '+26 # Southern Africa', '+27 # South Africa', '+290 # Saint Helena', '+291 # Eritrea']],
    ['sat', T('Спутниковые и межд. сети', 'Satellite & international networks'), ['+870 # Inmarsat', '+871', '+872', '+873', '+874', '+881 # Global mobile satellite', '+882 # International networks', '+883 # International networks', '+979 # International premium rate']],
    ['premium', T('Платные зоны', 'Premium rate zones'), ['+1900 # US premium', '+44 9 # UK premium', '+7 809 # RU premium']],
  ];
  v.innerHTML = `<div class="callout info"><i data-icon="globe"></i><div>${T('Фильтр проверяет каждое исходящее SMS — из API, веб-панели, Telegram, SMS-сервера и приложения — до отправки. Правила — префиксы номеров в международном формате: <span class="tag">+2</span> (вся Африка), <span class="tag">+44 7700</span>, <span class="tag">+1 202 555 0147</span>. Чёрный список всегда приоритетнее. Текст после <span class="tag">#</span> — комментарий.', 'Every outgoing SMS — from the API, web panel, Telegram, SMS server and the app — is checked before sending. Rules are number prefixes in international format: <span class="tag">+2</span> (all of Africa), <span class="tag">+44 7700</span>, <span class="tag">+1 202 555 0147</span>. The blocklist always wins. Text after <span class="tag">#</span> is a comment.')}</div></div>
  <div class="grid g2">
    <div class="card"><h3><i data-icon="ban"></i>${T('Запрещённые направления', 'Blocked destinations')}</h3>
      <div class="opt"><div class="t"><b>${T('Фильтр направлений включён', 'Destination filter enabled')}</b><small>${T('Выключение снимает все ограничения', 'Turning it off removes all restrictions')}</small></div>${sw('dEn', d.enabled)}</div>
      <label class="field" style="margin-top:12px"><span>${T('Чёрный список (по одному на строку)', 'Blocklist (one per line)')}</span><textarea id="dBl" rows="8" placeholder="+2&#10;+88">${lines(d.blocklist)}</textarea></label>
      <div class="field"><span>${T('Быстро добавить', 'Quick add')}</span><div class="row">${PRESETS.map(([k, l]) => `<button type="button" class="chip-btn" data-preset="${k}"><i data-icon="plus"></i>${esc(l)}</button>`).join('')}</div></div>
      <div class="opt"><div class="t"><b>${T('Блокировать короткие и платные номера', 'Block short and premium codes')}</b><small>${T('Номера из 3–6 цифр (сервисные, подписки)', '3–6 digit numbers (services, subscriptions)')}</small></div>${sw('dShort', d.blockShortCodes)}</div>
    </div>
    <div class="card"><h3><i data-icon="check"></i>${T('Разрешённые направления', 'Allowed destinations')}</h3>
      <div class="opt"><div class="t"><b>${T('Только белый список', 'Allowlist only')}</b><small>${T('SMS уходят только на перечисленные коды и номера', 'SMS go only to the listed codes and numbers')}</small></div>${sw('dAo', d.allowlistOnly)}</div>
      <label class="field" style="margin-top:12px"><span>${T('Белый список (по одному на строку)', 'Allowlist (one per line)')}</span><textarea id="dAl" rows="5" placeholder="+44&#10;+33 6">${lines(d.allowlist)}</textarea></label>
      <div class="grid g2" style="gap:12px">
        <label class="field"><span>${T('Код страны для местных номеров', 'Country code for local numbers')}</span><input id="dCc" value="${esc(d.defaultCountryCode)}" placeholder="44" inputmode="numeric"><small>${T('Номер 07700… станет +447700…', '07700… becomes +447700…')}</small></label>
        <label class="field"><span>${T('Местный префикс', 'Trunk prefix')}</span><input id="dTp" value="${esc(d.trunkPrefix)}" placeholder="0" inputmode="numeric"><small>${T('Отбрасывается: 0 (EU/UK), 8 (RU)', 'Dropped: 0 (EU/UK), 8 (RU)')}</small></label>
      </div>
      <div class="opt"><div class="t"><b>${T('Разрешать номера без международного кода', 'Allow numbers without an international code')}</b><small>${T('Если код страны не задан', 'When no country code is set')}</small></div>${sw('dLoc', d.allowLocal)}</div>
    </div>
  </div>
  <div class="card" style="margin-top:16px"><h3><i data-icon="globe"></i>${T('Проверить номер', 'Check a number')}</h3>
    <div class="row"><input id="dChk" class="grow" placeholder="+234 803 123 4567"><button class="btn" id="dChkBtn">${T('Проверить', 'Check')}</button></div>
    <div id="dChkOut" style="margin-top:12px"></div>
  </div>
  <div style="margin:16px 0"><div class="form-error" id="dErr"></div><button class="btn primary" id="dSave"><i data-icon="check"></i>${T('Сохранить правила', 'Save rules')}</button></div>`;
  hydrateIcons(v);
  const split = (t) => t.split('\n').map(x => x.trim()).filter(Boolean);
  v.querySelectorAll('[data-preset]').forEach(b => b.onclick = () => {
    const p = PRESETS.find(x => x[0] === b.dataset.preset); const ta = $('#dBl'); const cur = split(ta.value);
    const have = new Set(cur.map(x => x.split('#')[0].trim()));
    ta.value = cur.concat(p[2].filter(x => !have.has(x.split('#')[0].trim()))).join('\n');
    toast(T('Добавлено — не забудьте сохранить', 'Added — remember to save'));
  });
  $('#dChkBtn').onclick = async () => {
    const phone = $('#dChk').value.trim(); if (!phone) return;
    try {
      const r = await POST('destinations/check', { phone });
      $('#dChkOut').innerHTML = `<div class="callout ${r.allowed ? 'info' : ''}"><i data-icon="${r.allowed ? 'check' : 'ban'}"></i><div><b>${r.allowed ? T('Разрешено', 'Allowed') : T('Заблокировано', 'Blocked')}</b> · <span class="mono">${esc(r.normalized)}</span><br><span class="small">${esc(r.reason)}${r.rule ? ' — <span class="tag">' + esc(r.rule) + '</span>' : ''}</span></div></div>`;
      hydrateIcons($('#dChkOut'));
    } catch (e) { toast(e.message, true); }
  };
  $('#dSave').onclick = async () => {
    $('#dErr').textContent = '';
    try {
      await PUT('settings', { destinations: { enabled: $('#dEn').checked, blocklist: split($('#dBl').value), allowlistOnly: $('#dAo').checked, allowlist: split($('#dAl').value),
        blockShortCodes: $('#dShort').checked, allowLocal: $('#dLoc').checked, defaultCountryCode: $('#dCc').value.trim(), trunkPrefix: $('#dTp').value.trim() } });
      toast(T('Правила фильтра сохранены', 'Filter rules saved'));
    } catch (e) { $('#dErr').textContent = e.message; }
  };
};

PAGES.firewall = async (v) => {
  const [cfg, bans] = await Promise.all([GET('settings'), GET('firewall/bans')]);
  const fw = cfg.firewall;
  const lines = (a) => esc(a.join('\n'));
  v.innerHTML = `<div class="callout info"><i data-icon="shield"></i><div>${T('Правила применяются до проверки ключей и паролей. Поддерживаются IPv4/IPv6 и подсети CIDR (например', 'Rules are applied before keys and passwords are checked. IPv4/IPv6 and CIDR subnets are supported (e.g.')} <span class="tag">192.168.1.0/24</span>). ${T('Ваш текущий IP защищён от случайной самоблокировки.', 'Your current IP is protected from accidentally locking yourself out.')}</div></div>
  <div class="grid g2">
    <div class="card"><h3><i data-icon="shield"></i>${T('Политика доступа', 'Access policy')}</h3>
      <div class="seg" id="mode">${[['lan', T('Только LAN', 'LAN only')], ['whitelist', T('Белый список', 'Whitelist')], ['all', T('Все', 'Everyone')]].map(([k,l]) => `<button data-m="${k}" class="${fw.mode === k ? 'on' : ''}">${l}</button>`).join('')}</div>
      <p class="muted small" id="modeHint" style="margin:10px 0 16px"></p>
      <label class="field"><span>${T('Белый список (по одному на строку)', 'Whitelist (one per line)')}</span><textarea id="wl" rows="4">${lines(fw.whitelist)}</textarea><small>${T('В режиме LAN — дополнительно разрешённые внешние адреса', 'In LAN mode — additionally allowed external addresses')}</small></label>
      <label class="field"><span>${T('Чёрный список', 'Blacklist')}</span><textarea id="bl" rows="3">${lines(fw.blacklist)}</textarea><small>${T('Блокируется всегда, в любом режиме', 'Always blocked, in any mode')}</small></label>
    </div>
    <div class="card"><h3><i data-icon="lock"></i>${T('Веб-панель и защита от перебора', 'Web panel and brute-force protection')}</h3>
      <div class="opt"><div class="t"><b>${T('Панель только из локальной сети', 'Panel from local network only')}</b><small>${T('Даже если API открыт для внешних адресов', 'Even if the API is open to external addresses')}</small></div>${sw('alo', fw.adminLanOnly)}</div>
      <label class="field" style="margin-top:12px"><span>${T('Белый список панели (необязательно)', 'Panel whitelist (optional)')}</span><textarea id="awl" rows="2">${lines(fw.adminWhitelist)}</textarea></label>
      <div class="grid g3" style="gap:12px">
        <label class="field"><span>${T('Запросов/мин с IP', 'Requests/min per IP')}</span><input id="rl" type="number" min="0" value="${esc(fw.rateLimitPerMinute)}"></label>
        <label class="field"><span>${T('Ошибок входа до бана', 'Failed logins before ban')}</span><input id="mf" type="number" min="0" value="${esc(fw.maxAuthFailures)}"></label>
        <label class="field"><span>${T('Бан, минут', 'Ban, minutes')}</span><input id="bm" type="number" min="1" value="${esc(fw.banMinutes)}"></label>
      </div>
    </div>
  </div>
  <div style="margin:16px 0"><div class="form-error" id="ferr"></div><button class="btn primary" id="fsave"><i data-icon="check"></i>${T('Применить правила', 'Apply rules')}</button></div>
  <div class="card"><h3><i data-icon="ban"></i>${T('Заблокированные адреса', 'Blocked addresses')}</h3><div id="bans"></div></div>`;
  let mode = fw.mode;
  const hints = {
    lan: T('Доступ только из частных сетей (10.x, 172.16–31.x, 192.168.x, IPv6 ULA/link-local) и адресов белого списка. Рекомендуется.', 'Access only from private networks (10.x, 172.16–31.x, 192.168.x, IPv6 ULA/link-local) and whitelisted addresses. Recommended.'),
    whitelist: T('Доступ только с перечисленных адресов. Самый строгий режим.', 'Access only from the listed addresses. The strictest mode.'),
    all: T('Доступ с любых адресов. Используйте только за VPN или с HTTPS и ограничением ключей по IP!', 'Access from any address. Use only behind a VPN, or with HTTPS and per-key IP restrictions!'),
  };
  const setMode = (m) => { mode = m; v.querySelectorAll('#mode button').forEach(b => b.classList.toggle('on', b.dataset.m === m)); $('#modeHint').textContent = hints[m] || ''; };
  setMode(mode);
  $('#mode').onclick = (e) => { const b = e.target.closest('button'); if (b) setMode(b.dataset.m); };
  const split = (s) => s.split(/[\n,\s]+/).map(x => x.trim()).filter(Boolean);
  $('#fsave').onclick = async () => {
    $('#ferr').textContent = '';
    try {
      await PUT('settings', { firewall: { mode, whitelist: split($('#wl').value), blacklist: split($('#bl').value), adminLanOnly: $('#alo').checked,
        adminWhitelist: split($('#awl').value), rateLimitPerMinute: +$('#rl').value, maxAuthFailures: +$('#mf').value, banMinutes: +$('#bm').value } });
      toast(T('Правила файрвола применены', 'Firewall rules applied'));
    } catch (e) { $('#ferr').textContent = e.message; }
  };
  const drawBans = (list) => {
    $('#bans').innerHTML = list.length ? `<div class="table-wrap"><table><thead><tr><th>IP</th><th>${T('Причина', 'Reason')}</th><th>${T('До', 'Until')}</th><th></th></tr></thead><tbody>${list.map(b => `<tr><td class="mono">${esc(b.ip)}</td><td>${esc(b.reason)}</td><td class="small muted">${fmtDate(b.until)}</td><td><button class="btn sm" data-unban="${esc(b.ip)}">${T('Разблокировать', 'Unblock')}</button></td></tr>`).join('')}</tbody></table></div>` : `<div class="muted">${T('Блокировок нет', 'No blocks')}</div>`;
    $('#bans').querySelectorAll('[data-unban]').forEach(el => el.onclick = async () => drawBans(await DEL('firewall/bans/' + encodeURIComponent(el.dataset.unban))));
  };
  drawBans(bans);
};

PAGES.logs = async (v) => {
  let cat = '';
  const cats = [['', T('Все', 'All')], ['security', T('Безопасность', 'Security')], ['sms', 'SMS'], ['api', 'API'], ['webhook', T('Вебхуки', 'Webhooks')], ['remote', T('SMS-сервер', 'SMS server')], ['server', T('Сервер', 'Server')], ['service', T('Сервис', 'Service')]];
  v.innerHTML = `<div class="card"><div class="row" style="margin-bottom:12px">
    <div class="seg" id="cats">${cats.map(([k,l]) => `<button data-c="${k}" class="${k === '' ? 'on' : ''}">${l}</button>`).join('')}</div>
    <button class="btn danger sm" id="lclear" style="margin-left:auto"><i data-icon="trash"></i>${T('Очистить', 'Clear')}</button></div><div id="ll"></div></div>`;
  const load = async () => {
    const list = await GET('logs?limit=300' + (cat ? '&category=' + cat : ''));
    $('#ll').innerHTML = list.length ? list.map(l => `<div class="log-line"><span class="ts">${fmtDate(l.ts)}</span><span class="lvl-${esc(l.level)}">${esc(l.level)}</span><span class="tag">${esc(l.category)}</span><span>${esc(l.message)}</span></div>`).join('') : `<div class="empty">${T('Журнал пуст', 'The log is empty')}</div>`;
  };
  $('#cats').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; cat = b.dataset.c; v.querySelectorAll('#cats button').forEach(x => x.classList.toggle('on', x === b)); load(); };
  $('#lclear').onclick = async () => { if (await confirmBox(T('Очистить журнал?', 'Clear the log?'), T('Все записи журнала будут удалены.', 'All log entries will be deleted.'), T('Очистить', 'Clear'))) { await DEL('logs'); load(); } };
  await load();
  timer = setInterval(() => { if (!document.hidden) load().catch(() => {}); }, 6000);
};

function imeiHidden() {
  return `<div class="opt"><div class="t"><b>IMEI</b><small>${T('Скрыт. Показывается только администратору и не сохраняется.', 'Hidden. Shown to the administrator only and never stored.')}</small></div><button class="btn sm" data-imei-show><i data-icon="eye"></i>${T('Показать', 'Show')}</button></div>`;
}

PAGES.settings = async (v) => {
  const [c, sims] = await Promise.all([GET('settings'), GET('sims').catch(() => [])]);
  const s = c.sending, r = c.receiving, srv = c.server || {}, st = c.storage, si = c.storageInfo, cl = c.calls;
  const RET = [[7, T('7 дней', '7 days')], [30, T('30 дней', '30 days')], [90, T('90 дней', '90 days')], [180, T('180 дней', '180 days')], [365, T('1 год', '1 year')], [0, T('Без ограничения', 'Unlimited')]];
  const retSel = (id, label, val) => {
    const opts = RET.some(([d]) => d === +val) ? RET : [...RET, [+val, `${val} ${T('дн.', 'days')}`]];
    return `<label class="field"><span>${esc(label)}</span><select id="${id}">${opts.map(([d, l]) => `<option value="${d}" ${+val === d ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select></label>`;
  };
  const strategies = [['auto', T('SIM по умолчанию системы', 'System default SIM')], ['roundrobin', T('По очереди (round-robin)', 'Alternate (round-robin)')], ['balanced', T('Наименее загруженная за сегодня', 'Least used today')], ['slot1', T('Всегда слот 1', 'Always slot 1')], ['slot2', T('Всегда слот 2', 'Always slot 2')]];
  const simOpts = sims.map(x => [`sim:${x.simId}`, `${simName(x)}${simSub(x) ? ' — ' + simSub(x) : ''}${x.active !== false && x.slot ? ' (SIM ' + x.slot + (x.embedded ? ', eSIM' : '') + ')' : ' — ' + T('неактивна', 'inactive')}`]);
  if (/^sim:/.test(s.strategy) && !simOpts.some(([k]) => k === s.strategy)) simOpts.push([s.strategy, T('Неизвестная SIM', 'Unknown SIM') + ' #' + s.strategy.slice(4)]);
  const hasAppPrefs = Object.prototype.hasOwnProperty.call(c, 'language') || Object.prototype.hasOwnProperty.call(c, 'theme');
  const roRow = (title, desc, on) => `<div class="opt"><div class="t"><b>${esc(title)}</b><small>${esc(desc)}</small></div><span class="pill ${on ? 'em' : 'mu'}">${on ? T('Включено', 'On') : T('Выключено', 'Off')}</span></div>`;
  v.innerHTML = `<div class="grid g2" style="margin-bottom:16px">
    <div class="card"><h3><i data-icon="palette"></i>${T('Внешний вид панели', 'Panel appearance')}</h3>
      <div class="opt"><div class="t"><b>${T('Тема оформления', 'Theme')}</b><small>${T('Светлая, тёмная или как в системе — для этого браузера', 'Light, dark or system — for this browser')}</small></div>${themeSwitch()}</div>
      <div class="opt"><div class="t"><b>${T('Язык интерфейса', 'Interface language')}</b><small>Русский / English</small></div>${langSwitch()}</div>
    </div>
    <div class="card"><h3><i data-icon="sim"></i>${T('Приложение на телефоне', 'Phone app')}</h3>
      ${hasAppPrefs ? `<div class="grid g2 tight">
        <label class="field"><span>${T('Язык приложения', 'App language')}</span><select id="appLang">${[['en', 'English'], ['ru', 'Русский']].map(([k, l]) => `<option value="${k}" ${((c.language === 'ru') ? 'ru' : 'en') === k ? 'selected' : ''}>${esc(l)}</option>`).join('')}</select></label>
        <label class="field"><span>${T('Тема приложения', 'App theme')}</span><select id="appTheme">${THEMES.map(k => `<option value="${k}" ${(c.theme || 'system') === k ? 'selected' : ''}>${esc(themeName(k))}</option>`).join('')}</select></label></div>`
      : `<p class="muted small">${T('Язык и тема приложения меняются в настройках на телефоне.', 'The app language and theme are changed in the settings on the phone.')}</p>`}
      ${c.deviceId ? `<div class="opt"><div class="t"><b>${T('ID устройства', 'Device ID')}</b><small class="mono">${esc(c.deviceId)}</small></div><button class="btn sm" id="cpDev"><i data-icon="copy"></i></button></div>` : ''}
      <div id="imeiBox">${imeiHidden()}</div>
    </div>
  </div>
  <div class="grid g2">
    <div class="card"><h3><i data-icon="settings"></i>${T('Устройство и сервер', 'Device and server')}</h3>
      <label class="field"><span>${T('Имя устройства', 'Device name')}</span><input id="dn" value="${esc(c.deviceName)}" maxlength="40"></label>
      ${roRow(T('HTTP-сервер и REST API', 'HTTP server and REST API'), T('Изменяется только на телефоне — выключение из панели отрезало бы доступ к ней', 'Change it on the phone only — turning it off from the panel would cut off your access'), srv.enabled !== false)}
      ${roRow(T('Веб-панель управления', 'Web admin panel'), T('Изменяется только на телефоне', 'Change it on the phone only'), srv.webAdmin !== false)}
      <div class="grid g2" style="gap:12px;margin-top:12px"><label class="field"><span>${T('Порт', 'Port')}</span><input id="port" type="number" min="1024" max="65535" value="${esc(srv.port)}"></label>
      <div class="field"><span>HTTPS</span><div class="row" style="height:42px">${sw('https', srv.https)}<small class="muted">${T('самоподписанный сертификат', 'self-signed certificate')}</small></div></div></div>
      ${c.tlsFingerprint ? `<label class="field"><span>${T('Отпечаток сертификата SHA-256', 'Certificate SHA-256 fingerprint')}</span><div class="secret small">${esc(c.tlsFingerprint)}</div><small>${T('Сверьте с отпечатком в браузере при первом подключении', 'Compare it with the fingerprint shown by the browser on first connection')}</small></label>` : ''}
      <div class="opt"><div class="t"><b>${T('Автозапуск', 'Autostart')}</b><small>${T('После перезагрузки телефона', 'After the phone reboots')}</small></div>${sw('auto', c.autostart)}</div>
      <div class="opt"><div class="t"><b>${T('Не засыпать', 'Keep awake')}</b><small>${T('Удерживать CPU и Wi-Fi, пока шлюз работает', 'Hold CPU and Wi-Fi awake while the gateway is running')}</small></div>${sw('awake', c.keepAwake)}</div>
    </div>
    <div class="card"><h3><i data-icon="send"></i>${T('Отправка', 'Sending')}</h3>
      <label class="field"><span>${T('Выбор SIM, если не указана в запросе', 'SIM selection when not specified in the request')}</span><select id="strat">${strategies.map(([k,l]) => `<option value="${k}" ${s.strategy === k ? 'selected' : ''}>${esc(l)}</option>`).join('')}
        ${simOpts.length ? `<optgroup label="${esc(T('Всегда эта SIM', 'Always this SIM'))}">${simOpts.map(([k, l]) => `<option value="${esc(k)}" ${s.strategy === k ? 'selected' : ''}>${esc(l)}</option>`).join('')}</optgroup>` : ''}</select>
        <small>${T('«Всегда эта SIM» привязана к карте, а не к слоту — работает и после перестановки карт или смены профиля eSIM.', '“Always this SIM” is bound to the card, not the slot — it keeps working after swapping cards or switching eSIM profiles.')}</small></label>
      <div class="grid g3" style="gap:12px">
        <label class="field"><span>${T('Пауза, мс', 'Pause, ms')}</span><input id="delay" type="number" min="0" max="60000" value="${esc(s.delayMs)}"></label>
        <label class="field"><span>${T('Макс. SMS/мин', 'Max SMS/min')}</span><input id="ppm" type="number" min="0" value="${esc(s.maxPerMinute)}"></label>
        <label class="field"><span>${T('Повторов', 'Retries')}</span><input id="retr" type="number" min="0" max="10" value="${esc(s.maxRetries)}"></label>
      </div>
      <div class="opt"><div class="t"><b>${T('Отчёты о доставке', 'Delivery reports')}</b><small>${T('Запрашивать у оператора подтверждение доставки', 'Request delivery confirmation from the carrier')}</small></div>${sw('dr', s.deliveryReports)}</div>
    </div>
    <div class="card"><h3><i data-icon="inbox"></i>${T('Приём', 'Receiving')}</h3>
      <div class="opt"><div class="t"><b>${T('Хранить входящие в журнале шлюза', 'Store incoming in the gateway log')}</b><small>${T('Для выдачи через API (polling)', 'To serve them via the API (polling)')}</small></div>${sw('store', r.store)}</div>
      <label class="field" style="margin-top:12px"><span>${T('Не пересылать от отправителей', 'Do not forward from senders')}</span><textarea id="ign" rows="3" placeholder="900&#10;Bank">${esc(r.ignoreSenders.join('\n'))}</textarea><small>${T('По вхождению строки, по одному на строку', 'Substring match, one per line')}</small></label>
    </div>
    ${st ? `<div class="card"><h3><i data-icon="db"></i>${T('Данные и хранение', 'Data and storage')}</h3>
      ${si ? `<div class="stor-stats"><div><b>${si.dbBytes != null ? (si.dbBytes / 1048576).toFixed(si.dbBytes < 10485760 ? 2 : 1) : '—'}</b><small>${T('МБ база данных', 'MB database')}</small></div><div><b>${esc(si.messages ?? '—')}</b><small>${T('сообщений', 'messages')}</small></div><div><b>${esc(si.webhookQueue ?? '—')}</b><small>${T('в очереди вебхуков', 'in webhook queue')}</small></div></div>
        ${si.secretsEncrypted ? `<div class="sec-badge"><i data-icon="shield"></i>${T('Секреты зашифрованы Android Keystore', 'Secrets encrypted with Android Keystore')} <i data-icon="check"></i></div>` : ''}` : ''}
      <div class="grid g2 tight">
        ${retSel('stHist', T('История сообщений', 'Message history'), st.historyDays)}
        <label class="field"><span>${T('Максимум сообщений', 'Max messages')}</span><input id="stMax" type="number" min="0" step="1000" value="${esc(st.maxMessages)}"><small>${T('0 — без ограничения', '0 — unlimited')}</small></label>
        ${retSel('stLog', T('Журнал событий', 'Event log'), st.logDays)}
        ${retSel('stUssd', T('История USSD', 'USSD history'), st.ussdDays)}
        ${retSel('stCall', T('Журнал звонков', 'Call log'), st.callDays)}
      </div>
      ${toggleRow('stDel', T('Удалять и с телефона', 'Also delete from the phone'), T('При удалении сообщения в панели — удалять его из переписок телефона', 'When a message is deleted in the panel, also delete it from the phone conversations'), st.deleteFromPhone)}
    </div>` : ''}
    ${cl ? `<div class="card"><h3><i data-icon="phone"></i>${T('Звонки', 'Calls')}</h3>
      ${toggleRow('clOn', T('Отслеживать звонки', 'Track calls'), T('Вести журнал входящих и исходящих звонков, события call.* для вебхуков', 'Keep a log of incoming and outgoing calls, call.* webhook events'), cl.enabled)}
      ${toggleRow('clMiss', T('Пропущенные — в Telegram', 'Missed calls to Telegram'), T('Уведомление о каждом пропущенном звонке', 'A notification for every missed call'), cl.notifyMissed)}
      ${toggleRow('clIn', T('Входящие — в Telegram', 'Incoming calls to Telegram'), T('Уведомление, когда телефон звонит', 'A notification when the phone rings'), cl.notifyIncoming)}
    </div>` : ''}
    <div class="card"><h3><i data-icon="lock"></i>${T('Доступ и безопасность', 'Access and security')}</h3>
      <div class="opt"><span class="stat"><span class="ico tint-in"><i data-icon="user"></i></span></span><div class="t"><b>${T('Мой пароль', 'My password')}</b><small>${T('Смена пароля — в профиле', 'Change it in your profile')}</small></div><a class="btn sm" href="#/profile">${T('Профиль', 'Profile')}</a></div>
      <div class="opt"><span class="stat"><span class="ico tint-em"><i data-icon="users"></i></span></span><div class="t"><b>${T('Пользователи', 'Users')}</b><small>${T('Учётные записи, права и доступ к SIM', 'Accounts, permissions and SIM access')}</small></div><a class="btn sm" href="#/users">${T('Открыть', 'Open')}</a></div>
      <div class="opt"><span class="stat"><span class="ico tint-red"><i data-icon="shield"></i></span></span><div class="t"><b>${T('Файрвол', 'Firewall')}</b><small>${T('Режим доступа, панель только из LAN, баны', 'Access mode, panel from LAN only, bans')}</small></div><a class="btn sm" href="#/firewall">${T('Открыть', 'Open')}</a></div>
      <div class="opt"><span class="stat"><span class="ico tint-sky"><i data-icon="tg"></i></span></span><div class="t"><b>${T('Telegram-бот', 'Telegram bot')}</b><small>${T('Уведомления и управление из Telegram', 'Notifications and control from Telegram')}</small></div><a class="btn sm" href="#/telegram">${T('Открыть', 'Open')}</a></div>
      <div class="opt"><span class="stat"><span class="ico tint-am"><i data-icon="cloud"></i></span></span><div class="t"><b>${T('SMS-сервер и вебхуки', 'SMS server and webhooks')}</b><small>${T('Интеграция с вашей системой', 'Integration with your system')}</small></div><a class="btn sm" href="#/remote">${T('Открыть', 'Open')}</a></div>
    </div>
  </div>
  <div style="margin-top:16px"><div class="form-error" id="serr"></div><button class="btn primary" id="ssave"><i data-icon="check"></i>${T('Сохранить настройки', 'Save settings')}</button></div>
  <div class="card" style="margin-top:24px"><h3><i data-icon="download"></i>${T('Резервная копия и восстановление', 'Backup and restore')}</h3>
    <p class="muted" style="margin-top:-6px">${T('Файл резервной копии содержит настройки, пользователей, API-ключи, SIM-реестр, вебхуки и ботов; секреты в нём зашифрованы вашим паролем.', 'The backup file contains settings, users, API keys, the SIM registry, webhooks and bots; secrets inside are encrypted with your password.')}</p>
    <div class="grid g2">
      <form class="bk-pane" id="bkForm" novalidate><h4>${T('Создать копию', 'Create a backup')}</h4>
        ${pwdField('bkPw', T('Пароль копии', 'Backup password'), T('Минимум 8 символов. Без него восстановить копию невозможно.', 'At least 8 characters. Without it the backup cannot be restored.'))}<em class="ferr" id="bkErr"></em>
        <label class="check-row"><input type="checkbox" id="bkHist"><span><b>${T('Включить историю сообщений', 'Include message history')}</b><small>${T('Файл станет больше', 'The file will be larger')}</small></span></label>
        <button class="btn primary" type="submit" id="bkGo"><i data-icon="download"></i>${T('Скачать резервную копию', 'Download backup')}</button></form>
      <form class="bk-pane" id="rsForm" novalidate><h4>${T('Восстановить', 'Restore')}</h4>
        <div class="callout"><i data-icon="alert"></i><div><b>${T('Внимание!', 'Warning!')}</b> ${T('Восстановление полностью заменит все текущие настройки, пользователей и API-ключи данными из файла. Сервер и бот перезапустятся.', 'Restoring completely replaces all current settings, users and API keys with the data from the file. The server and the bot will restart.')}</div></div>
        <label class="field"><span>${T('Файл резервной копии', 'Backup file')}</span><input type="file" id="rsFile" accept=".json,application/json"><em class="ferr" id="rsFileErr"></em></label>
        <label class="field"><span>${T('Пароль копии', 'Backup password')}</span><input type="password" id="rsPw" autocomplete="off"><em class="ferr" id="rsPwErr"></em></label>
        <label class="check-row"><input type="checkbox" id="rsHist"><span><b>${T('Восстановить историю сообщений', 'Restore message history')}</b><small>${T('Если она есть в файле', 'If the file contains it')}</small></span></label>
        <div class="form-error" id="rsErr"></div>
        <button class="btn danger" type="submit" id="rsGo"><i data-icon="upload"></i>${T('Восстановить из файла', 'Restore from file')}</button></form>
    </div></div>`;
  bindPwd(v);
  $('#bkForm').onsubmit = async (e) => {
    e.preventDefault();
    const pw = $('#bkPw').value;
    if (!fieldErr(v, 'bkErr', pw.length >= 8 ? '' : T('Минимум 8 символов', 'At least 8 characters'))) return;
    const btn = $('#bkGo'); btn.disabled = true;
    try {
      let r;
      try { r = await fetch('/admin/api/backup', { method: 'POST', credentials: 'same-origin', headers: { 'X-ISMS-CSRF': '1', 'X-ISMS-Lang': lang, 'Content-Type': 'application/json' }, body: JSON.stringify({ password: pw, includeHistory: $('#bkHist').checked }) }); }
      catch (x) { throw new Error(T('Нет связи с телефоном', 'No connection to the phone')); }
      if (r.status === 401) { showLogin(); return; }
      const text = await r.text();
      if (!r.ok) { let msg = 'HTTP ' + r.status; try { msg = JSON.parse(text).error || msg; } catch (x) { /* not json */ } throw new Error(msg); }
      const d = new Date(), ymd = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
      const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }));
      const a = document.createElement('a'); a.href = url; a.download = `isms-gw-backup-${ymd}.json`; document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 4000);
      toast(T('Резервная копия сохранена. Храните файл и пароль в надёжном месте.', 'Backup saved. Keep the file and the password in a safe place.'));
    } catch (err) { fieldErr(v, 'bkErr', err.message); } finally { btn.disabled = false; }
  };
  $('#rsForm').onsubmit = async (e) => {
    e.preventDefault(); $('#rsErr').textContent = '';
    const f = $('#rsFile').files[0], pw = $('#rsPw').value;
    let ok = fieldErr(v, 'rsFileErr', f ? (f.size > 50 * 1048576 ? T('Файл слишком большой', 'The file is too large') : '') : T('Выберите файл', 'Choose a file'));
    ok = fieldErr(v, 'rsPwErr', pw ? '' : T('Введите пароль копии', 'Enter the backup password')) && ok;
    if (!ok) return;
    if (!await confirmBox(T('Восстановить из резервной копии?', 'Restore from backup?'), T('Все текущие настройки, пользователи и API-ключи будут заменены данными из файла «' + f.name + '». Это действие нельзя отменить.', 'All current settings, users and API keys will be replaced with the data from “' + f.name + '”. This cannot be undone.'), T('Восстановить', 'Restore'))) return;
    const text = await new Promise((res, rej) => { const fr = new FileReader(); fr.onload = () => res(String(fr.result)); fr.onerror = () => rej(new Error(T('Не удалось прочитать файл', 'Could not read the file'))); fr.readAsText(f); }).catch(err => { $('#rsErr').textContent = err.message; return null; });
    if (text == null) return;
    const btn = $('#rsGo'); btn.disabled = true;
    try {
      const r = await POST('restore', { backup: text, password: pw, includeHistory: $('#rsHist').checked });
      modal(`<h3><i data-icon="check"></i>${T('Данные восстановлены', 'Data restored')}</h3><p class="muted">${T('Восстановлено сообщений: ', 'Messages restored: ')}${esc(r.messages ?? 0)}. ${T('Сервер и бот перезапускаются — страница обновится через несколько секунд. Возможно, потребуется войти заново.', 'The server and the bot are restarting — the page will reload in a few seconds. You may need to sign in again.')}</p>${spinner(T('Перезапуск…', 'Restarting…'))}`);
      setTimeout(() => location.reload(), 6000);
    } catch (err) { $('#rsErr').textContent = err.message; btn.disabled = false; }
  };
  $('#ssave').onclick = async () => {
    $('#serr').textContent = '';
    const port = +$('#port').value;
    if (!Number.isInteger(port) || port < 1024 || port > 65535) { $('#serr').textContent = T('Порт должен быть 1024–65535', 'Port must be 1024–65535'); return; }
    try {
      const body = {
        deviceName: $('#dn').value.trim(), autostart: $('#auto').checked, keepAwake: $('#awake').checked,
        server: { port, https: $('#https').checked },
        sending: { strategy: $('#strat').value, delayMs: +$('#delay').value, maxPerMinute: +$('#ppm').value, maxRetries: +$('#retr').value, deliveryReports: $('#dr').checked },
        receiving: { ...r, store: $('#store').checked, ignoreSenders: $('#ign').value.split('\n').map(x => x.trim()).filter(Boolean) },
      };
      if (st) body.storage = { ...st, historyDays: +$('#stHist').value, maxMessages: Math.max(0, Math.floor(+$('#stMax').value || 0)), logDays: +$('#stLog').value, ussdDays: +$('#stUssd').value, callDays: +$('#stCall').value, deleteFromPhone: $('#stDel').checked };
      if (cl) body.calls = { ...cl, enabled: $('#clOn').checked, notifyMissed: $('#clMiss').checked, notifyIncoming: $('#clIn').checked };
      if ($('#appLang')) body.language = $('#appLang').value;
      if ($('#appTheme')) body.theme = $('#appTheme').value;
      const res = await PUT('settings', body);
      if (res.restart) {
        const proto = $('#https').checked ? 'https' : 'http', url = `${proto}://${location.hostname}:${$('#port').value}/`;
        modal(`<h3>${T('Сервер перезапускается', 'Server is restarting')}</h3><p class="muted">${T('Новый адрес панели:', 'New panel address:')}</p><div class="secret">${esc(url)}</div><div class="modal-actions"><a class="btn primary" href="${esc(url)}">${T('Перейти', 'Open')}</a></div>`);
      } else toast(T('Настройки сохранены', 'Settings saved'));
    } catch (e) { $('#serr').textContent = e.message; }
  };
  if ($('#cpDev')) $('#cpDev').onclick = () => copyText(c.deviceId);
  // IMEI is requested only when the administrator asks for it and is not kept after "Hide".
  $('#imeiBox').onclick = async (e) => {
    const box = $('#imeiBox');
    if (e.target.closest('[data-imei-hide]')) { box.innerHTML = imeiHidden(); hydrateIcons(box); return; }
    const cp = e.target.closest('[data-imei-copy]'); if (cp) { copyText(cp.dataset.imeiCopy); return; }
    if (!e.target.closest('[data-imei-show]')) return;
    try {
      const d = await GET('device');
      const msg = {
        restricted: T(`Android 10+ не выдаёт IMEI приложениям. Наберите ${d.dialCode} на телефоне — система покажет его.`, `Android 10+ does not give apps the IMEI. Dial ${d.dialCode} on the phone and the system will show it.`),
        no_permission: T('Нет разрешения «Телефон» в приложении.', 'The app has no Phone permission.'),
        none: T('IMEI недоступен на этом устройстве.', 'IMEI is not available on this device.'),
      };
      box.innerHTML = d.imei === 'available'
        ? d.slots.map(x => `<div class="opt"><div class="t"><b>IMEI${d.slots.length > 1 ? ' ' + esc(x.slot) : ''}</b><small class="mono">${esc(x.imei)}</small></div><button class="btn sm" data-imei-copy="${esc(x.imei)}" title="${esc(T('Копировать', 'Copy'))}" aria-label="${esc(T('Копировать', 'Copy'))}"><i data-icon="copy"></i></button></div>`).join('')
          + `<div class="row" style="justify-content:flex-end;margin-top:8px"><button class="btn sm" data-imei-hide>${T('Скрыть', 'Hide')}</button></div>`
        : `<div class="opt"><div class="t"><b>IMEI</b><small>${esc(msg[d.imei] || msg.none)}</small></div></div>`;
      hydrateIcons(box);
    } catch (err) { toast(err.message, true); }
  };
};

PAGES.docs = async (v) => {
  const base = location.origin;
  const KEY = T('sg_ВАШ_КЛЮЧ', 'sg_YOUR_KEY');
  const block = (code) => `<div class="code"><button class="btn sm copy" data-copy>${T('Копировать', 'Copy')}</button>${code}</div>`;
  const ep = (m, path, desc) => `<div class="endpoint"><span class="method m-${m.toLowerCase()}">${m}</span><b class="mono">${path}</b><span class="muted small">${desc}</span></div>`;
  v.innerHTML = `<div class="card">
    <h3><i data-icon="book"></i>REST API v1</h3>
    <p class="muted">${T('Базовый адрес:', 'Base URL:')} <span class="tag">${esc(base)}/api/v1</span>. ${T('Авторизация — заголовок', 'Authorization — header')} <span class="tag">Authorization: Bearer &lt;${T('API-ключ', 'API key')}&gt;</span> (${T('создаётся в разделе «API-ключи»', 'created in the “API keys” section')}). ${T('Ответы — JSON в UTF-8.', 'Responses are UTF-8 JSON.')}</p>
    <div class="callout info"><i data-icon="users"></i><div>${T('Каждый ключ принадлежит пользователю и действует только в пределах его прав (send / read / ussd / call) и его SIM-карт. Если у ключа выбрано подмножество SIM — только этих карт. Сообщения других пользователей и чужие SIM ключу не видны; попытка отправить через недоступную SIM вернёт 403.', 'Every key belongs to a user and works only within that user’s permissions (send / read / ussd / call) and SIM cards. If the key has a SIM subset, only those cards. Other users’ messages and SIMs are invisible to the key; sending through an inaccessible SIM returns 403.')}</div></div>
    ${ep('POST', '/api/v1/sms', T('отправить SMS (право send)', 'send SMS (send scope)'))}
    ${block(`curl -X POST ${esc(base)}/api/v1/sms \\
  -H <span class="s">"Authorization: Bearer ${KEY}"</span> \\
  -H <span class="s">"Content-Type: application/json"</span> \\
  -d <span class="s">'{"to": ["+79991234567"], "text": "${T('Ваш код: 4821', 'Your code: 4821')}", "simId": 3, "ref": "order-1001"}'</span>

<span class="c"># → {"ok":true,"messages":[{"id":57,"to":"+79991234567","status":"queued","simId":3}]}
# ${T('simId — постоянный ID SIM-карты из GET /api/v1/sims (рекомендуется: не меняется при перестановке карт и смене профиля eSIM).', 'simId — the stable SIM id from GET /api/v1/sims (recommended: it does not change when cards are swapped or eSIM profiles switched).')}
# ${T('sim — номер слота 1/2 (для совместимости). Не указаны или 0 — SIM выбирается по стратегии.', 'sim — slot number 1/2 (for compatibility). Omitted or 0 — the SIM is chosen by the strategy.')}
# ${T('ref — ваш идентификатор.', 'ref — your own identifier.')}</span>`)}
    ${ep('GET', '/api/v1/sms/{id}', T('статус сообщения (право read)', 'message status (read scope)'))}
    ${block(`curl ${esc(base)}/api/v1/sms/57 -H <span class="s">"Authorization: Bearer ${KEY}"</span>
<span class="c"># status: queued → sending → sent → delivered | failed</span>`)}
    ${ep('GET', '/api/v1/sms?direction=in&amp;unacked=1', T('забрать новые входящие (polling)', 'fetch new incoming messages (polling)'))}
    ${block(`curl <span class="s">"${esc(base)}/api/v1/sms?direction=in&amp;unacked=1&amp;limit=100"</span> -H <span class="s">"Authorization: Bearer ${KEY}"</span>
<span class="c"># ${T('Параметры', 'Parameters')}: direction=in|out, status, since_id, unacked=1, limit (≤500)</span>`)}
    ${ep('POST', '/api/v1/sms/ack', T('подтвердить получение входящих', 'acknowledge incoming messages'))}
    ${block(`curl -X POST ${esc(base)}/api/v1/sms/ack -H <span class="s">"Authorization: Bearer ${KEY}"</span> \\
  -H <span class="s">"Content-Type: application/json"</span> -d <span class="s">'{"ids": [101, 102]}'</span>`)}
    ${ep('GET', '/api/v1/status', T('состояние устройства, SIM, статистика', 'device state, SIMs, statistics'))}
    ${ep('GET', '/api/v1/sims', T('SIM-карты, доступные ключу (право read)', 'SIM cards available to the key (read scope)'))}
    ${block(`curl ${esc(base)}/api/v1/sims -H <span class="s">"Authorization: Bearer ${KEY}"</span>

<span class="c"># → [</span>
{ <span class="s">"simId"</span>: 3, <span class="s">"slot"</span>: 1, <span class="s">"active"</span>: <span class="k">true</span>, <span class="s">"embedded"</span>: <span class="k">false</span>, <span class="s">"carrier"</span>: "MTS", <span class="s">"displayName"</span>: "MTS",
  <span class="s">"number"</span>: "+79991234567", <span class="s">"country"</span>: "ru", <span class="s">"label"</span>: "${T('Основная', 'Main')}", <span class="s">"color"</span>: "#34D399",
  <span class="s">"sendEnabled"</span>: <span class="k">true</span>, <span class="s">"receiveEnabled"</span>: <span class="k">true</span>, <span class="s">"dailyLimit"</span>: 500, <span class="s">"isDefault"</span>: <span class="k">true</span>,
  <span class="s">"iccTail"</span>: "1234", <span class="s">"lastSeen"</span>: 1760000000000 }
<span class="c"># ]
# ${T('slot: 0 и active: false — карта извлечена (остаётся в списке). embedded: true — eSIM.', 'slot: 0 and active: false — the card was removed (it stays listed). embedded: true — eSIM.')}</span>`)}
    ${ep('GET', '/api/v1/sims/{simId}/messages', T('SMS из памяти SIM-карты (право read)', 'SMS stored in the SIM card memory (read scope)'))}
    ${block(`curl ${esc(base)}/api/v1/sims/3/messages -H <span class="s">"Authorization: Bearer ${KEY}"</span>
<span class="c"># → {"simId":3,"messages":[{"index":1,"address":"+7999…","body":"…","date":1760000000000,"status":"read"}]}
# ${T('422 — прошивка не даёт доступа к памяти этой SIM.', '422 — the firmware does not expose this SIM’s memory.')}</span>`)}
    ${ep('DELETE', '/api/v1/sims/{simId}/messages/{index}', T('удалить SMS из памяти SIM (право send)', 'delete an SMS from SIM memory (send scope)'))}
    ${block(`curl -X DELETE ${esc(base)}/api/v1/sims/3/messages/1 -H <span class="s">"Authorization: Bearer ${KEY}"</span>`)}

    <h3 style="margin-top:28px">USSD</h3>
    ${ep('POST', '/api/v1/ussd', T('выполнить USSD-запрос (право ussd)', 'run a USSD request (ussd scope)'))}
    ${block(`curl -X POST ${esc(base)}/api/v1/ussd -H <span class="s">"Authorization: Bearer ${KEY}"</span> \\
  -H <span class="s">"Content-Type: application/json"</span> -d <span class="s">'{"code": "*100#", "simId": 3}'</span>

<span class="c"># ${T('Синхронно, до ~45 с', 'Synchronous, up to ~45 s')} →</span>
{ <span class="s">"id"</span>: 12, <span class="s">"simId"</span>: 3, <span class="s">"slot"</span>: 1, <span class="s">"code"</span>: "*100#", <span class="s">"status"</span>: "ok", <span class="s">"response"</span>: "${T('Баланс 150.00 р', 'Balance 150.00')}", <span class="s">"error"</span>: <span class="k">null</span>, … }
<span class="c"># status: ok | error | pending. ${T('Только одношаговые запросы — интерактивные меню не поддерживаются. Вместо simId можно передать sim (слот).', 'Single-step requests only — interactive menus are not supported. Instead of simId you may pass sim (slot).')}</span>`)}
    ${ep('GET', '/api/v1/ussd/{id}', T('результат USSD-запроса (право ussd)', 'USSD request result (ussd scope)'))}
    ${block(`curl ${esc(base)}/api/v1/ussd/12 -H <span class="s">"Authorization: Bearer ${KEY}"</span>`)}

    <h3 style="margin-top:28px">${T('Звонки', 'Calls')}</h3>
    ${ep('GET', '/api/v1/calls', T('журнал звонков (право call)', 'call log (call scope)'))}
    ${block(`curl <span class="s">"${esc(base)}/api/v1/calls?limit=50"</span> -H <span class="s">"Authorization: Bearer ${KEY}"</span>
<span class="c"># → {"calls":[{"id":5,"simId":3,"slot":1,"number":"+7999…","direction":"in","status":"missed","startedAt":…,"answeredAt":0,"endedAt":…}]}
# status: ringing | answered | missed | ended | dialing | active | failed. ${T('number может быть пустым (Android 10+ скрывает номер).', 'number may be empty (Android 10+ hides it).')}</span>`)}
    ${ep('POST', '/api/v1/calls', T('позвонить с телефона (право call)', 'place a call from the phone (call scope)'))}
    ${block(`curl -X POST ${esc(base)}/api/v1/calls -H <span class="s">"Authorization: Bearer ${KEY}"</span> \\
  -H <span class="s">"Content-Type: application/json"</span> -d <span class="s">'{"to": "+79991234567", "simId": 3}'</span>
<span class="c"># ${T('Телефон набирает номер; разговор ведётся на самом телефоне — голос через API не передаётся.', 'The phone dials the number; the conversation happens on the phone itself — voice is not transmitted via the API.')}</span>`)}

    <h3 style="margin-top:28px">${T('Объект сообщения', 'Message object')}</h3>
    <p class="muted small">${T('Поля', 'Fields')}: <span class="tag">id</span><span class="tag">direction</span><span class="tag">phone</span><span class="tag">text</span><span class="tag">sim</span> (${T('слот', 'slot')}) <span class="tag">simId</span> (${T('постоянный ID SIM', 'stable SIM id')}) <span class="tag">status</span><span class="tag">parts</span><span class="tag">ref</span><span class="tag">owner</span> (${T('ID пользователя-отправителя', 'id of the sending user')}) <span class="tag">createdAt</span></p>
    <h3 style="margin-top:28px">${T('Коды ответов', 'Response codes')}</h3>
    <div class="table-wrap"><table><tbody>
      <tr><td class="mono">200</td><td>${T('Успех', 'Success')}</td></tr><tr><td class="mono">400</td><td>${T('Ошибка в запросе (номер, текст, JSON)', 'Bad request (number, text, JSON)')}</td></tr>
      <tr><td class="mono">401</td><td>${T('Нет или неверный API-ключ (после нескольких попыток IP блокируется)', 'Missing or invalid API key (the IP is banned after several attempts)')}</td></tr>
      <tr><td class="mono">403</td><td>${T('Запрещено файрволом, IP-ограничением ключа, нет права у ключа или его владельца, SIM недоступна ключу, владелец отключён', 'Forbidden by the firewall or the key’s IP restriction, missing permission of the key or its owner, SIM not available to the key, or the owner is disabled')}</td></tr>
      <tr><td class="mono">422</td><td>${T('Невозможно отправить (SIM отключена, лимит, нет роли SMS-приложения)', 'Cannot send (SIM disabled, limit reached, not the default SMS app)')}</td></tr>
      <tr><td class="mono">429</td><td>${T('Превышен лимит запросов в минуту', 'Requests-per-minute limit exceeded')}</td></tr>
    </tbody></table></div>
    <h3 style="margin-top:28px">${T('Пример на Python', 'Python example')}</h3>
    ${block(`<span class="k">import</span> requests
GW, KEY = <span class="s">"${esc(base)}"</span>, <span class="s">"${KEY}"</span>
H = {<span class="s">"Authorization"</span>: f<span class="s">"Bearer {KEY}"</span>}
r = requests.post(f<span class="s">"{GW}/api/v1/sms"</span>, json={<span class="s">"to"</span>: <span class="s">"+79991234567"</span>, <span class="s">"text"</span>: <span class="s">"${T('Привет!', 'Hello!')}"</span>}, headers=H, verify=<span class="k">False</span>)
print(r.json())`)}
  </div>`;
  v.querySelectorAll('[data-copy]').forEach(b => b.onclick = () => {
    const c = b.parentElement.cloneNode(true); const btn = c.querySelector('[data-copy]'); if (btn) btn.remove();
    copyText(c.textContent.trim());
  });
};

// ---------------------------------------------------------------- auth & boot
function showLogin() {
  clearInterval(timer);
  $('#app').classList.add('hidden'); $('#login').classList.remove('hidden');
  document.title = 'iSMS GW — ' + T('вход', 'sign in');
  closeModal(); ME = null; MY_SIMS = [];
  $('#loginPassword').value = '';
  setTimeout(() => ($('#loginUsername').value ? $('#loginPassword') : $('#loginUsername')).focus(), 50);
}
async function showApp() {
  $('#login').classList.add('hidden'); $('#app').classList.remove('hidden');
  hydrateIcons(); route();
  if ((location.hash.replace(/^#\//, '') || 'dashboard').split('?')[0] !== 'dashboard')
    GET('dashboard').then(d => { if (d.device) $('#brandDevice').textContent = d.device.name; }).catch(() => {});
}
$('#loginForm').addEventListener('submit', async (e) => {
  e.preventDefault(); $('#loginError').textContent = '';
  const username = $('#loginUsername').value.trim(), password = $('#loginPassword').value;
  if (!password) { $('#loginError').textContent = T('Введите пароль', 'Enter your password'); $('#loginPassword').focus(); return; }
  const btn = e.submitter || $('#loginForm button[type="submit"]'); btn.disabled = true;
  try {
    const body = { password }; if (username) body.username = username;
    const r = await POST('login', body);
    store.set('isms.user', username);
    ME = r.user || null;
    await loadMe().catch(() => {});
    showApp();
  } catch (err) {
    const msg = err.status === 401 ? T('Неверный логин или пароль. Проверьте раскладку клавиатуры и Caps Lock.', 'Invalid username or password. Check the keyboard layout and Caps Lock.')
      : err.status === 403 ? T('Доступ запрещён: ', 'Access denied: ') + err.message
      : err.status === 429 ? T('Слишком много попыток. Подождите немного и попробуйте снова.', 'Too many attempts. Wait a little and try again.')
      : err.message;
    $('#loginError').textContent = msg;
    $('#loginPassword').select();
  } finally { btn.disabled = false; }
});
$('#loginUsername').value = store.get('isms.user') || '';
document.addEventListener('click', async (e) => {
  const lb = e.target.closest('[data-lang]');
  if (lb) { e.preventDefault(); setLang(lb.dataset.lang); return; }
  const tb = e.target.closest('[data-theme-set]');
  if (tb) { e.preventDefault(); setTheme(tb.dataset.themeSet); return; }
  const a = e.target.closest('[data-action]'); if (!a) return;
  const act = a.dataset.action;
  if (act === 'logout') { await POST('logout').catch(() => {}); showLogin(); }
  if (act === 'openNav') { $('#sidebar').classList.add('open'); $('#scrim').classList.add('show'); }
  if (act === 'closeNav') { $('#sidebar').classList.remove('open'); $('#scrim').classList.remove('show'); }
});
window.addEventListener('hashchange', () => { if (!$('#app').classList.contains('hidden')) route(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeModal(); });

applyStatic();
applyTheme(false);
loadMe().then(showApp).catch(() => showLogin());
})();
