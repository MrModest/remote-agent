// Drives the web client against a scratch rad with the fake harness, saving
// screenshots. See AGENTS.md, "Web client".
//
//   node e2e/smoke.mjs "$(rad pair --web http://localhost:5173 --print-url)" <out dir> <project folder name>
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { chromium } from "playwright";

const [pairURL, out = "e2e-shots", project = "proj"] = process.argv.slice(2);
if (!pairURL) {
  console.error("usage: node e2e/smoke.mjs <web pairing link> [out dir] [project folder]");
  process.exit(2);
}
mkdirSync(out, { recursive: true });

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1280, height: 820 }, colorScheme: "dark" });
const page = await context.newPage();
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
let n = 0;
const shot = async (name) => {
  await page.waitForTimeout(300);
  const path = join(out, `${String(++n).padStart(2, "0")}-${name}.png`);
  await page.screenshot({ path });
  console.log("shot", path);
};
const step = (s) => console.log("step:", s);

step("pair");
await page.goto(pairURL);
await page.getByRole("heading", { name: "Pair a server" }).waitFor();
await shot("pair");
await page.getByRole("button", { name: "Pair", exact: true }).click();
await page.getByRole("button", { name: "New session" }).first().waitFor();
await page.waitForFunction(() => !document.querySelector("button[aria-label='New session']:disabled"));
await shot("paired");

step("new session");
await page.getByRole("button", { name: "New session" }).first().click();
await page.getByRole("button", { name: "Add a folder…" }).click();
await page.locator(".folder-row").first().click(); // the root
await page.locator(".folder-row", { hasText: new RegExp(`^${project}`) }).first().click();
await page.getByRole("button", { name: "Use folder" }).click();
await page.locator(".harness-row", { hasText: "Fake" }).click();
await page.getByPlaceholder("What should the agent do?").fill("write a.txt");
await shot("new-session");
await page.getByRole("button", { name: "Start", exact: true }).click();

step("approval");
await page.getByRole("button", { name: "Allow", exact: true }).waitFor();
await shot("approval");
await page.getByRole("button", { name: "Allow", exact: true }).click();
await page.getByText("Echo: write a.txt").waitFor();

step("question");
const prompt = page.getByPlaceholder(/Prompt|Queue a follow-up/);
await prompt.fill("question");
await prompt.press("Enter");
await page.getByRole("button", { name: "Blue" }).click();
await shot("question");
await page.getByRole("button", { name: "Submit" }).click();
await page.getByText(/answer:/).first().waitFor();

step("picture and screenshot");
await prompt.fill("picture and screenshot");
await prompt.press("Enter");
await page.locator(".reply-image img").first().waitFor();
await page.locator(".tool-call .remote-image img").first().waitFor();
await shot("images");

step("upload an image");
const dataURL = await page.evaluate(() => {
  const c = document.createElement("canvas");
  c.width = 300;
  c.height = 200;
  const g = c.getContext("2d");
  g.fillStyle = "#8caaee";
  g.fillRect(0, 0, 300, 200);
  g.fillStyle = "#e78284";
  g.fillRect(40, 40, 120, 80);
  return c.toDataURL("image/png");
});
const png = Buffer.from(dataURL.split(",")[1], "base64");
await page.locator("input[type=file]").first().setInputFiles({ name: "shot.png", mimeType: "image/png", buffer: png });
await page.locator(".attachment img").waitFor();
await page.waitForFunction(() => !document.querySelector(".attachment .spinner"));
await prompt.fill("look at this");
await shot("attachment");
await prompt.press("Enter");
await page.getByText(/Echo \(1 image\(s\)\)/).waitFor();

step("slow, then stop");
await prompt.fill("slow slow slow slow slow slow slow slow slow slow slow slow slow slow");
await prompt.press("Enter");
await page.getByRole("button", { name: "Stop", exact: true }).waitFor();
await shot("running");
await page.getByRole("button", { name: "Stop", exact: true }).click();
await page.getByText("Interrupted").last().waitFor();

step("fail");
await prompt.fill("fail");
await prompt.press("Enter");
await page.getByText("Failed", { exact: true }).last().waitFor();

step("/compact");
await page.getByRole("button", { name: "Session", exact: true }).click();
await shot("menu");
await page.getByRole("menuitem", { name: /\/compact/ }).click();
await page.getByText(/compacted/i).waitFor();

step("expand a tool call");
await page.locator(".tool-header").first().click();
await shot("transcript");

