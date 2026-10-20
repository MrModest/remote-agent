import Testing
@testable import RAKit

@Test func commandSuggestions() {
    let commands = [SlashCommand(name: "compact"), SlashCommand(name: "skill:prototype", description: "Answer a design question")]
    #expect(SlashCommand.suggestions(for: "/", from: commands) == commands)
    #expect(SlashCommand.suggestions(for: "/PROT", from: commands) == [commands[1]])
    #expect(SlashCommand.suggestions(for: "/design", from: commands) == [commands[1]])
    #expect(SlashCommand.suggestions(for: "/", from: commands + commands) == commands)
    #expect(SlashCommand.suggestions(for: "/missing", from: commands).isEmpty)
    for text in ["", "write /prot", " /prot", "/prototype ", "/prototype\n", "/prototype\targs"] {
        #expect(SlashCommand.query(text) == nil)
        #expect(SlashCommand.suggestions(for: text, from: commands).isEmpty)
    }
}
