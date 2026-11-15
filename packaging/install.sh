#!/bin/sh
# Installs Hypatia for the current user (no root needed).
# Run "mvn package" (or Clean and Build in NetBeans) first.
set -e
cd "$(dirname "$0")/.."

JAR=target/hypatia.jar
[ -f "$JAR" ] || { echo "Build first: $JAR not found (mvn package)"; exit 1; }

DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
BIN="$HOME/.local/bin"

mkdir -p "$DATA/hypatia" "$BIN" "$DATA/applications"
cp "$JAR" "$DATA/hypatia/hypatia.jar"

# The manual (Help > View Manual): compile doc/manual.tex if there is no PDF yet.
if [ ! -f doc/manual.pdf ] && command -v latexmk >/dev/null; then
    (cd doc && latexmk -pdf -interaction=nonstopmode -silent manual.tex >/dev/null 2>&1) || true
fi
if [ -f doc/manual.pdf ]; then
    cp doc/manual.pdf "$DATA/hypatia/manual.pdf"
else
    echo "Note: doc/manual.pdf not found; Help > View Manual needs it (latexmk -pdf doc/manual.tex)."
fi
install -m 755 packaging/hypatia "$BIN/hypatia"
cp packaging/hypatia.desktop "$DATA/applications/hypatia.desktop"

for s in 16 24 32 48 64 128 256 512; do
    d="$DATA/icons/hicolor/${s}x${s}/apps"
    mkdir -p "$d"
    cp "src/main/resources/atlantis/hypatia/icons/hypatia-$s.png" "$d/hypatia.png"
done

command -v update-desktop-database >/dev/null && update-desktop-database -q "$DATA/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$DATA/icons/hicolor" 2>/dev/null || true
if command -v kbuildsycoca6 >/dev/null; then kbuildsycoca6 >/dev/null 2>&1 || true
elif command -v kbuildsycoca5 >/dev/null; then kbuildsycoca5 >/dev/null 2>&1 || true
fi

echo "Installed Hypatia:"
echo "  $DATA/hypatia/hypatia.jar"
echo "  $BIN/hypatia"
echo "  $DATA/applications/hypatia.desktop"
case ":$PATH:" in
    *":$BIN:"*) ;;
    *) echo "Note: $BIN is not on your PATH; add it so Okular can call 'hypatia'." ;;
esac
echo
echo "Okular inverse search: Settings > Configure Okular > Editor > Custom Text Editor:"
echo "  hypatia --line %l %f"
