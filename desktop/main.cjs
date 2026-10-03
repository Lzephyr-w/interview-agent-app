const { app, BrowserWindow, dialog, Menu } = require('electron');
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID } = require('node:crypto');

const root = path.resolve(__dirname, '..');
const logs = path.join(root, 'runtime-logs');
const icon = path.join(__dirname, 'icon.ico');
const origin = 'http://localhost:3000';
const powershell = path.join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe');
const cancelFile = path.join(logs, `desktop-cancel-${randomUUID()}`);
let window;
let startup = Promise.resolve();
let stopping;
let stopped = false;
let microphoneGranted = false;

app.setName('智面');
app.setPath('userData', path.join(app.getPath('appData'), 'Zhimian'));
app.setAppUserModelId('com.interviewagent.zhimian');

function isLocal(url) {
  try { return new URL(url).origin === origin; } catch { return false; }
}

function runServices(action) {
  return new Promise((resolve, reject) => {
    const args = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', path.join(root, 'desktop.ps1'),
      '-Action', action, '-NoDialog'];
    if (action === 'Start') args.push('-NoWindow', '-CancelFile', cancelFile);
    const child = spawn(powershell, args, { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    for (const stream of [child.stdout, child.stderr]) {
      stream.on('data', chunk => { output = (output + chunk.toString()).slice(-8000); });
    }
    child.on('error', reject);
    // Windows grandchildren can inherit pipe handles; launcher exit is the completion boundary.
    child.on('exit', code => {
      child.stdout.destroy();
      child.stderr.destroy();
      code === 0 ? resolve() : reject(new Error(`${action} failed (${code}).\n${output}\nLogs: ${logs}`));
    });
  });
}

function stop() {
  if (stopping) return stopping;
  // Wait for the launcher to record/roll back its children before Stop; never kill it mid-spawn.
  fs.writeFileSync(cancelFile, '');
  if (window && !window.isDestroyed()) window.hide();
  stopping = (async () => {
    await startup.catch(() => {});
    try {
      await runServices('Stop');
    } catch (error) {
      fs.appendFileSync(path.join(logs, 'desktop-window.log'), `${new Date().toISOString()} ${error.message}\n`);
      dialog.showErrorBox('智面停止失败', error.message);
      stopping = undefined;
      if (window && !window.isDestroyed()) window.show();
      return; // Keep the owner alive so closing again can retry cleanup.
    }
    fs.rmSync(cancelFile, { force: true });
    stopped = true;
    app.quit();
  })();
  return stopping;
}

function secureContents(contents, preview = false) {
  const safe = url => isLocal(url) || (preview && (url === 'about:blank' || url.startsWith(`blob:${origin}/`)));
  for (const event of ['will-navigate', 'will-redirect']) {
    contents.on(event, (e, url) => { if (!safe(url)) e.preventDefault(); });
  }
  contents.on('will-attach-webview', e => e.preventDefault());
  contents.setWindowOpenHandler(({ url }) => {
    if (!preview && (url === 'about:blank' || url.startsWith(`blob:${origin}/`))) {
      return { action: 'allow', overrideBrowserWindowOptions: {
        icon, autoHideMenuBar: true,
        webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, webSecurity: true }
      } };
    }
    return { action: 'deny' };
  });
  contents.on('did-create-window', child => secureContents(child.webContents, true));
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  fs.mkdirSync(logs, { recursive: true });
  app.on('second-instance', () => {
    if (!window || window.isDestroyed() || stopping) return;
    if (window.isMinimized()) window.restore();
    window.show();
    window.focus();
  });
  app.on('before-quit', event => {
    if (!stopped) { event.preventDefault(); void stop(); }
  });
  app.on('window-all-closed', () => { void stop(); });
  app.whenReady().then(async () => {
    if (stopping) return;
    Menu.setApplicationMenu(null);
    window = new BrowserWindow({
      width: 1280, height: 900, minWidth: 800, minHeight: 600, title: '智面', icon,
      backgroundColor: '#f5f7f4',
      webPreferences: { nodeIntegration: false, contextIsolation: true, sandbox: true, webSecurity: true }
    });
    window.setAppDetails({ appId: 'com.interviewagent.zhimian', appIconPath: icon,
      relaunchCommand: `"${process.execPath}" "${__dirname}"`, relaunchDisplayName: '智面' });
    window.on('close', event => {
      if (!stopped) { event.preventDefault(); void stop(); }
    });
    window.webContents.on('render-process-gone', () => { void stop(); });
    secureContents(window.webContents);
    const session = window.webContents.session;
    const trusted = (contents, url) => contents === window.webContents && isLocal(url);
    session.setPermissionCheckHandler((contents, permission, requestingOrigin, details) =>
      trusted(contents, requestingOrigin) && (
        permission === 'loopback-network' || permission === 'local-network-access' ||
        (permission === 'media' && details.mediaType === 'audio' && microphoneGranted)));
    session.setPermissionRequestHandler(async (contents, permission, callback, details) => {
      if (!trusted(contents, details.requestingUrl)) return callback(false);
      if (permission === 'loopback-network' || permission === 'local-network-access') return callback(true);
      if (permission !== 'media' || !details.mediaTypes?.length ||
          details.mediaTypes.some(type => type !== 'audio')) return callback(false);
      if (!microphoneGranted) {
        try {
          const answer = await dialog.showMessageBox(window, {
            type: 'question', title: '智面', message: '允许智面使用麦克风进行面试录音？',
            buttons: ['允许', '拒绝'], defaultId: 0, cancelId: 1
          });
          microphoneGranted = answer.response === 0;
        } catch { return callback(false); }
      }
      callback(microphoneGranted && !stopping);
    });
    await window.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(
      '<meta charset="utf-8"><title>智面</title><body style="font:18px system-ui;background:#f5f7f4;color:#245842;display:grid;place-content:center;height:90vh">正在启动智面…</body>'));
    if (stopping) return;
    startup = runServices('Start');
    try {
      await startup;
      if (!stopping) await window.loadURL(origin);
    } catch (error) {
      if (!stopping) dialog.showErrorBox('智面启动失败', error.message);
      void stop();
    }
  }).catch(error => {
    dialog.showErrorBox('智面窗口失败', error.message);
    void stop();
  });
}
