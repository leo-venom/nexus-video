#!/usr/bin/env bash
#
# VERIFICAÇÃO OBRIGATÓRIA ANTES DE ENTREGAR O APK.
#
# Existe por causa de um crash real: ao clonar o projeto com outro
# applicationId, o AndroidManifest ficou com o nome COMPLETO da Activity
# apontando para o pacote ANTIGO. O build passou, a instalação passou e o
# app fechava na hora de abrir — porque o Android não achava a tela inicial.
#
# Uso:  ./scripts/verificar-apk.sh [caminho-do.apk]
#
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
AAPT="$ANDROID_HOME/build-tools/34.0.0/aapt"
BADGING="$("$AAPT" dump badging "$APK" 2>/dev/null || true)"

# ⚠️ o padrão tem de ser ancorado: a linha do package traz VÁRIOS name='...'
# (name, versionName, platformBuildVersionName) e um ".*name=" guloso acabaria
# pegando o último — foi exatamente esse tipo de detalhe que causou o crash.
PACOTE="$(echo "$BADGING" | grep '^package:' | sed "s/^package: name='\([^']*\)'.*/\1/")"
LANCAMENTO="$(echo "$BADGING" | grep '^launchable-activity:' | sed "s/^launchable-activity: name='\([^']*\)'.*/\1/")"

echo "  pacote   : $PACOTE"
echo "  activity : $LANCAMENTO"

FALHOU=0

# 1) tem uma tela principal declarada?
if [ -z "$LANCAMENTO" ]; then
    echo "  ❌ SEM launchable-activity — o app não teria como abrir!"
    FALHOU=1
fi

# 2) a tela principal pertence a ESTE pacote?
case "$LANCAMENTO" in
    "$PACOTE".*)
        echo "  ✅ a Activity principal pertence ao pacote correto"
        ;;
    *)
        echo "  ❌ a Activity ($LANCAMENTO) NÃO pertence ao pacote ($PACOTE)"
        echo "     → provavelmente é o nome completo do projeto clonado. Use .MainActivity"
        FALHOU=1
        ;;
esac

# 3) o APK está assinado?
if "$ANDROID_HOME/build-tools/34.0.0/apksigner" verify "$APK" >/dev/null 2>&1; then
    echo "  ✅ assinatura válida"
else
    echo "  ❌ assinatura inválida"
    FALHOU=1
fi

# 4) alguma referência ao pacote de outro projeto?
ANTIGO="$(unzip -p "$APK" AndroidManifest.xml 2>/dev/null | strings | grep -c 'nexusmusicapp' || true)"
if [ "${ANTIGO:-0}" != "0" ]; then
    echo "  ⚠️  o manifest ainda menciona 'nexusmusicapp' ($ANTIGO vez(es)) — confira!"
fi

echo
if [ "$FALHOU" = "0" ]; then
    echo "  ✅ APK pronto para instalar"
else
    echo "  ⚠️  corrija os itens acima ANTES de entregar"
    exit 1
fi
