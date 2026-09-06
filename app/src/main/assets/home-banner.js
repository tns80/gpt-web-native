(function () {
  'use strict';
  if (location.origin !== 'https://chatgpt.com') return;
  if (window.__gptNativeRefreshHomeBanner) {
    window.__gptNativeRefreshHomeBanner();
    return;
  }

  const marker = 'data-gpt-native-quota-banner';
  const title = '工作区有成员达到使用上限';
  const action = '开启自动充值';
  const normalize = value => String(value || '').replace(/\s+/g, '');
  const excluded = 'article,[data-message-id],[data-message-author-role],textarea,[contenteditable="true"],nav,[role="navigation"]';
  const style = document.createElement('style');
  style.textContent = '[' + marker + '] { display: none !important; }';
  (document.head || document.documentElement).appendChild(style);
  let queued = false;

  function matchesBanner(element) {
    if (!element.matches('div,section,aside,header') || element.closest(excluded) ||
        element.querySelector(excluded)) return false;
    const text = normalize(element.textContent);
    if (text.length > 260 || !text.includes(title)) return false;
    return Array.from(element.querySelectorAll('button,[role="button"],a'))
      .some(button => normalize(button.textContent) === action);
  }

  function update() {
    queued = false;
    const home = location.pathname === '/';
    document.querySelectorAll('[' + marker + ']').forEach(element => {
      if (!home || !matchesBanner(element)) element.removeAttribute(marker);
    });
    if (!home) return;
    document.querySelectorAll('button,[role="button"],a').forEach(button => {
      if (normalize(button.textContent) !== action || button.closest(excluded)) return;
      if (button.closest('[' + marker + ']')) return;
      let candidate = button.parentElement;
      for (let depth = 0; candidate && depth < 6; depth++, candidate = candidate.parentElement) {
        if (candidate === document.body || candidate === document.documentElement) break;
        if (!matchesBanner(candidate)) continue;
        const bounds = candidate.getBoundingClientRect();
        if (bounds.height > 0 && bounds.top >= 0 && bounds.top < innerHeight * 0.4) {
          candidate.setAttribute(marker, '');
        }
        break;
      }
    });
  }

  function schedule() {
    if (queued) return;
    queued = true;
    requestAnimationFrame(update);
  }
  window.__gptNativeRefreshHomeBanner = schedule;
  // Coalesce website mutations. No polling, network requests or history overrides.
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true, subtree: true, characterData: true
  });
  window.addEventListener('popstate', schedule);
  window.addEventListener('pageshow', schedule);
  schedule();
})();
