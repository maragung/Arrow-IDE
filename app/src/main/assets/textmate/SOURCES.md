# TextMate grammar & theme sources

All files in this directory are unmodified upstream TextMate grammar/theme
files. No grammar JSON in this tree was hand-written. This file documents
where each asset came from so it can be re-fetched or updated.

## Theme

| File | Source |
|------|--------|
| `darcula.json` | sora-editor demo app, tag `0.23.6`: `app/src/main/assets/textmate/darcula.json` from https://github.com/Rosemoe/sora-editor (tag 0.23.6, LGPL-2.1) |

## Grammars and language configurations taken from the sora-editor demo app

Copied verbatim from https://github.com/Rosemoe/sora-editor, tag `0.23.6`,
`app/src/main/assets/textmate/<lang>/...` (LGPL-2.1; upstream grammars are
MIT, see below):

- `java/` — `syntaxes/java.tmLanguage.json`, `language-configuration.json`
- `javascript/` — `syntaxes/JavaScript.tmLanguage.json`, `language-configuration.json`
- `kotlin/` — `syntaxes/Kotlin.tmLanguage` (JSON payload, no file extension), `language-configuration.json`
- `python/` — `syntaxes/python.tmLanguage.json`, `language-configuration.json`
- `markdown/` — `syntaxes/markdown.tmLanguage.json`, `language-configuration.json`

## Grammars and language configurations taken from microsoft/vscode

Copied verbatim from the `main` branch of https://github.com/microsoft/vscode
(MIT license) on 2026-09-11:

| Local file | Upstream path (repo: microsoft/vscode) |
|------------|----------------------------------------|
| `typescript/syntaxes/TypeScript.tmLanguage.json` | `extensions/typescript-basics/syntaxes/TypeScript.tmLanguage.json` |
| `typescript/language-configuration.json` | `extensions/typescript-basics/language-configuration.json` |
| `json/syntaxes/JSON.tmLanguage.json` | `extensions/json/syntaxes/JSON.tmLanguage.json` |
| `json/language-configuration.json` | `extensions/json/language-configuration.json` |
| `yaml/syntaxes/yaml.tmLanguage.json` | `extensions/yaml/syntaxes/yaml.tmLanguage.json` |
| `yaml/syntaxes/yaml-1.0.tmLanguage.json` | `extensions/yaml/syntaxes/yaml-1.0.tmLanguage.json` |
| `yaml/syntaxes/yaml-1.1.tmLanguage.json` | `extensions/yaml/syntaxes/yaml-1.1.tmLanguage.json` |
| `yaml/syntaxes/yaml-1.2.tmLanguage.json` | `extensions/yaml/syntaxes/yaml-1.2.tmLanguage.json` |
| `yaml/syntaxes/yaml-1.3.tmLanguage.json` | `extensions/yaml/syntaxes/yaml-1.3.tmLanguage.json` |
| `yaml/syntaxes/yaml-embedded.tmLanguage.json` | `extensions/yaml/syntaxes/yaml-embedded.tmLanguage.json` |
| `yaml/language-configuration.json` | `extensions/yaml/language-configuration.json` |
| `c/syntaxes/c.tmLanguage.json` | `extensions/cpp/syntaxes/c.tmLanguage.json` |
| `c/language-configuration.json` | `extensions/cpp/language-configuration.json` |
| `cpp/syntaxes/cpp.tmLanguage.json` | `extensions/cpp/syntaxes/cpp.tmLanguage.json` |
| `cpp/syntaxes/cpp.embedded.macro.tmLanguage.json` | `extensions/cpp/syntaxes/cpp.embedded.macro.tmLanguage.json` |
| `cpp/language-configuration.json` | `extensions/cpp/language-configuration.json` |

The YAML grammar is multi-file: `yaml.tmLanguage.json` (scope `source.yaml`)
pulls in the version-specific grammars (`source.yaml.1.0` ... `source.yaml.1.3`)
and `source.yaml.embedded` via cross-grammar includes, so all six files are
registered in `languages.json`.

The C++ grammar references `source.cpp.embedded.macro`, which is why
`cpp.embedded.macro.tmLanguage.json` is also registered. Some includes in the
C/C++ grammars (e.g. `source.asm`, `source.glsl`) are not shipped; TextMate
engines tolerate unresolved includes by simply not matching those patterns.

## Local modifications to upstream files

The four language-configuration files below contain JSONC (comments and/or
trailing commas) as shipped by VS Code, which sora-editor's Gson-based loader
does not fully tolerate. Comment lines and trailing commas were stripped with
a string-aware script — no semantic content was changed:

- `typescript/language-configuration.json`
- `json/language-configuration.json`
- `c/language-configuration.json`
- `cpp/language-configuration.json`

`kotlin/syntaxes/Kotlin.tmLanguage` is an XML plist (not JSON); this is
upstream's exact format and is supported by the TextMate engine.

## Registry file

`languages.json` in this directory is Arrow IDE's own registry (not upstream).
It lists every grammar above for `GrammarRegistry.loadGrammars(...)` and must
be kept in sync with `com.maragung.arrowide.editor.LanguageRegistry`.
