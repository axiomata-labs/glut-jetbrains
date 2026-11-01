# Glut for JetBrains IDEs

Glut beside PyCharm, IntelliJ IDEA, RustRover, WebStorm and the other JetBrains IDEs (2026.2 or later). Glut — a
terminal coding agent with its own sandbox, gate and sessions — runs in the IDE's terminal (or in any terminal in the
project's folder), and this plugin tells it what you work on:

- **The file, the cursor and the selection** go with each of your messages to Glut (it shows "⧉ 12 lines of
  src/main.rs" under them), as long as none of Glut's read rules holds the file back.
- **Your documents as the IDE holds them**: Glut reads an open document, unsaved changes too. It never changes a
  document with unsaved changes — save it first.
- **Glut's questions about edits as the IDE's own diffs**: where Glut's gate asks before an edit, the change opens as
  a diff tab, the file on the left, Glut's change on the right. The ✓ and ✕ in the diff's toolbar answer; Glut's own
  card in its terminal answers too, and the first answer counts. You may change the right side before accepting:
  Glut then writes your version instead of its own, and tells its model what you changed. Accepting writes nothing
  here: Glut writes the file once its gate allows it.
- **What is open and what the inspections find**, for Glut's model to ask.

The protocol is Glut's own, in Glut's repository: `docs/editor-protocol.md`.

## Install

```sh
./gradlew buildPlugin        # build/distributions/glut-jetbrains-0.1.0.zip
```

Then *Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…* and pick the zip. The plugin listens for each open project
(on 127.0.0.1 only, behind a fresh random token in `~/.glut/ide/<port>.json`, readable by you alone), so a Glut started
anywhere in the project's folder finds the IDE.

## Actions

*Open Glut* has the key ⌘Esc (Ctrl+Esc on Linux and Windows), the Glut tool window on the right side bar (its window
has the buttons), a flame in the main toolbar and one in the status bar; bind the other actions under *Settings ▸
Keymap ▸ Plug-ins ▸ Glut* the way you like. What you select goes to Glut with your next message by itself; *Send
Selection* only names it in your text.

| Action | Where | What it does |
|---|---|---|
| *Open Glut* | Tools ▸ Glut, ⌘Esc, the flame | Glut in the IDE's terminal: opens it, or shows the one there is |
| *Send Selection to Glut* | Tools ▸ Glut, the editor's menu | the selected lines as `@path#L10-22` in Glut's input |
| *Add File to Glut* | Tools ▸ Glut, the editor's and the project view's menu | the file as `@path` in Glut's input |

*Settings ▸ Tools ▸ Glut* names the program Glut is started as (`glut` by default) — kept for the IDE, never in a
project.

## Development

The build uses the IDE installed at `/Applications/PyCharm.app` and its own Java (set another with
`-Pglut.ide=/Applications/IntelliJ IDEA.app`):

```sh
export JAVA_HOME=/Applications/PyCharm.app/Contents/jbr/Contents/Home
./gradlew test               # the protocol, the server and the lock file against a stand-in editor
./gradlew verifyPlugin       # JetBrains' plugin verifier against that IDE
```

## License

MIT OR Apache-2.0
