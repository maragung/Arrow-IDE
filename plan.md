Kembangkan aplikasi Android sebagai **full-featured mobile code editor + local development environment + Git/GitHub workspace**, dengan pengalaman yang terinspirasi dari VS Code tetapi dioptimalkan untuk smartphone dan tablet.

Aplikasi bukan sekadar text editor, nama aplikasi ini "Arrow IDE"

Github Repo: https://github.com/maragung/Arrow-IDE

Target akhirnya:

**Code Editor + Project Explorer + Local Terminal + Package Manager + Git + GitHub + Build Environment + CI/CD Helper**

Semua fitur lokal harus dapat digunakan tanpa root.

---

# 1. Arsitektur Utama

Bagi aplikasi menjadi beberapa komponen:

```text
Android App
│
├── Code Editor
├── Project Explorer
├── Search
├── Terminal
├── Toolchain Manager
├── Package Manager
├── Git
├── GitHub
├── Build System
├── CI/CD
├── Settings
├── Secure Storage
└── Workspace Manager
```

Gunakan:

- Kotlin
- Jetpack Compose
- Android SDK
- C++/NDK untuk komponen yang membutuhkan performa tinggi
- Coroutines
- Android Keystore
- Storage Access Framework
- Gradle
- CMake

---

# 2. Local Terminal

Sediakan **Local Terminal** yang benar-benar berjalan di device Android.

Jangan membuat terminal palsu atau hanya menampilkan output simulasi.

Terminal harus menjalankan proses lokal menggunakan Android/Linux userspace yang tersedia.

Contoh:

```text
$ pwd
/storage/emulated/0/Projects/myapp

$ ls
src
package.json
README.md
```

Terminal harus memiliki:

- Multiple terminal sessions
- New terminal
- Close terminal
- Restart terminal
- Kill process
- Clear
- Search
- Copy
- Paste
- Select text
- Scrollback
- Resize
- Full-screen terminal
- Working directory
- Environment variables
- PATH management

---

# 3. Shell

Sediakan shell lokal.

Minimal:

```text
sh
```

Jika environment menyediakan shell tambahan:

```text
bash
```

gunakan secara otomatis.

Jangan membutuhkan root.

Terminal berjalan sebagai user aplikasi Android dengan sandbox Android.

Jangan memberikan akses root atau privilege escalation.

---

# 4. Built-in / Downloadable Toolchain

Jangan memasukkan semua toolchain berukuran besar ke APK utama.

Buat:

```text
Toolchain Manager
```

yang dapat menginstall/remove/update tools secara terpisah.

Minimal support:

```text
Node.js
npm
npx
Python 3
pip
Git
curl
wget
zip
unzip
tar
gzip
make
CMake
Clang
LLVM
```

Tambahkan architecture untuk:

```text
pnpm
yarn
bun
jq
openssl
ssh
rsync
```

jika binary kompatibel tersedia.

---

# 5. Tool Manager

Buat menu:

```text
Tools
```

Contoh:

```text
Node.js
  Installed
  Version 22.x
  [Update] [Remove]

Python
  Installed
  Version 3.x
  [Update] [Remove]

Git
  Installed
  Version 2.x
  [Update] [Remove]

CMake
  Not installed
  [Install]
```

Fitur:

- Install
- Remove
- Update
- Version selection jika memungkinkan
- Verify installation
- Repair
- Reinstall

---

# 6. Jangan Membebani APK

APK utama harus tetap relatif kecil.

Jangan bundle:

```text
Node.js
Python
LLVM
Clang
CMake
Git
```

ke dalam APK kecuali komponen yang benar-benar diperlukan.

Gunakan:

```text
APK
 ↓
Toolchain Manager
 ↓
Download tool
 ↓
Extract
 ↓
Verify checksum/signature
 ↓
Install
 ↓
Add to PATH
```

Toolchain disimpan di private app storage atau lokasi app-controlled yang sesuai.

---

# 7. Toolchain Security

Setiap tool yang di-download harus:

- HTTPS
- Verify checksum
- Verify archive integrity
- Hindari binary yang tidak dikenal
- Jangan menjalankan executable sebelum verifikasi selesai
- Atomic installation
- Rollback jika installation gagal

Jangan menjalankan file executable yang download-nya belum diverifikasi.

---

# 8. Node.js Environment

Jika Node.js terinstall:

```text
$ node --version
v22.x.x

$ npm --version
10.x.x

$ npx --version
10.x.x
```

Project dapat menjalankan:

```text
npm install
npm run dev
npm run build
npm test
npx ...
```

Environment harus menggunakan workspace sebagai current working directory.

---

# 9. Python Environment

Support:

```text
python3
python
pip
```

Contoh:

```text
$ python3 --version
Python 3.x

$ pip --version
```

Project dapat menjalankan:

```text
pip install
python3 main.py
python3 -m ...
```

Jika memungkinkan sediakan virtual environment:

```text
python3 -m venv .venv
```

dan environment activation yang sesuai dengan shell environment aplikasi.

---

# 10. Git

Sediakan Git CLI lokal.

Contoh:

```text
git status
git add .
git commit
git branch
git checkout
git switch
git diff
git log
git fetch
git pull
git push
git clone
```

Git harus bekerja langsung terhadap project workspace.

---

# 11. Git UI

Selain CLI, sediakan UI Git.

Explorer:

```text
M src/main.cpp
M package.json
? test.js
```

Menu:

```text
Source Control
```

Fitur:

- Changes
- Staged Changes
- Untracked
- Diff
- Stage
- Unstage
- Commit
- Pull
- Push
- Fetch
- Branch
- Checkout
- Merge
- Rebase jika tersedia
- Stash

---

# 12. GitHub Integration

Sediakan menu:

```text
GitHub
```

User dapat menghubungkan GitHub menggunakan **Personal Access Token (PAT)**.

Token jangan pernah:

- disimpan plaintext
- dimasukkan ke source code
- dimasukkan ke Git repository
- ditampilkan di UI setelah disimpan
- ditulis ke terminal history
- ditulis ke log

Simpan token menggunakan:

```text
Android Keystore
```

atau mekanisme encrypted storage yang menggunakan Android Keystore.

---

