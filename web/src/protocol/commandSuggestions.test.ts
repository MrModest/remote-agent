import { expect, it } from "vitest";
import { commandQuery, commandSuggestions } from "./commandSuggestions";
const commands = [{name:"compact"}, {name:"skill:prototype", description:"Answer a design question"}];
it("matches commands, namespaced skills and descriptions without changing prose or arguments", () => {
  expect(commandSuggestions("/", commands)).toEqual(commands);
  expect(commandSuggestions("/PROT", commands)).toEqual([commands[1]]);
  expect(commandSuggestions("/design", commands)).toEqual([commands[1]]);
  expect(commandSuggestions("/", [...commands, commands[0]])).toEqual(commands);
  expect(commandSuggestions("/missing", commands)).toEqual([]);
  for (const text of ["", "write /prot", " /prot", "/prototype ", "/prototype\n", "/prototype\targs"]) {
    expect(commandQuery(text)).toBeUndefined();
    expect(commandSuggestions(text, commands)).toEqual([]);
  }
});
