import type { SlashCommand } from "./models";

/** Only complete the leading command token; arguments and ordinary prose stay untouched. */
export function commandQuery(draft: string): string | undefined {
  return draft.startsWith("/") && !/\s/u.test(draft) ? draft.slice(1).toLowerCase() : undefined;
}

export function commandSuggestions(draft: string, commands: SlashCommand[]): SlashCommand[] {
  const query = commandQuery(draft);
  if (query === undefined) return [];
  const seen = new Set<string>();
  return commands.filter((c) => {
    if (seen.has(c.name)) return false;
    seen.add(c.name);
    return c.name.toLowerCase().includes(query) || !!c.description?.toLowerCase().includes(query);
  });
}
