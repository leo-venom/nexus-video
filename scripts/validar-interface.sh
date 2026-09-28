#!/usr/bin/env bash
#
# Valida a interface DESTE projeto (assets/index.html).
#
# Existe porque o Gradle NÃO valida JavaScript: um erro de sintaxe passa
# pelo build e só aparece quando o app abre em branco.
#
set -euo pipefail
ARQ="$(dirname "$0")/../app/src/main/assets/index.html"

python3 - "$ARQ" <<'PY'
import io, pathlib, subprocess, sys

s = io.open(pathlib.Path(sys.argv[1]), encoding="utf-8").read()
i = s.rindex("<script>") + 8
j = s.index("</script>", i)
io.open("/tmp/_n2_check.js", "w", encoding="utf-8").write(s[i:j])

r = subprocess.run(["node", "--check", "/tmp/_n2_check.js"], capture_output=True, text=True)
if r.returncode != 0:
    print("  FALHOU — erro de sintaxe no JS:")
    print(r.stderr[:600])
    sys.exit(1)

css = s[s.index("<style>") + 7:s.index("</style>")]
if css.count("{") != css.count("}"):
    print(f"  FALHOU — CSS desbalanceado: {css.count('{')}/{css.count('}')}")
    sys.exit(1)

print(f"  JS valido | CSS {css.count('{')}/{css.count('}')} | {round(len(s)/1024)} KB")
PY
