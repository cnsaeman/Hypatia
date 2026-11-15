# Hypatia

A LaTeX editor in Java/Swing, built on RSyntaxTextArea (editor component) and FlatLaf (look and feel). It runs on Linux, macOS and Windows 10 or later (Java 21 or later required).

**Prototype 0.1** covers:

- Tabs with several files, line numbers, code folding of `\begin…\end` blocks
- LaTeX highlighting: commands, structure commands, environment names, `\label`/`\ref`/`\cite` keys, inline, display and `align`-type math, `verbatim`/`lstlisting`, `comment` environments
- Soft wrap toggle (toolbar, View menu, Alt+Z); wrapped rows continue at the line's indentation
- Environment indentation (4 spaces): Enter after `\begin{…}` indents, typing `\end{…}` aligns it with its `\begin`, Mod+Shift+I re-indents the selection or document. This includes the `\begin{document}` body; Settings can turn that off
- Enter at the end of a line with an unclosed `\begin{x}` (also with arguments, `\begin{tabular}{cc}`) adds the aligned `\end{x}` and puts the cursor indented in between (can be switched off in Settings)
- Tab in a line's indentation, left of where the line belongs, jumps there (indenting the line if needed); otherwise it inserts 4 spaces
- Find/replace bar (regex, whole word, in selection), go to line, toggle comment (Ctrl+/)
- Matching brackets framed: `{`, `[`, `(` right of the cursor or `}`, `]`, `)` left of it, with their partners (each kind separately; escaped ones and comments skipped)
- Home goes to the end of the indentation, a second Home to column 1; on a wrapped continuation row, the first Home goes to the start of that row
- Refactoring (Tool+R, Edit > Refactor Selection): renames the selected text (e.g. a label) in the body of all project files; only complete keys (eq:1 does not hit eq:10); changed files open in tabs, unsaved
- Shift+click in the editor shows the clicked line in the PDF (forward search)
- Build with a configurable command (default `latexmk`), with a clickable error/warning list and error lines marked in the editor
- After a run with errors, the cursor jumps to the first error (behind the command TeX stopped at); the line stays bright red until you type
- Build (Tool+X), and single runs: LaTeX > Run pdfLaTeX (Tool+C) and Run BibTeX (Tool+B), commands in Settings > Building (defaults `pdflatex … %f`, `bibtex %n`; use `biber %n` for biblatex)
- `% !TeX bibliography = path` names the .bib file(s) for Ctrl+B when Hypatia cannot find them (e.g. named in a style file); it replaces the automatic detection
- LaTeX > Switch BibTeX File: moves the project to another .bib file, matching the cited entries by DOI, arXiv number, then title and authors; review old and new entry side by side, choose other entries by search; OK rewrites the keys in all citation commands and points `\bibliography`/`\addbibresource` to the new file
- View > Errors and Warnings (Shift+F8): everything in the last run's `.log` and `.blg` by category (errors, undefined references and citations, multiply defined labels, over/underfull boxes, bibliography, other), with the right file and line
- Main-file detection: `% !TEX root = main.tex`, then `\documentclass`, then asks once and remembers
- Forward search (Tool+V) and inverse search with Okular or Zathura (Linux), Skim (macOS) or SumatraPDF (Windows); Settings > Viewer Integration fills in the commands and shows what to enter in the viewer
- Single instance: a second `hypatia` call hands its file and line to the running window
- Macros defined in `macros.cfg` in the settings folder, run by a shortcut or a typed character sequence
- Spell checking as you type (Hunspell dictionaries, checked in the background), with suggestions on right-click and a Spellcheck Document dialog; LaTeX > Download Dictionary fetches one of LibreOffice's ~100 dictionaries
- Style checks from regular expressions and Java plugins, marked with purple squiggles
- Structure sidebar (Ctrl+Shift+O): the whole project's \part … \paragraph headings as a tree, including \input'ed files; click to jump, follows the cursor
- Detachable editors (F12): an editor in its own window, next to the main one
- Every file reopens where you left it (cursor and scroll position, kept in `positions.properties` in the settings folder)
- Light and dark themes (Settings or View menu), font and size, soft wrap
- Session restore, recent files, reload when files change on disk
- Manual: `doc/manual.tex`; Help > View Manual (F1) opens the compiled `manual.pdf` (put into the JAR if it exists when building)
- Help > Check TeX Installation: where Hypatia finds pdflatex, latexmk, bibtex, biber and the viewer

## Building in NetBeans

Open the folder as a project (File > Open Project). NetBeans recognises the Maven project.

