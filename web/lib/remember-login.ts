type SavedLogin = { email: string; password: string };
type EncryptedLogin = { key: CryptoKey; iv: Uint8Array; data: ArrayBuffer };

function storedLogin(value?: EncryptedLogin | null): Promise<EncryptedLogin | undefined> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open("interview_agent.remembered_login", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("login");
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error("Password storage is blocked."));
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => db.close();
      try {
        const transaction = db.transaction("login", value === undefined ? "readonly" : "readwrite");
        const store = transaction.objectStore("login");
        const operation = value === undefined ? store.get("saved")
          : value === null ? store.delete("saved") : store.put(value, "saved");
        transaction.oncomplete = () => { db.close(); resolve(operation.result); };
        transaction.onabort = () => { db.close(); reject(transaction.error); };
      } catch (error) { db.close(); reject(error); }
    };
  });
}

export async function forgetRememberedLogin() {
  await storedLogin(null);
}

export async function loadRememberedLogin(): Promise<SavedLogin | null> {
  try {
    const saved = await storedLogin();
    if (!saved) return null;
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: saved.iv as Uint8Array<ArrayBuffer> }, saved.key, saved.data);
    const login = JSON.parse(new TextDecoder().decode(plain)) as SavedLogin;
    if (typeof login.email !== "string" || !login.email || typeof login.password !== "string" || !login.password) throw new Error("Invalid saved login.");
    return login;
  } catch {
    await forgetRememberedLogin().catch(() => {});
    return null;
  }
}

export async function rememberLogin(email: string, password: string) {
  try {
    // ponytail: origin-local encryption, not a system password vault; same-origin scripts can decrypt.
    const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(JSON.stringify({ email, password })));
    await storedLogin({ key, iv, data });
  } catch {
    throw new Error("当前设备无法保存密码，请取消“记住密码”后重试。");
  }
}
