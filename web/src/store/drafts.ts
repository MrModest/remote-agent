import { storage } from "./storage";

/** Prompts written and not sent yet, by session, for one server. They stay
 *  in this browser, in localStorage; the server never sees them. Each change
 *  reads what is stored first, so tabs keep each other's drafts. */
export class Drafts {
  private key: string;

  constructor(serverID: string) {
    this.key = "drafts." + serverID;
  }

  /** The session's draft, or "" for none. */
  get(sessionID: string): string {
    return this.load()[sessionID] ?? "";
  }

  /** Sets the session's draft; "" forgets it. */
  set(sessionID: string, text: string) {
    const texts = this.load();
    if ((texts[sessionID] ?? "") === text) return;
    if (text === "") delete texts[sessionID];
    else texts[sessionID] = text;
    this.save(texts);
  }

  /** Forgets the drafts of sessions that are gone from the server. */
  keep(isKept: (sessionID: string) => boolean) {
    const texts = this.load();
    const gone = Object.keys(texts).filter((id) => !isKept(id));
    if (gone.length === 0) return;
    for (const id of gone) delete texts[id];
    this.save(texts);
  }

  removeAll() {
    storage.remove(this.key);
  }

  private load(): Record<string, string> {
    try {
      const texts = JSON.parse(storage.get(this.key) ?? "{}");
      return texts && typeof texts === "object" && !Array.isArray(texts) ? texts : {};
    } catch {
      return {};
    }
  }

  private save(texts: Record<string, string>) {
    if (Object.keys(texts).length === 0) storage.remove(this.key);
    else storage.set(this.key, JSON.stringify(texts));
  }
}
