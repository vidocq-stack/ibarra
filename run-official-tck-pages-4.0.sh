#!/usr/bin/env bash
# Precompile official Pages WARs before deploying them on modular Foy + Ibarra.
set -euo pipefail
cd "$(dirname "$0")"

prepare=true
install=true
smoke_selection='-Dtest=ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspfactory.URLClientIT,ee.jakarta.tck.pages.api.jakarta_servlet.jsp.jspengineinfo.URLClientIT,ee.jakarta.tck.pages.spec.configuration.charsequence.URLClientIT,ee.jakarta.tck.pages.spec.configuration.elevaluation.URLClientIT,ee.jakarta.tck.pages.spec.configuration.encoding.URLClientIT'
selection="$smoke_selection"
extra=()
for arg in "$@"; do
    case "$arg" in
        smoke) selection="$smoke_selection" ;;
        all|--all) selection='-Dtest=ee.jakarta.tck.pages.**.*IT' ;;
        --no-prepare) prepare=false ;;
        --no-install) install=false ;;
        -Dtest=*) selection="$arg" ;;
        *) extra+=("$arg") ;;
    esac
done

if "$prepare"; then python3 ibarra-tck/prepare-tck.py; fi
if "$install"; then ./mvnw -ntp clean install; fi
./mvnw -ntp -Ptck -pl ibarra-tck clean test
./mvnw -ntp -Ptck,tck-official -pl ibarra-tck clean test "$selection" "${extra[@]}"