# 13. GitHub Authentication

UI:

```text
GitHub

Not Connected

[Connect GitHub]
```

Setelah terhubung:

```text
GitHub
Connected

Account: username

Repositories
Actions
Branches
Pull Requests
Issues
```

Gunakan GitHub API melalui HTTPS.

Token hanya digunakan sesuai permission/scope yang diberikan user.

Jangan meminta permission GitHub yang tidak diperlukan.

---

# 14. GitHub Repository

User dapat:

```text
Clone Repository
```

Contoh:

```text
GitHub Repository URL
https://github.com/user/project.git

Destination
Projects/project
```

Setelah clone:

```text
Repository
 ↓
Workspace
 ↓
Editor
```

---

# 15. GitHub Repository Browser

Sediakan:

```text
Repositories
```

User dapat:

- List repositories
- Open repository
- Clone repository
- Create repository jika token mengizinkan
- Delete repository hanya melalui explicit confirmation
- View branches
- View commits
- View pull requests
- View issues
- View releases

Jangan melakukan destructive action tanpa confirmation.

---

# 16. GitHub Actions

Buat menu:

```text
GitHub Actions
```

Tampilkan:

```text
Workflow
Status
Commit
Branch
Started
Duration
```

Contoh:

```text
Build Android
✓ Success

Deploy Web
● Running

Tests
✕ Failed
```

User dapat:

- Run workflow
- Cancel workflow
- View run
- View logs
- Re-run failed job
- Open workflow file
- Download artifacts jika API mengizinkan

---

# 17. Workflow Editor

Dukung file:

```text
.github/workflows/*.yml
.github/workflows/*.yaml
```

Explorer:

```text
.github/
└── workflows/
    ├── build.yml
    ├── test.yml
    └── deploy.yml
```

User dapat membuka dan mengedit langsung.

Syntax highlighting YAML.

---

# 18. CI/CD Helper

Buat menu:

```text
CI/CD
```

Tujuannya mempermudah user menjalankan workflow GitHub Actions.

Flow:

```text
Select Repository
       ↓
Select Workflow
       ↓
Select Branch
       ↓
Optional Inputs
       ↓
Run Workflow
```

Tampilkan status realtime/polling yang efisien.

---

# 19. GitHub Actions Logs

Tampilkan log dengan terminal-like viewer:

```text
Build started...

Run npm install
✓ completed

Run npm run build
✓ completed

Build completed successfully.
```

Support:

- Auto-scroll
- Pause auto-scroll
- Search log
- Copy log
- Clear view
- Expand/collapse sections

Jangan menyimpan token atau secret dari logs.

---

# 20. GitHub Secrets

Jangan pernah menampilkan GitHub repository secrets.

Jika GitHub Actions menggunakan:

```text
${{ secrets.API_KEY }}
```

editor hanya menampilkan reference tersebut.

Jangan mencoba mengambil atau menampilkan nilai secret.

---

# 21. Local Secrets Manager

Buat:

```text
Settings
 → Secrets
```

User dapat menyimpan:

```text
GITHUB_TOKEN
NPM_TOKEN
API_KEY
CUSTOM_TOKEN
```

Tetapi:

- encrypted at rest
- Android Keystore-backed
- masked UI
- tidak masuk log
- tidak masuk Git
- tidak masuk crash report
- tidak masuk analytics

---

# 22. Environment Variables

Sediakan project environment:

```text
Environment
```

Contoh:

```text
NODE_ENV=development
API_URL=...
```

Tampilkan nilai sensitive sebagai:

```text
••••••••
```

Jangan menyimpan secret di repository kecuali user secara eksplisit memilih plain environment file.

Berikan warning jika mendeteksi kemungkinan secret di:

```text
.env
config
source code
Git diff
```

---

# 23. Project Terminal

Ketika membuka:

```text
MyProject
```

terminal otomatis:

```text
cd <workspace>
```

Jadi:

```text
$ pwd
.../MyProject
```

Perintah:

```text
npm install
python3 script.py
git status
```

langsung menggunakan workspace tersebut.

---

# 24. Build System

Deteksi:

```text
package.json
CMakeLists.txt
Makefile
pyproject.toml
requirements.txt
Cargo.toml
go.mod
```

Contoh:

```text
Project Detected

Node.js
Python
CMake
Rust
Go
```

Sediakan:

```text
Run
Build
Test
Clean
```

jika toolchain tersedia.

---

# 25. Background Process Manager

Buat:

```text
Processes
```

untuk melihat proses yang dijalankan aplikasi.

Contoh:

```text
npm install
Running

python3 server.py
Running

git clone ...
Completed
```

User dapat:

- View output
- Stop
- Kill
- Restart

Jangan memungkinkan aplikasi membunuh process Android lain di luar sandbox-nya.

---

# 26. Long Running Processes

Support process seperti:

```text
npm run dev
python3 server.py
```

yang berjalan lama.

Terminal harus tetap dapat digunakan untuk membuka terminal session baru.

Contoh:

```text
Terminal 1
npm run dev

Terminal 2
git status

Terminal 3
curl localhost:3000
```

---

# 27. Localhost

Izinkan project development menggunakan:

```text
localhost
127.0.0.1
```

Contoh:

```text
npm run dev
```

Jika server berjalan:

```text
http://127.0.0.1:3000
```

tampilkan informasi port.

---

# 28. Port Manager

Buat:

```text
Ports
```

Tampilkan:

```text
3000 → Node.js
5173 → Vite
8000 → Python
```

Fitur:

- Detect listening ports milik app
- Open URL
- Copy URL
- Stop associated process

---

# 29. Package Manager

Untuk Node:

```text
npm
npx
pnpm
yarn
```

Jika tersedia.

Untuk Python:

```text
pip
venv
```

Sediakan package manager UI sederhana:

```text
Install Package
Remove Package
Update Package
```

Tetapi semua operasi tetap dijalankan secara lokal.

---

# 30. Archive Tools

Terminal/project manager harus mendukung:

```text
zip
unzip
tar
gzip
```

Sediakan juga:

```text
View Archive
```

yang dapat melihat isi:

```text
project.zip

📁 src/
📄 package.json
📄 README.md
📄 config.json
```