step("changes");
await page.getByRole("button", { name: "Session", exact: true }).click();
await page.getByRole("menuitem", { name: "Changes" }).click();
await page.getByText(/file/).first().waitFor();
await page.waitForTimeout(500);
await shot("changes");
const fileRow = page.locator(".file-row").first();
if (await fileRow.count()) {
  await fileRow.click();
  await page.locator(".file-diff").waitFor();
  await shot("file-diff");
}
await page.keyboard.press("Escape");

step("terminal");
const terminalRows = (text) => page.locator(".terminal .xterm-rows", { hasText: text });
await page.getByRole("button", { name: "Session", exact: true }).click();
await page.getByRole("menuitem", { name: "Terminal" }).click();
await page.locator(".terminal .xterm").waitFor();
await page.waitForTimeout(1000); // the shell starting
await page.keyboard.type("echo $((6*7))");
await page.keyboard.press("Enter");
await terminalRows("42").waitFor();
await page.keyboard.press("Escape"); // the shell's, not the page's
await shot("terminal-over");
// Hidden and shown again, the same shell, with its output.
await page.getByRole("button", { name: "Hide Terminal" }).click();
await page.getByText("Echo: write a.txt").waitFor();
await page.getByRole("button", { name: "Session", exact: true }).click();
await page.getByRole("menuitem", { name: "Terminal" }).click();
await terminalRows("42").waitFor();
// ⌘J hides it and gives the prompt the keyboard; Ctrl+` there shows it
// again. In the terminal, Ctrl+J is the shell's.
await page.locator(".terminal textarea:focus").waitFor();
await page.keyboard.press("Meta+j");
await page.locator(".composer-field:focus").waitFor();
await page.keyboard.press("Control+Backquote");
await page.locator(".terminal textarea:focus").waitFor();
await page.keyboard.press("Control+j");
await page.waitForTimeout(300);
await terminalRows("42").waitFor({ timeout: 1000 });
for (const placement of ["below", "right"]) {
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await page.getByLabel("Opens").selectOption(placement);
  await page.getByRole("button", { name: "Done" }).click();
  await page.locator(`.terminal-${placement} .terminal .xterm-rows`, { hasText: "42" }).waitFor();
  await shot("terminal-" + placement);
}
await page.getByRole("button", { name: "Terminal", exact: true }).click();
await page.getByRole("menuitem", { name: "Close Shell" }).click();
await page.getByText("The shell was closed.").waitFor();
await page.getByRole("button", { name: "Hide Terminal" }).click();

step("worktree session: changes and revert");
await page.getByRole("button", { name: "New session" }).first().click();
await page.locator(".harness-row", { hasText: "Fake" }).click();
await page.getByLabel("Isolated git worktree").check();
await page.getByPlaceholder("What should the agent do?").fill("write b.txt");
await page.getByRole("button", { name: "Start", exact: true }).click();
await page.getByRole("button", { name: "Allow", exact: true }).click();
await page.getByText("Echo: write b.txt").waitFor();
await page.getByRole("button", { name: "Session", exact: true }).click();
await page.getByRole("menuitem", { name: "Changes" }).click();
await page.locator(".file-row", { hasText: "b.txt" }).waitFor();
await shot("worktree-changes");
await page.locator(".file-row", { hasText: "b.txt" }).click();
await page.locator(".diff-line.add").first().waitFor();
await shot("worktree-file-diff");
await page.getByRole("button", { name: "Changes" }).click();
await page.getByRole("button", { name: /Revert files/ }).click();
await page.getByRole("menuitem", { name: "Before turn 1" }).click();
await page.getByRole("button", { name: "Revert to before turn 1" }).click();
await page.locator(".form-message", { hasText: "Reverted 1 file" }).waitFor();
await shot("worktree-reverted");
await page.getByRole("button", { name: "Done" }).click();

step("settings and light theme");
await page.getByRole("button", { name: "Settings", exact: true }).click();
await page.locator("select").first().selectOption("light");
await shot("settings-light");
await page.getByRole("button", { name: "Done" }).click();
await shot("light");

step("phone");
await page.setViewportSize({ width: 390, height: 844 });
await shot("phone-session");
await page.getByRole("button", { name: "Back" }).click();
await shot("phone-sessions");

