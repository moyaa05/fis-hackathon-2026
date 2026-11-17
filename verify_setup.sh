#!/usr/bin/env bash
set -u

REPORT_FILE="diagnostico_fis.txt"
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

if [[ "$(basename "$PWD")" == "frontend" && -d "../backend" ]]; then
    cd ..
fi

{
    echo "=========================================================="
    echo "   DIAGNÓSTICO DE ARQUITECTURA - FIS HACKATHON 2026"
    echo "   Fecha: $(date -u +"%Y-%m-%d %H:%M:%S UTC")"
    echo "   Directorio actual: $PWD"
    echo "=========================================================="
    echo ""

    check_file() {
        local file="$1"
        local desc="$2"
        if [[ -s "$file" ]]; then
            echo "[OK]   Archivo presente y con contenido: $file ($desc)"
            ((PASS_COUNT++))
        elif [[ -f "$file" ]]; then
            echo "[FAIL] Archivo vacío: $file ($desc)"
            ((FAIL_COUNT++))
        else
            echo "[FAIL] No encontrado: $file ($desc)"
            ((FAIL_COUNT++))
        fi
    }

    echo "--- 1. VERIFICACIÓN DE ESTRUCTURA Y ARCHIVOS CLAVE ---"
    check_file "docker-compose.yml" "Orquestación Docker Compose"
    check_file "gateway/nginx.conf" "API Gateway & Rate Limiting"
    check_file "backend/Dockerfile" "Dockerfile Backend Java 21"
    check_file "backend/pom.xml" "Dependencias Maven Spring Boot 3"
    check_file "backend/src/main/resources/application.yml" "Configuración Spring Boot + Virtual Threads"
    check_file "backend/src/main/java/com/fis/core/CoreApplication.java" "Core Engine + SHA-256 + ReentrantLock"
    check_file "frontend/Dockerfile" "Dockerfile Frontend Node 20"
    check_file "frontend/package.json" "Manifiesto NPM Frontend"
    check_file "frontend/tailwind.config.js" "Configuración Tailwind v3"
    check_file "frontend/postcss.config.js" "Configuración PostCSS"
    check_file "frontend/vite.config.js" "Configuración Vite + Proxy /api"
    check_file "frontend/src/index.css" "Directivas @tailwind"
    check_file "frontend/src/App.jsx" "UI React conectada a /api/health"
    echo ""

    echo "--- 2. VERIFICACIÓN DE PAQUETES FRONTEND (package.json) ---"
    if [[ -f "frontend/package.json" ]]; then
        for pkg in "react" "lucide-react" "recharts" "tailwindcss" "postcss" "autoprefixer"; do
            if grep -q "\"$pkg\"" frontend/package.json; then
                ver=$(grep "\"$pkg\"" frontend/package.json | head -n 1 | sed 's/^[[:space:]]*//')
                echo "[OK]   Dependencia detectada: $ver"
                ((PASS_COUNT++))
            else
                echo "[FAIL] Falta dependencia en frontend/package.json: $pkg"
                ((FAIL_COUNT++))
            fi
        done
    fi
    echo ""

    echo "--- 3. VALIDACIÓN DE CONTENIDO CRÍTICO ---"
    if [[ -f "frontend/src/index.css" ]] && grep -q "@tailwind base;" frontend/src/index.css; then
        echo "[OK]   Directivas Tailwind inyectadas en frontend/src/index.css"
        ((PASS_COUNT++))
    else
        echo "[FAIL] Faltan directivas @tailwind en frontend/src/index.css"
        ((FAIL_COUNT++))
    fi

    if [[ -f "frontend/vite.config.js" ]] && grep -q "proxy" frontend/vite.config.js; then
        echo "[OK]   Proxy hacia backend configurado en frontend/vite.config.js"
        ((PASS_COUNT++))
    else
        echo "[FAIL] Falta configuración de proxy en frontend/vite.config.js"
        ((FAIL_COUNT++))
    fi

    if [[ -f "gateway/nginx.conf" ]] && grep -q "limit_req_zone" gateway/nginx.conf; then
        echo "[OK]   Rate-limiting configurado en gateway/nginx.conf"
        ((PASS_COUNT++))
    else
        echo "[FAIL] Falta limit_req_zone en gateway/nginx.conf"
        ((FAIL_COUNT++))
    fi
    echo ""

    echo "--- 4. ENTORNO DOCKER & VALIDACIÓN SINTÁCTICA ---"
    if command -v docker >/dev/null 2>&1; then
        echo "[OK]   Docker instalado: $(docker --version)"
        ((PASS_COUNT++))
        if docker compose version >/dev/null 2>&1; then
            echo "[OK]   Docker Compose instalado: $(docker compose version)"
            ((PASS_COUNT++))
            if [[ -f "docker-compose.yml" ]]; then
                if docker compose config -q 2>/dev/null; then
                    echo "[OK]   Sintaxis de docker-compose.yml validada correctamente"
                    ((PASS_COUNT++))
                else
                    echo "[FAIL] Error de sintaxis en docker-compose.yml:"
                    docker compose config 2>&1 | head -n 10
                    ((FAIL_COUNT++))
                fi
            fi
        else
            echo "[WARN] Subcomando 'docker compose' no disponible"
            ((WARN_COUNT++))
        fi
    else
        echo "[WARN] Docker no encontrado en PATH"
        ((WARN_COUNT++))
    fi
    echo ""

    echo "--- 5. ESTADO DE CONTENEDORES (SI ESTÁN LEVANTADOS) ---"
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
        running=$(docker ps --filter "name=fis-" --format "{{.Names}} ({{.Status}})")
        if [[ -n "$running" ]]; then
            echo "[OK]   Contenedores activos detectados:"
            echo "$running" | sed 's/^/       - /'
            if command -v curl >/dev/null 2>&1; then
                http_code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/api/health || true)
                if [[ "$http_code" == "200" ]]; then
                    echo "[OK]   Gateway + Backend respondiendo HTTP 200 en http://localhost:18080/api/health"
                    echo "       Payload: $(curl -s http://localhost:18080/api/health)"
                else
                    echo "[INFO] Endpoint http://localhost:18080/api/health devolvió HTTP $http_code"
                fi
            fi
        else
            echo "[INFO] Contenedores apagados (ejecuta 'docker compose up --build' para levantar el entorno)."
        fi
    else
        echo "[INFO] Demonio de Docker sin permisos o detenido en esta sesión."
    fi

    echo ""
    echo "=========================================================="
    echo "   RESUMEN FINAL: $PASS_COUNT OK | $FAIL_COUNT ERRORES | $WARN_COUNT AVISOS"
    if [[ "$FAIL_COUNT" -eq 0 ]]; then
        echo "   VEREDICTO: ESQUELETO 100% LISTO PARA COMPROBAR CON DOCKER"
    else
        echo "   VEREDICTO: REVISAR LOS ELEMENTOS MARCADOS CON [FAIL]"
    fi
    echo "=========================================================="
} | tee "$REPORT_FILE"