**Jangan mengunduh seluruh archive besar hanya untuk menampilkan daftar isinya.**

Jika archive berada pada remote/storage yang dapat diakses secara streaming atau random-access, gunakan pendekatan tersebut bila tersedia.

Jika archive lokal, baca metadata/central directory tanpa mengekstrak seluruh isi.

User dapat memilih file di dalam archive untuk preview jika format dan ukuran memungkinkan.

---

# 31. Codebase Awareness

Editor harus selalu mengetahui:

```text
Current Workspace
Current File
Git Repository
Project Root
Toolchain
Environment
```

Contoh:

```text
Workspace:
~/Projects/MyApp

Git:
main

Node:
22.x

Python:
3.x
```

Gunakan informasi tersebut untuk mempermudah command dan CI/CD.

---

# 32. GitHub + Codebase Workflow

Sediakan workflow cepat:

```text
Open GitHub Repository
        ↓
Clone
        ↓
Open Workspace
        ↓
Edit Code
        ↓
Run Local Tests
        ↓
Git Diff
        ↓
Commit
        ↓
Push
        ↓
Run GitHub Actions
        ↓
View CI Logs
```

Semua langkah harus dapat dilakukan dari aplikasi.

---

# 33. One-Tap Actions

Pada project:

```text
▶ Run
🔨 Build
🧪 Test
↻ Git Pull
↑ Git Push
⚙ Actions
```

Command yang dijalankan harus berasal dari project configuration/package scripts, bukan command palsu.

---

# 34. Security Boundary

Local terminal **tidak boleh memiliki root access**.

Gunakan sandbox Android.

Jangan bypass:

- Android permission model
- SELinux
- application sandbox
- security restrictions

Jangan menggunakan exploit untuk mendapatkan akses filesystem.

---

# 35. Storage Architecture

Pisahkan:

```text
App Data
Toolchains
Workspace Metadata
Cache
Recovery
Temporary Files
```

Contoh:

```text
app/
├── toolchains/
├── cache/
├── recovery/
├── logs/
└── settings/
```

Workspace user tetap mengikuti akses yang diberikan melalui SAF.

---

# 36. Memory & Security

Editor dan terminal harus meminimalkan plaintext copies.

Gunakan secure memory untuk:

- GitHub token
- API keys
- Password
- Sensitive configuration
- Encryption keys

Gunakan:

```text
Android Keystore
```

untuk key protection.

Jangan mencoba mengklaim bahwa seluruh RAM selalu terenkripsi.

Untuk editor:

```text
Minimal plaintext copies
+
Secure buffers where appropriate
+
Zeroization
+
Encrypted recovery cache
+
No plaintext logs
```

---

# 37. Command History Security

Terminal history harus memiliki:

```text
Normal
Secure
Disabled
```

Dalam Secure mode:

Jangan menyimpan command yang berisi:

```text
token
password
API key
secret
```

Jika command mengandung credential inline, berikan warning.

Lebih baik gunakan environment/secret injection.

---

# 38. Git Credential Security

Jangan menyimpan:

```text
https://token@github.com/...
```

sebagai plaintext remote URL.

Gunakan credential mechanism yang aman.

Jika GitHub token digunakan:

```text
Android Secure Storage
        ↓
Git operation
        ↓
Credential injected temporarily
```

Jangan memasukkan token ke repository.

---

# 39. Network Security

Semua komunikasi GitHub/tool downloads:

```text
HTTPS
```

Gunakan certificate validation standar Android.

Jangan menerima insecure HTTP untuk credential operations.

Tool download harus menggunakan trusted HTTPS source dan integrity verification.

---

# 40. Offline Mode

Editor tetap berfungsi tanpa internet:

- Open file
- Edit
- Save
- Search
- Terminal
- Local Git
- Local build
- Local Node
- Local Python

Internet hanya diperlukan untuk:

- GitHub
- Download toolchains
- Clone/push/pull
- Package installation
- Remote CI/CD

---

# 41. UI

Tambahkan navigation:

```text
Home
Explorer
Search
Source Control
Run
Terminal
GitHub
Tools
Settings
```

Pada smartphone gunakan drawer/bottom navigation yang adaptif.

Pada tablet gunakan sidebar permanen.

---

# 42. Terminal UI

Gunakan terminal dengan font monospace.

Support:

- ANSI colors
- ANSI cursor
- ANSI escape sequences
- Unicode
- Resize events
- Interactive CLI
- stdin
- stdout
- stderr

Pastikan command interaktif tidak rusak.

---

# 43. Terminal Emulator

Gunakan terminal emulator library yang matang bila tersedia daripada membuat ANSI terminal parser dari nol.

Terminal harus dapat menjalankan program CLI modern yang kompatibel dengan environment Android.

---

# 44. Lifecycle

Sangat penting.

Jika user:

- lock screen
- switch app
- rotate
- background app
- Android reclaim memory

jangan kehilangan:

- open tabs
- unsaved changes
- terminal metadata
- project state

Untuk process yang berjalan lama, gunakan architecture yang sesuai dengan Android lifecycle dan jangan mengandalkan background execution tanpa batas.

---

# 45. Resource Limits

Karena Android memiliki resource terbatas:

- Batasi concurrent process sesuai resource device.
- Jangan menjalankan build berat tanpa kontrol.
- Tampilkan RAM usage jika relevan.
- Berikan warning ketika device kekurangan RAM.
- Jangan membuat unlimited terminal/process.

---

# 46. Toolchain Updates

Tool Manager harus dapat:

```text
Installed
Update Available
Update
Rollback
Remove
Repair
```

Jika update gagal:

```text
Restore previous version
```

Jangan merusak environment yang sudah bekerja.

---

# 47. Project Templates

Sediakan template:

```text
Node.js
Vite
React
Python
C++
CMake
Static Website
```

Template hanya dibuat jika toolchain yang diperlukan tersedia.

---

# 48. GitHub Actions Templates

Sediakan generator sederhana:

```text
New GitHub Workflow
```

Pilihan:

```text
Node.js CI
Python CI
C++ CI
Android CI
Build & Release
Deploy
```

Generator menghasilkan `.github/workflows/*.yml`.

User tetap dapat mengedit YAML secara manual.

