#!/usr/bin/env bash
# Rode na raiz do projeto, depois de "npx cap add android".
set -e
DEST=$(find android/app/src/main/java -name MainActivity.java -exec dirname {} \;)
cp native/CosiNativePlugin.java native/CosiOverlayService.java "$DEST/"
python3 native/patch.py
echo "Codigo nativo copiado para $DEST"
