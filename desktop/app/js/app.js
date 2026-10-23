/* Parabol Desktop — navigation (screen stack), keyboard/mouse back, start-up. */



const Router = {
  stack: [],

  current() { return this.stack[this.stack.length - 1] || null; },

  mount(entry) {
    const root = document.getElementById('root');
    root.innerHTML = '';
    entry.screen = SCREENS[entry.name](entry.params || {});
    root.appendChild(entry.screen.el);
    if (entry.screen.afterMount) entry.screen.afterMount();
  },

  unmountCurrent() {
    const cur = this.current();
    if (cur && cur.screen && cur.screen.destroy) cur.screen.destroy();
  },

  go(name, params) {
    this.unmountCurrent();
    const entry = { name, params };
    this.stack.push(entry);
    this.mount(entry);
  },

  root(name, params) {
    this.unmountCurrent();
    this.stack = [];
    this.go(name, params);
  },

  back() {
    const cur = this.current();
    if (cur && cur.screen && cur.screen.onBack && cur.screen.onBack()) return; // handled inside the screen
    if (this.stack.length <= 1) return;
    this.unmountCurrent();
    this.stack.pop();
    this.mount(this.current());
  },
};

/** After choosing/adding an account: Home, with the account screen underneath (Esc goes back to it). */
function openHome() {
  Router.root('accounts');
  Router.go('home');
}

function isTyping(e) {
  const t = e.target;
  return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable);
}

document.addEventListener('keydown', (e) => {
  if (document.querySelector('.dialog-backdrop')) return; // dialogs handle their own keys
  if (VodPlayer.open) {
    if (VodPlayer.onKey(e)) e.preventDefault();
    return;
  }
  const cur = Router.current();
  if (cur && cur.screen && cur.screen.onKey && cur.screen.onKey(e)) { e.preventDefault(); return; }
  if (e.key === 'Escape' || e.key === 'BrowserBack' || (e.key === 'Backspace' && !isTyping(e))) {
    if (e.key === 'Escape' && isTyping(e) && e.target.value) return;
    e.preventDefault();
    Router.back();
  }
});

// Mouse "back" button.
window.addEventListener('mouseup', (e) => {
  if (e.button !== 3) return;
  e.preventDefault();
  if (VodPlayer.open) VodPlayer.close(); else Router.back();
});

function init() {
  LivePlayer.init();
  VodPlayer.init();

  if (window.parabolAPI) {
    window.parabolAPI.onRecordingsChanged((data) => applyRecordings(data));
  }

  // Always start on the account screen (the login form when there is no account yet).
  Router.root(Store.getProfiles().length ? 'accounts' : 'login');
}

document.addEventListener('DOMContentLoaded', init);