---

# 49. No Fake Features

Jangan membuat:

```text
Fake terminal
Fake Git
Fake GitHub Actions
Fake build output
Fake process list
Fake Node.js
Fake Python
```

Semua fitur harus benar-benar bekerja.

Jika tool belum terinstall:

```text
Node.js is not installed.

[Install Node.js]
```

bukan menampilkan hasil simulasi.

---

# 50. Final Target

Target aplikasi:

**"A complete Android-native mobile development workspace."**

Pengguna dapat melakukan:

```text
Open project
    ↓
Browse files
    ↓
Edit code
    ↓
Open terminal
    ↓
npm install
    ↓
npm run build
    ↓
python3 script.py
    ↓
git diff
    ↓
git commit
    ↓
git push
    ↓
GitHub Actions
    ↓
View CI/CD logs
```

semuanya dari satu aplikasi.

Prioritas utama:

1. Stability
2. Security
3. Editor performance
4. Terminal reliability
5. Toolchain management
6. Git
7. GitHub
8. CI/CD
9. Storage management
10. Battery/resource efficiency
11. Professional UX
12. Extensibility

Lakukan security review dan stress testing sebelum release, terutama terhadap:

- command injection
- path traversal
- malicious archives
- malicious repositories
- executable downloads
- Git credentials
- GitHub tokens
- secrets
- temporary files
- memory leaks
- OutOfMemoryError
- ANR
- process lifecycle
- Android sandbox
- filesystem permissions
- corrupted files
- interrupted installations
- interrupted Git operations
- interrupted builds
- crash recovery


# 51. AI Coding Agent

Tambahkan **AI Coding Agent terintegrasi penuh** ke dalam aplikasi.

AI Agent bukan sekadar chatbot.

AI harus mampu memahami dan bekerja dengan **codebase/workspace yang sedang dibuka**, dengan permission dan batasan yang jelas.

Target pengalaman:

```text
User
 ↓
AI Coding Agent
 ↓
Understand Workspace
 ↓
Inspect Files
 ↓
Search Code
 ↓
Plan
 ↓
Edit Files
 ↓
Run Commands
 ↓
Run Tests
 ↓
Review Changes
 ↓
Show Diff
 ↓
User Approval
```

AI Agent harus dapat membantu:

- Membaca codebase
- Mencari file
- Mencari symbol/function/class
- Membaca file yang relevan
- Membuat file
- Mengubah file
- Rename/refactor
- Delete file dengan confirmation
- Menjalankan command
- Menjalankan test
- Menjalankan build
- Menganalisis error
- Memperbaiki error
- Menjelaskan code
- Membuat dokumentasi
- Membuat commit message
- Membantu Git
- Membantu GitHub Actions
- Menganalisis CI/CD failure

---

# 52. Multi Provider

Jangan hard-code provider AI.

Buat abstraction:

```text id="0x3wq6"
AI Provider
├── OpenAI
├── Anthropic
├── Google
├── OpenAI-Compatible
├── Ollama
├── Custom HTTP API
└── Future Providers
```

Provider harus dapat ditambahkan tanpa mengubah core Agent.

---

# 53. Multi Model

Setiap provider dapat memiliki banyak model.

Contoh:

```text id="8x9c2e"
OpenAI
├── Model A
├── Model B
└── Model C

Anthropic
├── Model A
└── Model B

Custom Provider
├── Model X
└── Model Y

Ollama
├── qwen
├── llama
└── other local models
```

Jangan hard-code daftar model.

User dapat menambahkan model secara manual.

---

# 54. Custom Provider

Buat:

```text id="m6e3tp"
Settings
 → AI Providers
 → Add Provider
```

Form:

```text id="m5xk2h"
Provider Name
Base URL
API Key
API Format
Model
Organization / Project optional
Headers optional
```

Support API style:

```text id="8h7q2r"
OpenAI-compatible
Anthropic-compatible
Google-compatible
Custom
```

Contoh OpenAI-compatible:

```text id="6r5h9a"
Base URL:
https://example.com/v1

API Key:
••••••••••••

Model:
my-model
```

Jangan mengasumsikan semua provider menggunakan URL/API format yang sama.

---

# 55. Model Profiles

Setiap model dapat memiliki konfigurasi sendiri:

```text id="v4p7sm"
Model Profile

Provider
Model ID
Display Name
Context Window
Max Output
Temperature
Reasoning
Tool Calling
Vision
Streaming
```

Jika provider mendukungnya, support:

- reasoning
- thinking
- tool calling
- structured output
- streaming
- vision
- code generation

Jangan mengirim parameter yang tidak didukung provider.

---

# 56. Model Selection

Di AI chat header:

```text id="3q9s6u"
AI Agent
[Provider / Model ▼]
```

Contoh:

```text id="j8w2kc"
OpenAI / Model A
Anthropic / Model B
Ollama / qwen
Custom / Model X
```

User dapat mengganti model tanpa keluar dari workspace.

---

# 57. Model Routing

Sediakan optional:

```text id="8o4xmc"
Auto Model
```

User dapat menentukan:

```text id="8ih7zd"
Simple Tasks → Fast Model
Coding → Coding Model
Large Refactor → Large Context Model
Complex Debugging → Reasoning Model
```

Tetapi user harus tetap dapat memilih model secara manual.

---

# 58. AI Agent Modes

Sediakan mode:

### Ask

AI hanya membaca dan menjelaskan.

```text
Ask
```

Tidak boleh mengubah file.

### Plan

AI menganalisis workspace dan membuat rencana.

```text
Plan
```

Tidak melakukan perubahan sebelum approval.

### Edit

AI dapat mengubah file.

```text
Edit
```

Perubahan ditampilkan sebagai diff.

### Agent

AI dapat:

- Inspect
- Search
- Edit
- Run commands
- Test
- Iterate

Tetapi operasi berisiko membutuhkan approval.

---

# 59. Agent Permission System

Ini sangat penting.

Buat permission:

```text id="r0d5qp"
AI Permissions

Read Files       ✓
Search Files     ✓
Edit Files       ✓
Create Files     ✓
Delete Files     ?
Run Commands     ?
Git Operations   ?
Network          ?
GitHub           ?
```