Clean and Build produces `target/hypatia.jar`: one runnable JAR with all dependencies, made by the shade plugin. Requires JDK 21.

Run it with `java -jar target/hypatia.jar`, or with Run in NetBeans (main class `atlantis.hypatia.Main`).

## Settings folder

| System | Folder |
| --- | --- |
| Linux | `~/.config/hypatia` (`$XDG_CONFIG_HOME/hypatia`) |
| macOS | `~/Library/Application Support/Hypatia` |
| Windows | `%APPDATA%\Hypatia` |

`HYPATIA_CONFIG` overrides it. Below, paths like `keys.properties` are in this folder.

## Installing on Linux (current user)

```sh
packaging/install.sh
```

This copies the JAR to `~/.local/share/hypatia/` (with the manual, `doc/manual.pdf`, compiled with latexmk if it is not there yet), the `hypatia` launcher to `~/.local/bin/`, and the icons and a `.desktop` entry, so KDE shows Hypatia with its icon.

## Running on macOS and Windows

Share `target/hypatia.jar` (build it after compiling `doc/manual.pdf`, so the manual is inside). Users need Java 21+ (e.g. adoptium.net) and a TeX distribution (MacTeX; MiKTeX or TeX Live on Windows). Start it with a double click or `java -jar hypatia.jar`; on Windows, `packaging/hypatia.cmd` next to the JAR starts it without a console window.