step("sidebar layouts");
await page.setViewportSize({ width: 1280, height: 820 });
const layout = async (name) => {
  await page.getByRole("button", { name: "Settings", exact: true }).first().click();
  await page.getByLabel("Layout").selectOption(name);
  await page.getByRole("button", { name: "Done" }).click();
};
await page.locator(".list-header", { hasText: "Servers" }).waitFor(); // the Inbox, the default
await layout("columns");
await page.locator(".session-column .session-row").first().waitFor();
await page.locator(".collection-row", { hasText: project }).click();
await page.locator(".session-column .bar-title", { hasText: project }).waitFor();
await shot("layout-columns");
// A project's collection, and a project's +, start a new session in that
// project. Forgetting the project used last leaves only them to choose it.
const startsInProject = async (what) => {
  const chosen = await page.locator(".form-row", { hasText: "Project" }).locator("select").evaluate((s) => s.selectedOptions[0]?.textContent);
  if (chosen !== project) throw new Error(`${what} starts a session in ${chosen}, not ${project}`);
  await page.getByRole("button", { name: "Cancel" }).click();
};
await page.evaluate(() => localStorage.removeItem("lastProject"));
await page.locator(".session-column").getByRole("button", { name: "New session" }).click();
await startsInProject("the project's collection");
await page.locator(".collection-row", { hasText: project }).hover();
await page.getByRole("button", { name: `New session in ${project}` }).click();
await startsInProject("the collection's +");
await layout("projects");
await page.locator(".project-row", { hasText: project }).waitFor();
await shot("layout-projects");
await page.locator(".project-row", { hasText: project }).hover();
await shot("project-plus");
await page.getByRole("button", { name: `New session in ${project}` }).click();
await startsInProject("the project's +");

step("forget a project");
await page.getByRole("button", { name: "New session" }).first().click();
const projectPicker = page.locator(".form-row", { hasText: "Project" }).locator("select");
await projectPicker.selectOption({ label: project });
// The server keeps a project while it has sessions that aren't archived.
await page.getByRole("button", { name: `Forget ${project}` }).click();
await page.getByText(/archive them first/).waitFor();
await shot("forget-refused");
const addRoot = async () => {
  await page.getByRole("button", { name: "Add a folder…" }).click();
  await page.locator(".folder-row").first().click(); // the root
  await page.getByRole("button", { name: "Use folder" }).click();
  // The folder added is chosen once the server answers.
  const added = await page.waitForFunction((p) => {
    const name = document.querySelector(".form-row select").selectedOptions[0]?.textContent;
    return name !== p && name !== "Choose…" && name;
  }, project);
  return added.jsonValue();
};
const root = await addRoot();
// The Projects layout shows it without sessions, until it's forgotten.
const rootRow = page.locator(".project-row", { hasText: root });
await rootRow.waitFor();
if ((await rootRow.locator(".row-plus-count").textContent()) !== "0") throw new Error(`${root} doesn't show that it has no sessions`);
await shot("project-without-sessions");
await page.getByRole("button", { name: `Forget ${root}` }).click();
await page.waitForFunction((name) => {
  const s = document.querySelector(".form-row select");
  return s.value === "" && ![...s.options].some((o) => o.textContent === name);
}, root);
await rootRow.waitFor({ state: "detached" });
await addRoot();
await page.getByRole("button", { name: "Cancel" }).click();
// From its menu in the sidebar too. In three columns, forgetting the chosen
// project shows All Sessions.
await layout("columns");
const projectMenu = async (name) => {
  await page.locator(".collection-row", { hasText: name }).hover();
  await page.getByRole("button", { name: `${name} actions` }).click();
};
await projectMenu(project);
if (!(await page.getByRole("menuitem", { name: "Forget project" }).isDisabled())) throw new Error(`${project} can be forgotten with its sessions`);
await page.keyboard.press("Escape");
await page.locator(".collection-row", { hasText: root }).click();
await projectMenu(root);
await shot("project-menu");
await page.getByRole("menuitem", { name: "Forget project" }).click();
await page.locator(".collection-row", { hasText: root }).waitFor({ state: "detached" });
await page.locator(".session-column .bar-title", { hasText: "All Sessions" }).waitFor();
await layout("projects");

step("collapse the server, which a reload keeps");
const header = page.locator(".server-header-main");
await header.click();
await page.locator(".server-header-main[aria-expanded=false]").waitFor();
await shot("collapsed");
await page.reload();
await page.locator(".server-header-main[aria-expanded=false]").waitFor();
await header.click();
await page.locator(".session-row").first().waitFor();

step("archive");
await page.locator(".session-row").first().hover();
await page.getByRole("button", { name: "Archive" }).first().click();
await page.getByRole("button", { name: "Archive", exact: true }).last().click();
await page.getByText(/Archived/).waitFor();

step("remove server");
await page.locator(".server-header").hover();
await page.getByRole("button", { name: / actions$/ }).click();
await page.getByRole("menuitem", { name: /Remove/ }).click();
await page.getByRole("button", { name: "Remove", exact: true }).click();
await page.getByText("No servers").waitFor();
await shot("removed");

await browser.close();
if (errors.length) {
  console.error("page errors:\n" + errors.join("\n"));
  process.exit(1);
}
console.log("ok");