User dapat mengatur permission.

---

# 60. Command Approval

Untuk command berbahaya atau berpotensi merusak:

```text id="q9v4by"
AI wants to run:

rm -rf build/

[Allow once]
[Allow for workspace]
[Deny]
```

Jangan menjalankan command destructive secara otomatis.

Untuk command normal:

```text
npm test
npm run build
git status
```

user dapat memilih auto-approve jika diinginkan.

---

# 61. Dangerous Command Detection

Buat command risk classifier.

Kategori:

```text id="z8a5qr"
Safe
Low Risk
Medium Risk
High Risk
Destructive
```

Contoh high-risk:

```text
rm -rf
chmod -R
format
disk operations
credential operations
```

Command yang memengaruhi filesystem di luar workspace harus membutuhkan approval.

---

# 62. Workspace Sandbox

Default AI Agent hanya boleh mengakses:

```text id="0v6p7a"
Current Workspace
```

Jangan memberikan akses otomatis ke:

```text
/home
/system
/data
Android private directories
other app data
```

Jika AI membutuhkan file di luar workspace:

```text id="g4q6d2"
AI requests access to:

<path>

[Allow once]
[Allow]
[Deny]
```

Gunakan Android permission/security model.

---

# 63. Codebase Context

AI harus memiliki tools:

```text id="9w5b1k"
list_files
read_file
search_files
find_symbol
get_file_info
get_git_status
get_diff
```

AI tidak perlu mengirim seluruh project ke model.

Gunakan **targeted context retrieval**.

Contoh:

User:

```text
Fix the authentication bug.
```

Agent:

```text
Search authentication
        ↓
Find relevant files
        ↓
Read relevant code
        ↓
Understand dependencies
        ↓
Plan fix
```

---

# 64. Context Management

Implementasikan context engine:

```text id="4c7p2m"
Workspace
 ↓
Indexer
 ↓
Relevant Files
 ↓
Symbol / Search Results
 ↓
Context Builder
 ↓
AI Model
```

Jangan mengirim seluruh codebase jika tidak diperlukan.

Prioritaskan:

1. Current file
2. Open files
3. User-selected files
4. Relevant search results
5. Imports/dependencies
6. Git diff
7. Related files

---

# 65. Ignore Files

Support:

```text id="1t3v8q"
.gitignore
.aiignore
```

`.aiignore` digunakan untuk mencegah AI membaca file tertentu.

Contoh:

```text
.env
*.pem
*.key
secrets/
credentials/
private/
```

AI Agent harus menghormati `.aiignore`.

---

# 66. Secret Protection

AI Agent **tidak boleh otomatis membaca secret**.

Detect:

```text id="w7v3pa"
API_KEY
TOKEN
PASSWORD
PRIVATE_KEY
SECRET
```

Jika file berisi credential:

```text
Secret detected.

This file is excluded from AI context.
```

User dapat melakukan explicit override jika diperlukan.

---

# 67. AI Secrets

Provider API keys harus disimpan:

```text id="d5y4rz"
Android Keystore
```

Jangan:

- hard-code
- simpan plaintext
- commit ke Git
- masukkan ke AI prompt
- tampilkan di log
- tampilkan dalam crash report

UI:

```text
API Key
••••••••••••••
```

---

# 68. Streaming Response

AI response harus streaming jika API mendukung.

Contoh:

```text id="n1g6v0"
AI Agent

Analyzing workspace...

I found the authentication flow...

Creating a plan...

Applying changes...
```

Jangan menunggu seluruh response jika provider mendukung streaming.

---

# 69. Agent Activity

Buat activity panel:

```text id="u2x6re"
Agent Activity

✓ Listed src/
✓ Searched authentication
✓ Read auth.ts
✓ Read api.ts
→ Editing auth.ts
→ Running tests
```

User dapat melihat apa yang dilakukan agent.

---

# 70. Diff Before Apply

Jangan langsung menyembunyikan perubahan AI.

Tampilkan:

```text id="8y5x1p"
AI Changes

auth.ts
+ added validation
- removed obsolete code

api.ts
+ added error handling
```

Actions:

```text
[Apply]
[Reject]
[Review]
```

Untuk Agent mode, perubahan kecil dapat auto-apply jika user mengizinkan.

---

# 71. File Edit Operations

AI tool API:

```text id="3z6p8a"
create_file
edit_file
replace_text
insert_text
delete_text
rename_file
move_file
```

Setiap operation harus:

- Validate path
- Ensure workspace boundary
- Preserve encoding
- Preserve line endings
- Create backup/recovery state
- Produce diff

---

# 72. Atomic Editing

Jangan membuat file corrupt jika AI gagal di tengah proses.

Gunakan:

```text id="0p9z4e"
Read
 ↓
Generate Patch
 ↓
Validate Patch
 ↓
Preview Diff
 ↓
Apply Atomically
 ↓
Save
```

Jika gagal:

```text
Rollback
```

---

# 73. AI Terminal Tool

AI dapat menggunakan terminal melalui controlled tool:

```text id="h8p2vz"
run_command
```

Contoh:

```text
npm test
npm run build
python3 test.py
git status
```

Return:

```text
stdout
stderr
exit code
duration
```

AI dapat menggunakan hasilnya untuk debugging.

---

# 74. Terminal Security

AI command execution harus berjalan menggunakan user-level Android sandbox.

Tidak ada:

```text
root
privilege escalation
system modification
```

Default working directory:

```text
Current Workspace
```

---

# 75. Agent Loop

Implementasikan agent loop:

```text id="v3o5nq"
User Request
     ↓
Plan
     ↓
Tool Call
     ↓
Tool Result
     ↓
Reason
     ↓
Next Tool Call
     ↓
Validate
     ↓
Test
     ↓
Final Response
```

Batasi:

- Maximum iterations
- Maximum tool calls
- Maximum execution time
- Maximum output
- Maximum modified files

Agar agent tidak berjalan tanpa batas.

---

# 76. Agent Stop

User selalu dapat:

```text id="v2c8yx"
Stop Agent
```

Jika agent sedang:

- membaca
- searching
- editing
- running command
- testing

harus dapat dihentikan dengan aman.

