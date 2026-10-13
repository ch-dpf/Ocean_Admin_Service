#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${OCEAN_ENV_FILE:-${PROJECT_ROOT}/deploy/application.env}"
RUNTIME_DIR="${PROJECT_ROOT}/logs"
PID_FILE="${RUNTIME_DIR}/ocean-admin.pid"
OUT_LOG="${RUNTIME_DIR}/ocean-admin-console.log"
APP_JAR="${OCEAN_APP_JAR:-${PROJECT_ROOT}/ocean-bootstrap/target/ocean-bootstrap-1.0.0-SNAPSHOT.jar}"
DEFAULT_MAGO_JAR="${PROJECT_ROOT}/ocean-business-geo/tools/mago/mago-3d-terrainer-1.14.2-release.jar"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

load_env() {
  [[ -f "${ENV_FILE}" ]] || die "Configuration not found: ${ENV_FILE}. Copy deploy/application.env.example first."
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
  [[ -n "${JAVA_TOOL_OPTIONS:-}" ]] || unset JAVA_TOOL_OPTIONS
  export SERVER_PORT="${SERVER_PORT:-${APP_PORT:-8090}}"
  export GIS_TERRAIN_MAGO_JAR_PATH="${GIS_TERRAIN_MAGO_JAR_PATH:-${DEFAULT_MAGO_JAR}}"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

check_java() {
  require_command java
  local version
  version="$(java -version 2>&1 | grep -m 1 'version "')"
  [[ "${version}" =~ \"21([\.]|\") ]] || die "Java 21 is required, found: ${version}"
}

check_runtime() {
  check_java
  [[ -f "${GIS_TERRAIN_MAGO_JAR_PATH}" ]] || die "Mago JAR not found: ${GIS_TERRAIN_MAGO_JAR_PATH}. Run git lfs pull."
  local mago_size
  mago_size="$(wc -c < "${GIS_TERRAIN_MAGO_JAR_PATH}")"
  (( mago_size > 1000000 )) || die "Mago JAR looks like a Git LFS pointer. Run git lfs pull."
}

build() {
  require_command mvn
  check_runtime
  cd "${PROJECT_ROOT}"
  mvn -B -ntp -pl ocean-bootstrap -am clean package -DskipTests
}

running_pid() {
  [[ -f "${PID_FILE}" ]] || return 1
  local pid
  pid="$(<"${PID_FILE}")"
  [[ "${pid}" =~ ^[0-9]+$ ]] || return 1
  kill -0 "${pid}" 2>/dev/null || return 1
  printf '%s' "${pid}"
}

start() {
  load_env
  check_runtime
  if pid="$(running_pid)"; then
    echo "Ocean Admin Service is already running (PID ${pid})."
    return
  fi
  [[ -f "${APP_JAR}" ]] || die "Application JAR not found: ${APP_JAR}. Run '$0 build' first."
  mkdir -p "${RUNTIME_DIR}"
  rm -f "${PID_FILE}"
  cd "${PROJECT_ROOT}"
  nohup java -jar "${APP_JAR}" >>"${OUT_LOG}" 2>&1 &
  local pid=$!
  echo "${pid}" >"${PID_FILE}"
  sleep 2
  kill -0 "${pid}" 2>/dev/null || die "Application exited during startup. Check ${OUT_LOG}."
  echo "Ocean Admin Service started (PID ${pid}, port ${APP_PORT:-8090})."
}

stop() {
  local pid
  if ! pid="$(running_pid)"; then
    rm -f "${PID_FILE}"
    echo "Ocean Admin Service is not running."
    return
  fi
  kill "${pid}"
  for _ in {1..30}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f "${PID_FILE}"
      echo "Ocean Admin Service stopped."
      return
    fi
    sleep 1
  done
  kill -9 "${pid}"
  rm -f "${PID_FILE}"
  echo "Ocean Admin Service was force-stopped after 30 seconds."
}

status() {
  local pid
  if pid="$(running_pid)"; then
    echo "Ocean Admin Service is running (PID ${pid}, port ${APP_PORT:-8090})."
  else
    echo "Ocean Admin Service is not running."
    return 1
  fi
}

logs() {
  touch "${OUT_LOG}"
  tail -n 200 -f "${OUT_LOG}"
}

usage() {
  echo "Usage: $0 {build|start|stop|restart|status|logs}"
}

action="${1:-}"
case "${action}" in
  build) load_env; build ;;
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) logs ;;
  *) usage; exit 2 ;;
esac

