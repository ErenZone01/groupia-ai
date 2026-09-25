(function () {
  var preference = 'system';
  try {
    var stored = localStorage.getItem('groupia.theme');
    if (['light', 'dark', 'system'].includes(stored)) preference = stored;
  } catch (_) {}
  var dark =
    preference === 'dark' ||
    (preference === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.dataset.theme = dark ? 'dark' : 'light';
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
})();