---

# 77. Cost / Token Usage

Jika provider memberikan usage information, tampilkan:

```text id="h1k4sz"
Input Tokens
Output Tokens
Total Tokens
Estimated Cost
```

Sediakan optional:

```text
Budget Limit
```

Agent harus berhenti jika budget limit tercapai.

---

# 78. Context Window

Model profile harus memiliki:

```text
Context Window
```

Context builder harus menyesuaikan jumlah file/code yang dikirim dengan context window model.

Jangan mengirim prompt yang melebihi batas provider.

---

# 79. Conversation History

Setiap workspace dapat memiliki AI sessions:

```text id="2s6n0f"
AI Sessions

Fix authentication
Implement dark mode
Refactor API client
Fix CI failure
```

Session dapat:

- Rename
- Delete
- Continue
- Export

Jangan menyimpan source code sensitif secara plaintext dalam history jika tidak diperlukan.

---

# 80. AI Context Privacy

Berikan indikator:

```text id="c4v7xe"
Context sent to:
Provider / Model

Files:
src/auth.ts
src/api.ts
package.json
```

User dapat melihat file apa saja yang dikirim.

Tambahkan:

```text
Don't send this file
```

---

# 81. Local AI

Support local AI provider:

```text id="g7m3v8"
Ollama
```

atau OpenAI-compatible local server.

Config:

```text
Provider:
Local

Base URL:
http://...

Model:
...
```

Jika local endpoint tersedia di jaringan lokal, user dapat memasukkannya secara manual.

Jangan menganggap localhost pada Android selalu menunjuk ke PC/VPS user.

---

# 82. Remote AI Endpoint

Support custom endpoint:

```text
https://example.com/v1
```

User dapat memasukkan endpoint sendiri.

Semua network request harus:

- HTTPS bila remote
- TLS validation
- Timeout
- Retry policy
- Cancellation
- Error handling

---

# 83. Provider Health Check

Pada settings:

```text
Test Connection
```

Output:

```text
✓ Provider reachable
✓ Authentication valid
✓ Model available
✓ Tool calling supported
```

Jika gagal:

```text
✕ Authentication failed
```

Jangan menampilkan API key.

---

# 84. Provider Capabilities

Setiap provider/model harus mendeklarasikan capability:

```text
Streaming
Tool Calling
Vision
Reasoning
JSON
Context Window
```

Agent menyesuaikan behavior berdasarkan capability tersebut.

Jika model tidak mendukung tool calling, gunakan fallback yang aman atau mode Ask/Plan.

---

# 85. AI + Git

AI dapat:

```text
git status
git diff
```

dan membantu:

- Explain changes
- Generate commit message
- Review diff
- Find bugs
- Suggest improvements

Jangan otomatis commit/push tanpa permission.

---

# 86. AI + GitHub

AI dapat membantu:

```text
Analyze CI failure
Read workflow
Explain error
Suggest fix
Edit workflow
```

Flow:

```text
GitHub Actions Failed
        ↓
AI Analyze Logs
        ↓
Find Relevant Files
        ↓
Suggest Fix
        ↓
Apply Patch
        ↓
Run Local Test
        ↓
User Review
        ↓
Commit/Push
        ↓
Re-run Actions
```

Push/re-run workflow harus membutuhkan permission sesuai setting user.

---

# 87. AI Coding Commands

Tambahkan command:

```text
Explain Selection
Fix Selection
Refactor Selection
Generate Tests
Generate Documentation
Optimize Code
Find Bugs
Review File
Review Changes
Explain Error
```

User dapat memilih text lalu menjalankan command tersebut.

---

# 88. Inline AI

Tambahkan:

```text
Ctrl+K
```

atau tombol mobile:

```text
✨ AI
```

Flow:

```text
Select Code
 ↓
AI
 ↓
Explain
Fix
Refactor
Optimize
```

Hasil ditampilkan sebagai preview.

---

# 89. AI Chat

Panel:

```text id="t9q1wb"
┌────────────────────────────┐
│ AI Agent    Model ▼   ⚙    │
├────────────────────────────┤
│ User:                      │
│ Fix the login issue        │
│                            │
│ AI:                        │
│ I'll inspect the auth flow │
│                            │
│ ✓ searched 8 files         │
│ ✓ found auth.ts            │
│ → preparing fix            │
├────────────────────────────┤
│ Ask AI...              ➤   │
└────────────────────────────┘
```

Pada smartphone gunakan bottom sheet/full-screen AI panel.

Pada tablet gunakan split panel.

---

# 90. AI Model Presets

Sediakan:

```text
Fast
Balanced
Reasoning
Coding
Large Context
Local
```

User dapat memetakan preset ke provider/model sendiri.

Contoh:

```text
Coding → Provider A / Model X
Fast → Provider B / Model Y
Local → Ollama / qwen
```

---

# 91. Custom Headers

Untuk OpenAI-compatible/custom providers, support optional headers:

```text
Header
Value
```

Tetapi sensitive values harus disimpan menggunakan secure storage.

Jangan menampilkan header secret plaintext.

---

# 92. Request Logging

Sediakan debugging network log **tanpa plaintext sensitive content**.

Tampilkan:

```text
Provider
Model
Status
Latency
Tokens
Error
```

Jangan mencatat:

- API keys
- tokens
- passwords
- source code
- full prompts jika berisi sensitive data

---

# 93. AI Error Handling

Jika provider gagal:

```text
Timeout
Rate Limited
Authentication Error
Invalid Model
Context Too Large
Network Error
Tool Call Error
```

berikan pesan yang jelas dan opsi:

```text
Retry
Switch Model
Switch Provider
Edit Configuration
Cancel
```

---

# 94. Automatic Model Fallback

Optional:

```text
Fallback Model
```

Contoh:

```text
Primary:
Provider A / Model X

Fallback:
Provider B / Model Y
```

Fallback hanya digunakan jika error tertentu terjadi, bukan diam-diam mengganti model tanpa informasi.

Tampilkan:

```text
Primary model unavailable.
Using fallback model.
```

---

# 95. AI Configuration Import/Export

User dapat export configuration.

Tetapi:

**Jangan memasukkan API keys dalam export secara default.**

Export:

```text
provider name
model
base URL
settings
```

Secret:

```text
excluded
```

Berikan explicit secure export jika benar-benar diperlukan.

---

# 96. Default Security Policy

Default:

```text
Read workspace        ALLOW
Search workspace      ALLOW
Edit files            ASK
Create files          ASK
Delete files          ASK
Run commands          ASK
Git commit            ASK
Git push              ASK
GitHub Actions        ASK
Network access        ASK/Provider
Access outside        DENY
Secrets               DENY
```

User dapat mengubah policy.

---

# 97. AI Activity Audit

Sediakan optional:

```text
AI Activity Log
```

Catat metadata:

```text
Time
Model
Tool
File path
Command
Result
```

Jangan mencatat secret atau plaintext sensitive content.

---

# 98. Final AI Architecture

Gunakan architecture:

```text id="1k3g5s"
AI UI
   ↓
Agent Controller
   ↓
Model Router
   ↓
Provider Adapter
   ↓
LLM API

Agent Controller
   ↓
Tool Registry
   ├── Filesystem
   ├── Search
   ├── Editor
   ├── Terminal
   ├── Git
   ├── GitHub
   └── Build/Test

Security Layer
   ↓
Permission Manager
   ↓
Workspace Sandbox
   ↓
Secret Manager
```

Setiap provider harus diisolasi dari core Agent.

---

# 99. Important Rule

Jangan membuat AI Coding Agent sebagai chatbot yang hanya menghasilkan code block.

Agent harus dapat benar-benar:

```text
Understand
Search
Inspect
Plan
Edit
Test
Debug
Review
```

codebase.

Namun setiap filesystem, terminal, Git, GitHub, dan network action harus melewati **Tool Permission/Security Layer**.

---

# 100. Final Product Vision

Target akhir aplikasi adalah:

**Android Mobile Development Workspace + AI Coding Agent**

Workflow lengkap:

```text
Open GitHub Repository
        ↓
Clone Project
        ↓
Open Workspace
        ↓
Edit Code
        ↓
Open Local Terminal
        ↓
npm / Python / Git / CMake
        ↓
AI Coding Agent
        ↓
Analyze Codebase
        ↓
Plan
        ↓
Edit Files
        ↓
Run Tests
        ↓
Fix Errors
        ↓
Review Diff
        ↓
Git Commit
        ↓
Git Push
        ↓
GitHub Actions
        ↓
AI Analyze CI Result
        ↓
Fix if necessary
        ↓
Re-run CI
```

Semua workflow harus terintegrasi dalam satu aplikasi.

Prioritas:

1. Security
2. Stability
3. Code editor
4. Local terminal
5. Toolchain
6. Project management
7. AI Agent
8. Git
9. GitHub
10. CI/CD
11. Performance
12. Customization

Jangan mengorbankan security demi automation.

AI harus selalu mengetahui batas workspace dan permission yang diberikan user.


### 101. AI Coding Agent Engine — OpenCode Integration

Untuk AI Coding Agent, **jangan membangun agent engine dari nol pada versi awal aplikasi**. Gunakan **OpenCode sebagai AI Coding Agent engine/runtime utama**, kemudian integrasikan OpenCode ke dalam aplikasi Android melalui integration/adapter layer milik aplikasi sendiri.

Tujuan arsitektur adalah agar aplikasi memiliki pengalaman seperti:

**Android IDE + Code Editor + Terminal + Git + GitHub + OpenCode + GitHub Actions**

dalam satu aplikasi.

#### 101.1 Architecture

Gunakan arsitektur:

```text
Android IDE
    │
    ├── Code Editor
    ├── Project Explorer
    ├── Terminal
    ├── Git
    ├── GitHub
    ├── Build & Release
    └── AI Coding Agent UI
             │
             ▼
      Agent Controller
             │
      Permission/Security Layer
             │
             ▼
       Agent Adapter
             │
             ▼
       OpenCode Engine
             │
       ┌─────┼──────────┐
       ▼     ▼          ▼
    OpenAI Anthropic   Ollama
       │
       └── Other compatible providers
```

#### 101.2 Do Not Rebuild OpenCode

Jangan menduplikasi seluruh kemampuan OpenCode di dalam aplikasi.

Gunakan OpenCode untuk menangani kemampuan agentic AI seperti:

* memahami codebase
* context management
* agent loop
* tool calling
* file inspection
* code search
* code editing
* patch/diff generation
* command execution
* iterative debugging
* build/test workflow
* model interaction
* streaming
* agent sessions
* reasoning/task execution

Aplikasi Android bertanggung jawab atas UI, workspace, security, permission, project management, terminal integration, Git/GitHub integration, dan cloud build/release.

#### 101.3 Custom Adapter Layer

Buat abstraction layer sendiri sehingga aplikasi tidak bergantung langsung pada implementasi internal OpenCode.

Contoh:

```text
AI Coding UI
     ↓
Agent Controller
     ↓
AgentAdapter
     ↓
OpenCodeAdapter
     ↓
OpenCode
```

Gunakan interface yang memungkinkan engine diganti di masa depan.

Contoh:

```text
AgentAdapter
├── OpenCodeAdapter
├── BuiltInAgentAdapter       (future)
└── CustomAgentAdapter        (future)
```

Versi pertama harus menggunakan:

**OpenCodeAdapter → OpenCode**

Jangan membuat `BuiltInAgentAdapter` hanya untuk menggantikan OpenCode pada V1.

#### 101.4 Real Workspace Integration

OpenCode harus bekerja terhadap project workspace yang sedang dibuka pengguna.

Agent harus dapat, sesuai permission:

* melihat struktur folder
* membaca file
* mencari file
* mencari symbol
* memahami dependency
* membaca konfigurasi project
* membaca Git status
* membaca Git diff
* mengubah file
* membuat file
* menghapus file dengan approval
* menjalankan command
* menjalankan build
* menjalankan test
* membaca error
* memperbaiki kode
* melakukan iterasi sampai task selesai

Jangan membuat simulasi/fake response.

Jika AI mengatakan:

> "I fixed the error."

aplikasi harus memastikan perubahan benar-benar terjadi pada workspace dan dapat ditampilkan melalui diff.

