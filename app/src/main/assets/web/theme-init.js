/* iSMS GW — applies theme and language before first paint (loaded synchronously in <head>). */
(function () {
  var d = document.documentElement, get = function (k) { try { return localStorage.getItem(k); } catch (e) { return null; } };
  try {
    var p = get('isms.theme');
    if (p !== 'light' && p !== 'dark') p = 'system';
    var t = p === 'system' ? ((window.matchMedia && !window.matchMedia('(prefers-color-scheme: dark)').matches) ? 'light' : 'dark') : p;
    d.setAttribute('data-theme', t);
    d.setAttribute('data-theme-pref', p);
    var m = document.querySelector('meta[name="color-scheme"]');
    if (m) m.setAttribute('content', t);
    var l = get('isms.lang');
    if (l !== 'ru' && l !== 'en') l = 'en'; // English by default
    d.setAttribute('lang', l);
  } catch (e) { /* ignore */ }
})();
