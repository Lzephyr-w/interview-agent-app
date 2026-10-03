const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, 'main.cjs'), 'utf8');
const flush = async () => { for (let i = 0; i < 5; i++) await new Promise(setImmediate); };

function launch(single = true) {
  const children = [], windows = [], files = new Set(), errors = [];
  const app = new EventEmitter();
  Object.assign(app, {
    setName() {}, setPath() {}, setAppUserModelId() {}, getPath: () => 'C:/profile',
    requestSingleInstanceLock: () => single, whenReady: () => Promise.resolve(),
    quit() {
      const event = { cancelled: false, preventDefault() { this.cancelled = true; } };
      app.emit('before-quit', event);
      if (!event.cancelled) app.exited = true;
    }
  });
  class Window extends EventEmitter {
    constructor(options) {
      super(); this.options = options; this.hidden = false; this.minimized = false;
      this.webContents = new EventEmitter();
      this.webContents.session = {
        setPermissionCheckHandler: fn => { this.checkPermission = fn; },
        setPermissionRequestHandler: fn => { this.requestPermission = fn; }
      };
      this.webContents.setWindowOpenHandler = fn => { this.openWindow = fn; };
      windows.push(this);
    }
    loadURL(url) { this.url = url; return Promise.resolve(); }
    setAppDetails(details) { this.appDetails = details; }
    isDestroyed() { return false; }
    isMinimized() { return this.minimized; }
    hide() { this.hidden = true; }
    show() { this.hidden = false; }
    restore() { this.minimized = false; }
    focus() { this.focused = true; }
    close() { this.emit('close', { preventDefault() {} }); }
  }
  const electron = { app, BrowserWindow: Window, Menu: { setApplicationMenu() {} },
    dialog: { showErrorBox: (title, text) => errors.push(text), showMessageBox: async () => ({ response: 0 }) } };
  vm.runInNewContext(source, {
    __dirname, URL, process: { env: { SystemRoot: 'C:/Windows' }, execPath: 'C:/electron.exe' },
    require(name) {
      if (name === 'electron') return electron;
      if (name === 'node:fs') return {
        mkdirSync() {}, appendFileSync() {},
        writeFileSync: file => files.add(file), rmSync: file => files.delete(file)
      };
      if (name === 'node:child_process') return { spawn(exe, args, options) {
        const child = new EventEmitter();
        child.stdout = new EventEmitter(); child.stderr = new EventEmitter();
        child.stdout.destroy = () => {}; child.stderr.destroy = () => {};
        child.action = args[args.indexOf('-Action') + 1]; child.args = args; child.options = options;
        children.push(child); return child;
      } };
      return require(name);
    }
  });
  return { app, children, windows, files, errors, electron };
}

(async () => {
  const cold = launch(); await flush();
  assert.equal(cold.children[0].action, 'Start');
  assert.equal(cold.children[0].options.windowsHide, true);
  const win = cold.windows[0];
  assert.equal(win.options.webPreferences.nodeIntegration, false);
  assert.equal(win.options.webPreferences.sandbox, true);
  assert.ok(win.options.icon.endsWith('icon.ico'));
  cold.children[0].emit('exit', 0); await flush();
  assert.equal(win.url, 'http://localhost:3000');
  cold.app.emit('second-instance'); assert.equal(win.focused, true);
  let blocked = false;
  win.webContents.emit('will-navigate', { preventDefault: () => { blocked = true; } }, 'https://example.com');
  assert.equal(blocked, true);
  assert.equal(win.openWindow({ url: 'file:///C:/secret' }).action, 'deny');
  assert.equal(win.openWindow({ url: 'about:blank' }).action, 'allow');
  assert.equal(win.openWindow({ url: 'blob:http://localhost:3000/resume' }).action, 'allow');
  assert.equal(win.openWindow({ url: 'blob:http://localhost:3000.evil/resume' }).action, 'deny');
  let permission;
  await win.requestPermission(win.webContents, 'media', allowed => { permission = allowed; },
    { requestingUrl: 'https://example.com', mediaTypes: ['audio'] });
  assert.equal(permission, false);
  await win.requestPermission(win.webContents, 'media', allowed => { permission = allowed; },
    { requestingUrl: win.url, mediaTypes: ['video'] });
  assert.equal(permission, false);
  await win.requestPermission(win.webContents, 'media', allowed => { permission = allowed; },
    { requestingUrl: win.url, mediaTypes: ['audio'] });
  assert.equal(permission, true);
  assert.equal(win.checkPermission(win.webContents, 'media', win.url, { mediaType: 'audio' }), true);
  win.close(); win.close(); cold.app.quit(); await flush();
  assert.equal(cold.children.length, 2);
  assert.equal(cold.children[1].action, 'Stop');
  assert.equal(win.hidden, true);
  assert.ok(cold.files.size > 0);
  assert.notEqual(cold.app.exited, true);
  cold.children[1].emit('exit', 0); await flush();
  assert.equal(cold.app.exited, true); assert.equal(cold.files.size, 0);

  const early = launch(); await flush();
  early.windows[0].close(); await flush();
  assert.equal(early.children.length, 1, 'Stop must wait for Start to finish recording/rollback');
  assert.equal(early.files.size, 1, 'Closing during startup must signal cancellation');
  early.children[0].emit('exit', 1); await flush();
  assert.equal(early.errors.length, 0, 'Intentional cancellation must not show a startup error');
  assert.equal(early.children[1].action, 'Stop');
  early.children[1].emit('exit', 0); await flush(); assert.equal(early.app.exited, true);

  const failed = launch(); await flush();
  failed.children[0].emit('exit', 1); await flush();
  assert.equal(failed.errors.length, 1); assert.equal(failed.children[1].action, 'Stop');
  failed.children[1].emit('exit', 1); await flush();
  assert.notEqual(failed.app.exited, true, 'Failed cleanup must retain the window for retry');
  assert.equal(failed.windows[0].hidden, false);
  failed.windows[0].close(); await flush();
  assert.equal(failed.children[2].action, 'Stop');
  failed.children[2].emit('exit', 0); await flush(); assert.equal(failed.app.exited, true);

  const second = launch(false); await flush();
  assert.equal(second.children.length, 0); assert.equal(second.windows.length, 0);
  assert.equal(second.app.exited, true);
  console.log('PASS: window close/quit, startup cancellation, cleanup ordering/retry, singleton, icon and renderer boundaries.');
})().catch(error => { console.error(error); process.exitCode = 1; });