#### 101.5 Permission Boundary

Semua operasi agent harus melewati security/permission layer aplikasi.

Minimal permission:

```text
READ_FILES
SEARCH_FILES
EDIT_FILES
CREATE_FILES
DELETE_FILES
RUN_COMMANDS
GIT_OPERATIONS
GITHUB_OPERATIONS
NETWORK_ACCESS
```

Default:

```text
READ_FILES       = allowed
SEARCH_FILES     = allowed
EDIT_FILES       = approval
CREATE_FILES     = approval
DELETE_FILES     = approval
RUN_COMMANDS     = approval
GIT_OPERATIONS   = approval
GITHUB_OPERATIONS = approval
NETWORK_ACCESS   = restricted
```

Pengguna dapat mengubah permission sesuai workspace.

#### 101.6 Secret Protection

AI agent tidak boleh otomatis membaca atau mengirim:

```text
.env
.env.*
*.pem
*.key
credentials/*
secrets/*
private/*
tokens/*
```

Jangan mengirim API key provider kepada model.

API key/provider credentials harus disimpan menggunakan secure storage Android, seperti Android Keystore-backed storage.

Jangan menampilkan secret pada:

* AI conversation
* agent log
* terminal log
* crash report
* debug log
* Git diff
* exported configuration

#### 101.7 Model & Provider Management

Sediakan multi-provider dan multi-model configuration.

Contoh:

```text
AI Coding
├── Fast
│   └── Provider / Model
├── Coding
│   └── Provider / Model
├── Reasoning
│   └── Provider / Model
├── Large Context
│   └── Provider / Model
└── Local
    └── Ollama / Model
```

Provider yang kompatibel dapat mencakup:

* OpenAI
* Anthropic
* Google/Gemini
* Ollama
* OpenAI-compatible APIs
* Custom endpoints

Jangan mengunci aplikasi ke satu provider.

#### 101.8 AI Agent Modes

Sediakan mode:

**Ask**

Read-only assistance.

**Plan**

Agent menganalisis project dan membuat rencana tanpa langsung mengubah file.

**Edit**

Agent dapat membuat perubahan dan menampilkan diff sebelum diterapkan.

**Agent**

Agent dapat:

```text
Inspect
→ Plan
→ Edit
→ Run
→ Test
→ Analyze
→ Fix
→ Retest
```

dengan permission dan approval yang sesuai.

#### 101.9 Diff & Rollback

Setiap perubahan yang dibuat AI harus dapat ditinjau.

Sediakan:

```text
AI Changes
├── File changed
├── Lines added
├── Lines removed
├── Preview diff
├── Accept
├── Reject
└── Revert
```

Gunakan atomic patch/application mechanism jika memungkinkan.

Jika proses agent gagal, perubahan yang belum disetujui pengguna tidak boleh meninggalkan workspace dalam keadaan tidak konsisten.

#### 101.10 Agent Activity

Sediakan activity panel:

```text
AI Coding Agent

✓ Analyzed project
✓ Found AndroidManifest.xml
✓ Inspected MainActivity.kt
✓ Found build configuration
● Editing MainActivity.kt
○ Running Gradle test
○ Analyzing result
```

Pengguna dapat melihat apa yang sedang dilakukan agent tanpa harus melihat raw internal reasoning.

Jangan menampilkan hidden chain-of-thought. Tampilkan hanya status, tindakan/tool yang digunakan, hasil, error, dan ringkasan yang aman.

#### 101.11 Stop / Cancel

Agent harus dapat dihentikan pengguna kapan saja.

Sediakan:

```text
[ Stop Agent ]
```

Ketika dihentikan:

* hentikan task aktif jika memungkinkan
* hentikan process yang dijalankan agent
* jangan lanjut menjalankan command berikutnya
* pertahankan perubahan yang sudah terjadi secara aman
* tampilkan status task

#### 101.12 Resource Limits

Sediakan batas:

```text
Maximum iterations
Maximum tool calls
Maximum execution time
Maximum terminal output
Maximum modified files
Maximum context size
```

Tujuannya mencegah agent masuk infinite loop atau menjalankan task secara tidak terkendali.

#### 101.13 Local AI

Dukung local AI melalui:

```text
Ollama
OpenAI-compatible local endpoint
```

Agent architecture tidak boleh mengasumsikan semua model berada di cloud.

Pengguna dapat memilih:

```text
Local AI
Remote AI
```

dan melihat provider/model yang sedang digunakan.

#### 101.14 OpenCode Process Management

Jika OpenCode dijalankan sebagai local process/service, aplikasi harus menyediakan lifecycle management:

```text
Start
Stop
Restart
Health Check
Version
Logs
```

Jangan meninggalkan background process yang tidak diperlukan.

Sesuaikan implementasi dengan kemampuan Android sandbox dan jangan membutuhkan root.

#### 101.15 Future-Proof Design

Walaupun V1 menggunakan OpenCode, seluruh UI dan application architecture tidak boleh bergantung pada nama atau internal API OpenCode secara langsung.

Gunakan model internal aplikasi:

```text
AgentSession
AgentMessage
AgentTask
AgentTool
AgentPermission
AgentAction
AgentResult
AgentDiff
AgentError
```

Kemudian mapping object tersebut ke OpenCode melalui `OpenCodeAdapter`.

Dengan demikian aplikasi dapat mengganti atau menambahkan agent engine di masa depan tanpa mendesain ulang seluruh UI.

#### 101.16 Final Requirement

Implementasi harus mengikuti prinsip:

**OpenCode = AI Coding Agent Engine**

**Aplikasi Android = IDE + Workspace + UI + Security + Permissions + Terminal + Git + GitHub + Build/Release orchestration**

Jangan membangun ulang OpenCode dari nol kecuali terdapat kebutuhan teknis yang benar-benar tidak dapat dipenuhi oleh integration layer.

Semua fitur harus benar-benar berfungsi pada perangkat Android dan bukan sekadar mock/demo UI.

Use OpenCode under its applicable MIT License and comply with all applicable copyright/license/third-party notices. Do not imply affiliation, endorsement, or official status. Keep OpenCode's required license notices with the distributed product. Perform dependency/license auditing before every production release.