- **macOS:** TeX is found in `/Library/TeX/texbin` even when started from the Dock. Menus are in the screen menu bar; Settings and Quit are in the Hypatia menu.
- **Windows:** MiKTeX's `latexmk` needs Perl (e.g. Strawberry Perl); without it, use `pdflatex …` as the build command. Hypatia says so in the log when this happens.
- **Spelling:** there are no system Hunspell dictionaries; use LaTeX > Download Dictionary (or LibreOffice's, which Hypatia also finds).

## PDF viewer

Choose the viewer in Settings > Viewer Integration. That fills in the forward-search commands and shows the inverse-search command to enter in the viewer (with a Copy button). Without a choice, the first installed one is used.

| Viewer | Inverse search |
| --- | --- |
| Okular (Linux) | Settings > Configure Okular > Editor > Custom Text Editor: `hypatia --line %l %f`; Shift+click in Browse mode |
| Zathura (Linux) | passed by Hypatia (`-x`); Ctrl+click |
| Skim (macOS) | Skim > Settings > Sync, preset Custom, Command and Arguments as shown; Cmd+Shift+click |
| SumatraPDF (Windows) | Settings > Options > inverse search command line as shown; double-click |

- **Forward search (editor → PDF):** Tool+V, Shift+click in the editor, or automatically after a successful build.
- **One Okular window per document** (Settings > Viewer Integration, on by default): Hypatia asks the running Okular windows over D-Bus (`dbus-send`, `gdbus` or `qdbus`) which one shows the PDF and jumps there, or starts a new Okular. Files of the same main file share a window. Okular's "Open new files in tabs" must be off.
- Both directions need a `.synctex.gz`. The default build command passes `-synctex=1`.
- When Hypatia runs from a JAR, the inverse-search command contains the full paths of Java (`javaw` on Windows) and the JAR.

## Windows, positions, structure

- **Structure sidebar** (View > Structure Sidebar, Ctrl+Shift+O): headings from `\part` down to `\paragraph`, starred ones marked `*`, for the whole project.
  - Headings from `\input`/`\include`d files appear where they are included.
  - The tree is the same in every file of the project. A file without `% !TeX root` uses the main file of an open project that includes it.
  - Click or Enter jumps to a heading, opening its file if needed; Esc returns to the editor. The heading around the cursor is selected as you move.
  - The width and whether the sidebar is shown are remembered.
- **Detach** (F12): moves the current editor into its own window with its own find bar. Shortcuts work there and act on that editor. F12 again, or closing that window, puts it back; Ctrl+W closes the file. The detached window remembers where you last put it. (If Yakuake is installed it uses F12; change one of them.)
- **Positions:** the cursor and scroll position of every file are saved when you close it, save it, switch windows or quit, and restored when it is opened again (up to 1000 files).
- **Second viewer** (LaTeX > Open PDF in New Viewer, Shift+F6): opens the PDF in a new, independent viewer window, while Tool+V keeps using the existing one. The command is in Settings > Viewer Integration.

## Command line

```
hypatia paper.tex
hypatia --line 120 chapters/intro.tex
hypatia chapters/intro.tex:120
hypatia --new-instance file.tex     # don't hand over to a running window
```

## Citations and references

- **Ctrl+B: citation.** Searches the project's BibTeX files. Hypatia finds them from `\bibliography{…}` or `\addbibresource{…}` in the main file and its included files, then via `kpsewhich`, and otherwise uses every `.bib` file next to the main file. Every word typed must occur in the author/editor or title field. Accents and braces are ignored, so `schafer` finds `Sch{\"a}fer`. Up to 10 matches are shown, with the entry's source on the right.
- **Ctrl+L: reference.** Searches all `\label{…}` in the project. Labels whose key contains all the words come first, then labels that match in the ±5 lines around them. The highlighted source is shown on the right.
- **Keys in the popup:** Up/Down (and Page Up/Down) select, Enter inserts, Esc cancels.
- **What gets inserted:** `\cite{key}`, or `\ref{key}` (`\eqref{key}` for labels in `equation`, `align`, …).
- **Inside an existing argument:** if the caret is inside `\cite{…}` or `\cref{…}`, only the key is inserted. Whatever was typed after the last comma pre-fills the search and is replaced.
- **Changing the commands:** set `cite.command`, `ref.command` and `ref.mathCommand` in `hypatia.properties`.
- **Ctrl+D: go to label.** With the cursor in a reference (`\ref{…}`, `\eqref`, `\cref{a,b}` – the key under the cursor – and any other `…ref` command, `\hyperref[…]`), opens the `\label` in the project's files.
- **Ctrl+U: uses of a label.** With the cursor in a `\label{…}` (or a reference), lists every reference to it with ±5 lines of context; Enter jumps there. Comments are ignored.

## Spell checking

Misspelled words get a light red squiggle shortly after you stop typing.

**Right-click on a word** for:
- suggestions,
- Add to Dictionary,
- Ignore for Now (until Hypatia is closed).

**LaTeX > Spellcheck Document (F7)** goes through the current file, starting at the cursor and wrapping around once. You can Replace, Replace All, Ignore, Ignore All or Add to Dictionary. Enter replaces, Up/Down choose a suggestion, and Esc closes.

**Language**, in this order of priority:
1. a line `% !TeX spellcheck = en_GB` (or `de_DE`, `en-US`, …) in the first 50 lines of the file,
2. the same line in its main file (from `% !TeX root`),
3. the default from Settings (initially `en_GB`).

The language shows in the status bar. Clicking it, or LaTeX > Spelling Language, writes the `% !TeX spellcheck` line for you. Turn checking as you type on or off under View > Check Spelling as You Type.

**Dictionaries** are the system's Hunspell `.aff`/`.dic` files, read with Apache Lucene's Hunspell implementation.
- **Getting dictionaries:** LaTeX > Download Dictionary (also in the status-bar language menu) fetches one from LibreOffice's collection (github.com/LibreOffice/dictionaries) into `dictionaries/`, with its README (licence). On openSUSE also e.g. `sudo zypper install myspell-en_GB myspell-de_DE`.
- **Where Hypatia looks:** `dictionaries/` in the settings folder; on Linux `~/.local/share/hunspell`, `/usr/share/hunspell`, `/usr/share/myspell`; on macOS `~/Library/Spelling`, `/Library/Spelling`; on macOS and Windows also an installed LibreOffice's dictionaries.
- **Your additions** go to `dictionaries/<lang>.txt`, one word per line. Edit it with LaTeX > Edit User Dictionary; saving applies it.
- **Capitalisation:** an added lower-case word is also accepted capitalised at the start of a sentence.

**What is checked:**
- Only the document body: everything after `\begin{document}`, or the whole file if it has none (an `\input`'ed chapter).
- **Not checked:** comments, math (`$…$`, `\(…\)`, `\[…\]`, math environments), words in capitals (acronyms), words next to digits, and single letters.
- **LaTeX accents are resolved** before checking: `Sch\"afer`, `Sch{\"a}fer`, `\'e`, `\v{c}`, `\ss`, `\o`, and so on. In German documents babel's `"a`, `"s` are too.
- **Replacements are written in the style the word used**, so `Sch\"afre` becomes `Sch\"afer` rather than `Schäfer`.
- **Commands and arguments:** every LaTeX command is ignored together with the arguments directly after it, unless it is listed in `spellcheck.cfg` (LaTeX > Edit Spell-Check Commands).
  - In a listed command, `...` marks an argument to check: `\emph{...}`, `\section[...]{...}`, `\href{}{...}`.
  - The same file sets the arguments checked after `\begin{env}` (`\begin{theorem}[...]`), plus `math-environment` and `ignore-environment` lines.
  - The defaults cover the usual text, sectioning and theorem commands, and `\text{...}` inside math.

## Style checks

**Style > Check Style (F9)** checks the current file and underlines problems in purple. The explanation shows in a bar below the editor when the cursor is on a problem, and as a tooltip on hover.
- **Navigation:** Alt+Page Down / Alt+Page Up jump to the next or previous problem.
- **Updates:** once a style is active, the file is re-checked after each pause in typing.
- **Clearing:** Style > Clear Style Marks (Shift+F9, or the ✕ in the bar) removes the marks and stops checking.
- **Choosing the style:**
  - F9 uses the file's style: `% !TeX style = name` in its first 50 lines, or in its main file. Without one it uses the style called `default`.
  - Style > Check with Style runs any other style.

Everything lives in `styles/` in the settings folder, which Hypatia creates with examples on first use:

```
styles/
  styles.cfg          which regexp files and plugins make up each style
  regexps/*.txt       regular expressions with explanations
  plugins/            compiled StylePlugin classes (.class, with package folders) or .jar files
```

**styles.cfg** (Style > Edit Styles):

```
>>STYLE:default
regexps/typography.txt
regexps/latex.txt

>>STYLE:paper
include default
regexps/academic.txt
plugins/LongSentences.class
```

Entries are `regexps/<file>`, `plugins/<Name>.class` (a class in a package goes at `plugins/pkg/Name.class`), `plugins/<file>.jar` (every StylePlugin in the jar), or `include <style>`. Wildcards work: `regexps/*`, `plugins/**`.

**Regexp files** (Style > Edit Regular Expressions):

```
>>Repeated word: "$1"
(?i)\b(\p{L}+)\s+\1\b
```

- A `>>` line gives the explanation for the Java regular expressions on the lines below it, one per line.
- `$1`, `$2`, … in the explanation are replaced by the matched groups.
- `^` and `$` match at line starts and ends.
- Lines starting with `#` are comments.
- Only the document body is searched, and matches that start in a `%` comment are ignored.
- A regexp that runs longer than 2 s is skipped, and a message says which one.

**Plugins** implement `atlantis.hypatia.style.StylePlugin`:

```java
public interface StylePlugin {
    ArrayList<Problem> checkDocument(String s);   // s: the whole source
}
public record Problem(String explanation, int start, int end) {}  // offsets into s
```

- **Compiling** against the installed jar puts the class in the right place:
  ```
  javac -cp ~/.local/share/hypatia/hypatia.jar -d ~/.config/hypatia/styles/plugins MyCheck.java
  # macOS/Windows: the path of your hypatia.jar and the settings folder; LongSentences.java's header shows the exact command
  ```
- **Example:** `plugins/LongSentences.java` (sentences over 40 words) shows how. Compile it, then uncomment its line in `styles.cfg`.
- **Instances:** a plugin needs a public no-argument constructor. One instance is reused and called from a background thread, one check at a time.
- **Reloading:** F9 reloads styles.cfg, the regexp files and the plugins, so a recompiled plugin takes effect on the next F9. Saving styles.cfg or a regexp file in Hypatia also reloads.
- **Failures:**
  - A plugin that throws is reported in the status bar.
  - Problems with impossible positions are dropped.
  - A plugin that takes longer than 5 s is abandoned and switched off until the next F9. The limit is `style.pluginTimeout` in milliseconds in `hypatia.properties`.

## Macros

Macros are defined in `macros.cfg` in the settings folder. Hypatia creates the file with examples on first start, and File > Macros opens it. Saving it applies the changes, and problems are reported in the status bar.

```
>>MACRO:wrap-equation:key        shortcut: set runmacro.wrap-equation in keys.properties
>>SELECT CURRENT BLOCK
>>INSERT LINES
\begin{equation}
    >CURSOR<
\end{equation}

>>MACRO:emph:text=\\em           runs when \\em is typed quickly in a .tex file
>>INSERT \emph{>CURSOR<}
```

**Commands:**
- `>>SELECT CURRENT BLOCK` selects the lines around the cursor, up to an empty line or a line indented less than the current one. More deeply indented lines are included.
- `>>CLEAR ENVIRONMENT` removes the innermost `\begin{…}`/`\end{…}` around the selection or cursor, including arguments such as `[Title]` or `{cc}`. It never touches `document`. Lines left empty are removed, and anything else on them (e.g. a `\label`) is kept. The content is de-indented one level and left selected, so a following `INSERT LINES` can rewrap it.
- `>>INSERT <text>` inserts text at the cursor.
- `>>EXECUTE <Class>.<method>` calls `public static void method(javax.swing.text.Document doc, int start, int end)` of a compiled class in `macros/` in the settings folder (package names allowed: `tools.Stamp.stamp`). The method changes `doc` directly; `start`/`end` are the selection. Compile with `javac -d ~/.config/hypatia/macros Upper.java`; the class is reloaded on every run.
- `>>INSERT LINES` inserts the lines that follow, up to the next `>>` line.
  - The lines are indented like the current line.
  - On an empty line, the indentation comes from the line above, one level deeper after an open `\begin`.
  - After other text, the first line continues the current line.
  - Trailing empty lines are dropped.

**The `>CURSOR<` marker** in `INSERT` and `INSERT LINES` marks where the cursor goes.
- Selected text is moved there and re-indented to that line, and the cursor ends up after it.
- Without `>CURSOR<`, a selection is replaced.

**Triggers:**
- `:key` triggers: Hypatia adds an empty `runmacro.<name> =` entry to `keys.properties` for you to fill in.
- `:text=` triggers:
  - All the characters must be typed within one second. Change this with `macro.triggerWindow` (in ms) in `hypatia.properties`.
  - Pasted text never triggers a macro.
  - Triggers are active in `.tex`, `.sty`, `.cls`, `.bib` and unsaved files, but not in `macros.cfg` itself.
  - Any character sequence works as a trigger. If one trigger occurs inside another (e.g. `\\e` and `\\eq`), the shorter one fires first, and Hypatia reports the clash.

**Other details:**
- A whole macro is one undo step.
- Names may contain letters, digits, `_`, `-` and `.`.
- Lines starting with `>>#` are comments.

## Keys

All shortcuts are in `keys.properties`, which is created with the defaults on first start. Open it with File > Keyboard Shortcuts, edit, and save; the changes apply immediately. Format: `latex.cite = Mod+B`. Modifiers: `Ctrl`, `Shift`, `Alt` (`Option`), `Meta` (`Super`/`Win`/`Cmd`), and two that depend on the system:

| | Mod | Tool |
| --- | --- | --- |
| Linux | Ctrl | Meta (Windows key) |
| macOS | Cmd | Control |
| Windows | Ctrl | Ctrl+Shift |

KDE keeps some Meta combinations for itself (Meta+V clipboard, Meta+E, Meta+L, Meta+D, Meta+arrows, Meta+1…9), and those never reach Hypatia; free Meta+V in System Settings > Keyboard > Shortcuts for Tool+V. Windows keeps almost all Win combinations, hence Ctrl+Shift there. On Mac keyboards, F keys need fn unless set as standard function keys. A file from an earlier version is updated once: F5, Shift+F5, Ctrl+F5, F6 and Meta+R, if unchanged, become Tool+X, Tool+C, Tool+B, Tool+V and Tool+R. An empty value removes a shortcut, and conflicts or unreadable keys are reported in the status bar. Shortcuts set there take precedence over the editor's built-in keys.

Defaults (Ctrl is Cmd on a Mac):

| Key | Action |
| --- | --- |
| Ctrl+B | Insert citation |
| F7 | Spellcheck document |
| F9 / Shift+F9 | Check style / clear style marks |
| Alt+Page Down / Alt+Page Up | Next / previous style problem |
| Ctrl+L | Insert reference |
| Ctrl+D / Ctrl+U | Go to label / list the uses of a label |
| Tool+X | Build (again to stop) |
| Tool+C / Tool+B | Run pdfLaTeX / Run BibTeX once |
| Tool+V | Show line in PDF |
| Shift+F6 | Open PDF in a new viewer window |
| F12 | Detach editor / put it back |
| F1 | View manual |
| Ctrl+Shift+O | Structure sidebar on/off |
| F8 | Build log |
| Shift+F8 | Errors and warnings |
| Ctrl+F / Ctrl+R | Find / replace |
| F3 / Shift+F3 | Next / previous match |
| Ctrl+G | Go to line |
| Ctrl+/ | Toggle comment |
| Ctrl+Shift+I | Re-indent environments (selection or document) |
| Tool+R | Refactor selection (rename in the whole project) |
| Alt+Z | Wrap long lines on/off |
| Ctrl+= / Ctrl+- / Ctrl+0 | Font size |
| Ctrl+, | Settings |

Settings live in `hypatia.properties`.

### Build command placeholders

| Placeholder | Meaning |
| --- | --- |
| `%f` | Main file |
| `%n` | Main file without extension |
| `%d` | Main file's folder |
| `%p` | PDF |
| `%t` | Current file |
| `%l` | Current line |
| `%c` | Current column |

Commands are split like a shell would split them, but run without a shell, so paths with spaces need no quoting. For pipes, use `sh -c '…'` (Windows: `cmd /c …`). On Windows a backslash is part of a path, except before `"`. Programs are looked up on the PATH plus the usual TeX folders (MacTeX, Homebrew, MiKTeX, TeX Live), which are also passed on to them.

## Code layout

```
atlantis.hypatia
  Main, OpenRequest, InstanceServer   start-up, command line, single instance (Unix-domain socket, also on Windows)
  Platform                            OS differences: settings folder, opening files, finding TeX programs, Hypatia's own command line
  Viewers                             PDF viewer presets per system (forward/inverse search)
  OkularWindows                       one Okular window per PDF: finds it over D-Bus or starts one
  Settings                            properties file, session, recent files, remembered main files
  Positions                           last editing position per file
  editor/
    HypatiaTextArea                   RSyntaxTextArea using the hanging-indent wrapped view
    LatexIndenter                     environment indentation (Enter, auto \end, Tab, \end alignment, re-indent)
    FastFoldManager                   caching wrapper for RSTA's fold manager (fast layout with line wrap)
  Keymap                              keys.properties: commands, defaults, parsing
  macro/
    Macro, MacroFile                  macros.cfg format and parser (with default examples)
    MacroRunner                       the commands; one undoable edit per macro
    TextTriggers                      typed-sequence triggers
    LatexTokenMaker                   hand-written tokenizer (states: normal, math, verbatim, comment env)
    LatexFoldParser                   \begin/\end folding
    EditorPalette                     colour schemes (dark one taken from the icon)
    EditorTab                         one file: text area, load/save, go-to-line, error marks
  tex/
    CommandTemplate                   placeholder substitution
    RootResolver                      main-file detection
    ProcessRunner                     build process, detached viewer
    LogParser                         errors/warnings from TeX output
    ProjectScanner                    included files and .bib files of a project
    Bibliography                      BibTeX parser and author/title search
    Labels                            \label index with context and environment
    References                        \label and \ref... keys in the text, uses and definitions of a label
    Outline                           project heading tree (follows \input/\include)
  spell/
    SpellConfig                       spellcheck.cfg: checked command arguments, math/ignored environments
    LatexWordScanner                  words of the document body, accents resolved, source ranges kept
    Dictionaries, SpellDictionary     finding/loading Hunspell dictionaries (Lucene), user additions
    DictionaryDownloader              LibreOffice dictionary list and download
    SpellChecker                      RSTA parser: squiggles; right-click suggestions
  style/
    Problem, StylePlugin              the plugin API
    StyleLibrary                      styles.cfg, regexp files, plugin loading (fresh class loader each time)
    StyleRunner                       runs a style: regexps on the body, plugins with a time limit
    DefaultStyles                     example files written on first use
  ui/
    MainFrame                         window, menus, toolbar, build/view logic
    ReferenceInserter                 citation/reference popups and insertion
    LabelNavigator                    go to label (Ctrl+D), uses of a label (Ctrl+U)
    SearchPopup, PreviewPane          generic keyboard search popup, highlighted snippet
    SpellCheckDialog                  Spellcheck Document
    StyleMarks                        purple marks, explanation bar, re-check while typing
    OutlinePanel                      structure sidebar
    FindBar, LogPanel, SettingsDialog, WelcomePanel, Theme, Icons, QuietSplitPane
```

`org.fife.ui.rsyntaxtextarea.IndentedWrappedSyntaxView` is RSyntaxTextArea's `WrappedSyntaxView` with the hanging indent added and fold lookups skipped when nothing is folded (changes marked `Hypatia:`). It sits in RSTA's package because it needs package-private types; when upgrading RSTA, compare it with the new `WrappedSyntaxView`.

## Next steps

These are the natural places to extend the prototype:

- **Grammar checking:** LanguageTool (pure Java) could reuse `LatexWordScanner`'s idea of what text is.
- **Integrated PDF preview:** use Apache PDFBox to render pages, and `synctex view -i line:col:file -o file.pdf` to find the box to highlight.
- **Section folding:** `Outline` already knows where each section starts.
