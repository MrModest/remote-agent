// As DraftTests in apple/RAKit/Tests/RAKitTests/StoreTests.swift.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { Drafts } from "./drafts";
import { SessionStore, type SessionHost } from "./sessionStore";

describe("Drafts", () => {
  let values: Map<string, string>;

  beforeEach(() => {
    values = new Map();
    vi.stubGlobal("localStorage", {
      getItem: (k: string) => values.get(k) ?? null,
      setItem: (k: string, v: string) => values.set(k, v),
      removeItem: (k: string) => values.delete(k),
    });
  });
  afterEach(() => vi.unstubAllGlobals());

  function store(id: string): SessionStore {
    const host = { drafts: new Drafts("srv") } as unknown as SessionHost;
    return new SessionStore(id, host);
  }

  it("outlive the store and the page", () => {
    store("a").draft = "half a thought";
    expect(store("b").draft).toBe("");
    // The next page load reads it back.
    expect(store("a").draft).toBe("half a thought");
    // Sent: forgotten.
    store("a").draft = "";
    expect(values.has("drafts.srv")).toBe(false);
  });

  it("keep another tab's drafts", () => {
    const tab1 = new Drafts("srv");
    const tab2 = new Drafts("srv");
    tab1.set("a", "from one tab");
    tab2.set("b", "from another");
    expect(new Drafts("srv").get("a")).toBe("from one tab");
    expect(new Drafts("srv").get("b")).toBe("from another");
  });

  it("forget sessions gone from the server", () => {
    const d = new Drafts("srv");
    d.set("a", "kept");
    d.set("b", "gone");
    d.keep((id) => id === "a");
    expect(d.get("a")).toBe("kept");
    expect(d.get("b")).toBe("");
    d.removeAll();
    expect(values.has("drafts.srv")).toBe(false);
  });
});
