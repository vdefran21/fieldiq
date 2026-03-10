#!/usr/bin/env bash
# ============================================================================
# dev.sh — FieldIQ local development and demo orchestration
#
# This script has two roles:
# 1. Interactive local development startup for Docker infrastructure and the
#    Kotlin backend instances.
# 2. Explicit demo orchestration helpers for seeded availability, the agent,
#    and the two Expo Metro servers used in the cross-instance mobile proof.
#
# Default behavior stays intentionally narrow:
# - `./dev.sh` or `./dev.sh start` keeps the existing "infra + both backends"
#   workflow and remains attached to the current terminal.
# - Agent startup, demo-data mutation, and Expo startup are opt-in commands.
#
# Usage:
#   ./dev.sh
#   ./dev.sh start
#   ./dev.sh infra
#   ./dev.sh a
#   ./dev.sh b
#   ./dev.sh start-agent
#   ./dev.sh seed-demo
#   ./dev.sh start-mobile-demo
#   ./dev.sh demo-up
#   ./dev.sh stop
#   ./dev.sh stop --all
#
# Environment overrides:
#   FIELDIQ_DEMO_HOST     Host/IP used for Expo demo bundles (default: detected LAN IPv4)
#   FIELDIQ_EXPO_PORT_A   Metro port for the bundle that targets backend instance A (default: 8082)
#   FIELDIQ_EXPO_PORT_B   Metro port for the bundle that targets backend instance B (default: 8083)
#   FIELDIQ_INSTANCE_A_URL / FIELDIQ_INSTANCE_B_URL
#                         Backend overrides consumed by the seed script
# ============================================================================

set -euo pipefail

# Core workspace paths used by every orchestration mode. These stay rooted at
# the repository so detached processes can be restarted from any caller cwd.
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
AGENT_DIR="$ROOT_DIR/agent"
MOBILE_DIR="$ROOT_DIR/mobile"
RUNTIME_DIR="$ROOT_DIR/.fieldiq-dev"
LOG_DIR="$RUNTIME_DIR/logs"

# State snapshots persist the PIDs and log locations for long-lived processes so
# `./dev.sh stop` can clean them up even after the original shell exits.
INSTANCE_A_STATE_FILE="$RUNTIME_DIR/instance-a.env"
INSTANCE_B_STATE_FILE="$RUNTIME_DIR/instance-b.env"
AGENT_STATE_FILE="$RUNTIME_DIR/agent.env"
METRO_A_STATE_FILE="$RUNTIME_DIR/metro-a.env"
METRO_B_STATE_FILE="$RUNTIME_DIR/metro-b.env"

# Detached-mode logs are written under `.fieldiq-dev/logs` to keep the repo root
# clean while still giving local operators a consistent place to inspect failures.
INSTANCE_A_LOG_FILE="$LOG_DIR/instance-a.log"
INSTANCE_B_LOG_FILE="$LOG_DIR/instance-b.log"
AGENT_LOG_FILE="$LOG_DIR/agent.log"
METRO_A_LOG_FILE="$LOG_DIR/metro-a.log"
METRO_B_LOG_FILE="$LOG_DIR/metro-b.log"

# Demo-mode Metro ports are configurable because Expo often conflicts with other
# local projects; defaults remain stable for the mobile proof instructions.
EXPO_PORT_A="${FIELDIQ_EXPO_PORT_A:-8082}"
EXPO_PORT_B="${FIELDIQ_EXPO_PORT_B:-8083}"

# ANSI color tokens are centralized here so operator-facing status output stays
# readable without repeating escape sequences throughout the control flow.
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Runtime PID tracking for the interactive backend mode.
INSTANCE_A_PID=""
INSTANCE_B_PID=""
INSTANCE_A_APP_PID=""
INSTANCE_B_APP_PID=""

# Creates the directory that stores persisted process state for detached mode.
ensure_runtime_dir() {
  mkdir -p "$RUNTIME_DIR"
}

# Creates the directory used by detached processes to write stdout/stderr logs.
ensure_log_dir() {
  mkdir -p "$LOG_DIR"
}

# Persists backend launcher metadata so later stop commands can find both the
# Gradle wrapper process and the JVM listener that actually owns the HTTP port.
write_instance_state() {
  local state_file=$1
  local instance_name=$2
  local instance_port=$3
  local launcher_pid=$4
  local app_pid=$5
  local log_file=${6:-}

  ensure_runtime_dir
  printf 'INSTANCE_NAME=%q\nINSTANCE_PORT=%q\nLAUNCHER_PID=%q\nAPP_PID=%q\nLOG_FILE=%q\n' \
    "$instance_name" "$instance_port" "$launcher_pid" "$app_pid" "$log_file" > "$state_file"
}

# Persists non-backend process metadata for detached demo helpers like the agent
# worker and Expo Metro servers.
write_process_state() {
  local state_file=$1
  local process_name=$2
  local pid=$3
  local log_file=$4

  ensure_runtime_dir
  printf 'PROCESS_NAME=%q\nPID=%q\nLOG_FILE=%q\n' \
    "$process_name" "$pid" "$log_file" > "$state_file"
}

# Removes a persisted state snapshot after the corresponding process is gone.
remove_state() {
  local state_file=$1
  rm -f "$state_file"
}

# Returns the first PID listening on the requested TCP port, or nothing when no
# listener is present. Port ownership is the source of truth for backend JVMs.
resolve_listener_pid() {
  local port=$1
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

# Checks whether a PID still exists without sending a terminating signal.
is_pid_running() {
  local pid=$1
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

# Reconciles the recorded backend launcher PID with the actual process now bound
# to the HTTP port. This keeps cleanup correct when Gradle forks the JVM.
capture_instance_pid() {
  local state_file=$1
  local instance_name=$2
  local port=$3
  local launcher_pid=$4
  local log_file=${5:-}
  local app_pid

  app_pid="$(resolve_listener_pid "$port")"
  if [[ -z "$app_pid" ]]; then
    echo -e "${YELLOW}  ! Could not determine $instance_name listener PID; using launcher PID $launcher_pid for cleanup.${NC}"
    app_pid="$launcher_pid"
  fi

  write_instance_state "$state_file" "$instance_name" "$port" "$launcher_pid" "$app_pid" "$log_file"
  printf '%s\n' "$app_pid"
}

# Sends a polite TERM signal to a managed PID and reports whether anything was
# actually running for the caller to wait on.
stop_pid() {
  local pid=$1
  local label=$2

  if ! is_pid_running "$pid"; then
    return 1
  fi

  echo -e "${BLUE}Stopping $label (PID $pid)...${NC}"
  kill "$pid" 2>/dev/null || true
  return 0
}

# Waits for a managed process to exit after TERM so stop flows can surface a
# warning instead of hanging forever on a stubborn process.
wait_for_pid_exit() {
  local pid=$1
  local timeout=${2:-15}
  local elapsed=0

  if [[ -z "$pid" ]]; then
    return 0
  fi

  while is_pid_running "$pid"; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [[ $elapsed -ge $timeout ]]; then
      echo -e "${YELLOW}  ! PID $pid is still running after ${timeout}s.${NC}"
      return 1
    fi
  done

  return 0
}

# Prints the tail of a managed log file when startup or shutdown does not finish
# normally, which keeps failures debuggable without opening the file manually.
print_log_hint() {
  local log_file=$1
  if [[ -n "$log_file" && -f "$log_file" ]]; then
    echo -e "${YELLOW}  Last log lines from $log_file:${NC}"
    tail -n 20 "$log_file" || true
  fi
}

# Stops one backend instance using persisted state when available and interactive
# shell PIDs as a fallback for the current session.
stop_instance() {
  local default_name=$1
  local state_file=$2
  local fallback_launcher_pid=${3:-}
  local instance_name=$default_name
  local launcher_pid=""
  local app_pid=""

  if [[ -f "$state_file" ]]; then
    # shellcheck disable=SC1090
    source "$state_file"
    instance_name="${INSTANCE_NAME:-$default_name}"
    launcher_pid="${LAUNCHER_PID:-}"
    app_pid="${APP_PID:-}"
  fi

  if [[ -z "$launcher_pid" && -n "$fallback_launcher_pid" ]]; then
    launcher_pid="$fallback_launcher_pid"
  fi

  if [[ -z "$app_pid" && -n "$launcher_pid" ]]; then
    app_pid="$launcher_pid"
  fi

  local stopped_any=false
  if stop_pid "$app_pid" "$instance_name application"; then
    stopped_any=true
  fi
  if [[ -n "$launcher_pid" && "$launcher_pid" != "$app_pid" ]] && stop_pid "$launcher_pid" "$instance_name launcher"; then
    stopped_any=true
  fi

  if [[ "$stopped_any" == true ]]; then
    wait_for_pid_exit "$app_pid" || true
    if [[ -n "$launcher_pid" && "$launcher_pid" != "$app_pid" ]]; then
      wait_for_pid_exit "$launcher_pid" || true
    fi
  fi

  remove_state "$state_file"
}

# Stops a detached non-backend process such as the agent or an Expo Metro server.
stop_managed_process() {
  local default_name=$1
  local state_file=$2
  local process_name=$default_name
  local pid=""

  if [[ -f "$state_file" ]]; then
    # shellcheck disable=SC1090
    source "$state_file"
    process_name="${PROCESS_NAME:-$default_name}"
    pid="${PID:-}"
  fi

  if stop_pid "$pid" "$process_name"; then
    wait_for_pid_exit "$pid" || true
  fi

  remove_state "$state_file"
}

# Stops both Kotlin backend instances managed by this script.
stop_backend_instances() {
  stop_instance "Instance A" "$INSTANCE_A_STATE_FILE" "$INSTANCE_A_PID"
  stop_instance "Instance B" "$INSTANCE_B_STATE_FILE" "$INSTANCE_B_PID"
}

# Stops detached demo-side helpers without touching Docker infrastructure.
stop_demo_processes() {
  stop_managed_process "Agent" "$AGENT_STATE_FILE"
  stop_managed_process "Expo Metro A" "$METRO_A_STATE_FILE"
  stop_managed_process "Expo Metro B" "$METRO_B_STATE_FILE"
}

# Trap handler for interactive mode. It intentionally leaves Docker services
# running because developers often restart only the backends while iterating.
cleanup() {
  echo ""
  echo -e "${YELLOW}Shutting down...${NC}"
  stop_backend_instances
  echo -e "${GREEN}Backend instances stopped.${NC}"
  echo -e "${YELLOW}Docker containers still running. Run './dev.sh stop' to shut them down.${NC}"
}

# Stops all managed processes but preserves Docker images and volumes so the next
# local development start stays fast.
stop_all() {
  echo -e "${YELLOW}Stopping demo-side managed processes...${NC}"
  stop_demo_processes
  echo -e "${YELLOW}Stopping backend instances...${NC}"
  stop_backend_instances
  echo -e "${YELLOW}Stopping all Docker containers...${NC}"
  docker compose -f "$ROOT_DIR/docker-compose.yml" down
  echo -e "${GREEN}All managed services stopped.${NC}"
}

# Performs a full local reset by tearing down the compose stack and deleting the
# images and volumes used by the FieldIQ development environment.
stop_all_and_prune() {
  echo -e "${YELLOW}Stopping demo-side managed processes...${NC}"
  stop_demo_processes
  echo -e "${YELLOW}Stopping backend instances...${NC}"
  stop_backend_instances
  echo -e "${YELLOW}Stopping all Docker containers and removing compose images + volumes...${NC}"
  docker compose -f "$ROOT_DIR/docker-compose.yml" down --rmi all --volumes --remove-orphans
  echo -e "${GREEN}All managed services, compose images, and volumes removed.${NC}"
}

# Probes the Spring Boot actuator health endpoint for one backend instance.
backend_healthy() {
  local port=$1
  curl -fsS "http://localhost:$port/actuator/health" >/dev/null 2>&1
}

# Waits until a backend instance becomes healthy or surfaces recent logs when
# startup stalls. Callers rely on this before seeding demo data or starting Expo.
wait_for_backend() {
  local port=$1
  local name=$2
  local log_file=${3:-}
  local max_wait=120
  local elapsed=0

  echo -e "${BLUE}Waiting for $name to be ready on port $port...${NC}"
  while ! backend_healthy "$port"; do
    sleep 2
    elapsed=$((elapsed + 2))
    if [[ $elapsed -ge $max_wait ]]; then
      echo -e "${RED}  ✗ $name did not start within ${max_wait}s${NC}"
      print_log_hint "$log_file"
      return 1
    fi
  done
  echo -e "${GREEN}  ✔ $name ready (localhost:$port)${NC}"
}

# Waits for generic TCP listeners such as Expo Metro, where an HTTP health
# endpoint is not available but port readiness is enough for local usage.
wait_for_listener_port() {
  local port=$1
  local name=$2
  local log_file=${3:-}
  local max_wait=60
  local elapsed=0

  echo -e "${BLUE}Waiting for $name to listen on port $port...${NC}"
  while [[ -z "$(resolve_listener_pid "$port")" ]]; do
    sleep 2
    elapsed=$((elapsed + 2))
    if [[ $elapsed -ge $max_wait ]]; then
      echo -e "${RED}  ✗ $name did not start within ${max_wait}s${NC}"
      print_log_hint "$log_file"
      return 1
    fi
  done
  echo -e "${GREEN}  ✔ $name listening on localhost:$port${NC}"
}

# Starts Docker infrastructure and blocks until the core local dependencies match
# the Phase 1 assumptions documented for backend and agent development.
start_infra() {
  echo -e "${BLUE}Starting Docker infrastructure...${NC}"
  docker compose -f "$ROOT_DIR/docker-compose.yml" up -d

  echo -e "${BLUE}Waiting for Postgres (Instance A) to be ready...${NC}"
  until docker exec fieldiq-db pg_isready -U fieldiq >/dev/null 2>&1; do
    sleep 1
  done
  echo -e "${GREEN}  ✔ Postgres A ready (localhost:5432)${NC}"

  echo -e "${BLUE}Waiting for Postgres (Instance B) to be ready...${NC}"
  until docker exec fieldiq-db-team-b pg_isready -U fieldiq >/dev/null 2>&1; do
    sleep 1
  done
  echo -e "${GREEN}  ✔ Postgres B ready (localhost:5433)${NC}"

  echo -e "${BLUE}Waiting for Redis to be ready...${NC}"
  until docker exec fieldiq-redis redis-cli ping 2>/dev/null | grep -q PONG; do
    sleep 1
  done
  echo -e "${GREEN}  ✔ Redis ready (localhost:6379)${NC}"

  echo -e "${BLUE}Waiting for LocalStack to be ready...${NC}"
  until curl -s http://localhost:4566/_localstack/health 2>/dev/null | grep -q '"sqs"'; do
    sleep 1
  done
  echo -e "${GREEN}  ✔ LocalStack ready (localhost:4566)${NC}"

  echo -e "${GREEN}Infrastructure is up!${NC}"
  echo ""
}

# Starts backend instance A in the current shell so Ctrl+C still tears it down.
start_instance_a() {
  echo -e "${BLUE}Starting Backend Instance A (port 8080)...${NC}"
  cd "$BACKEND_DIR"
  SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun --console=plain &
  INSTANCE_A_PID=$!
  write_instance_state "$INSTANCE_A_STATE_FILE" "Instance A" "8080" "$INSTANCE_A_PID" "" ""
  echo -e "${GREEN}  ✔ Instance A starting (PID $INSTANCE_A_PID)${NC}"
}

# Starts backend instance B in the current shell so Ctrl+C still tears it down.
start_instance_b() {
  echo -e "${BLUE}Starting Backend Instance B (port 8081)...${NC}"
  cd "$BACKEND_DIR"
  SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun --console=plain &
  INSTANCE_B_PID=$!
  write_instance_state "$INSTANCE_B_STATE_FILE" "Instance B" "8081" "$INSTANCE_B_PID" "" ""
  echo -e "${GREEN}  ✔ Instance B starting (PID $INSTANCE_B_PID)${NC}"
}

# Launches one backend instance in detached mode, records its runtime metadata,
# and waits for the health endpoint before the demo pipeline continues.
start_instance_detached() {
  local profile=$1
  local port=$2
  local label=$3
  local state_file=$4
  local log_file=$5

  if backend_healthy "$port"; then
    echo -e "${YELLOW}$label already responds on localhost:$port; skipping managed restart.${NC}"
    return 0
  fi

  ensure_log_dir
  local launcher_pid
  launcher_pid="$(
    cd "$BACKEND_DIR"
    nohup env SPRING_PROFILES_ACTIVE="$profile" ./gradlew bootRun --console=plain >"$log_file" 2>&1 &
    printf '%s' "$!"
  )"

  write_instance_state "$state_file" "$label" "$port" "$launcher_pid" "" "$log_file"
  echo -e "${GREEN}$label launching in background (PID $launcher_pid).${NC}"
  echo -e "${BLUE}  log: $log_file${NC}"

  wait_for_backend "$port" "$label" "$log_file"
  capture_instance_pid "$state_file" "$label" "$port" "$launcher_pid" "$log_file" >/dev/null
}

# Generic detached launcher used for the agent and Metro servers. It preserves a
# single managed PID per state file to avoid duplicate background processes.
start_detached_process() {
  local label=$1
  local workdir=$2
  local state_file=$3
  local log_file=$4
  shift 4

  if [[ -f "$state_file" ]]; then
    local existing_pid=""
    # shellcheck disable=SC1090
    source "$state_file"
    existing_pid="${PID:-}"
    if is_pid_running "$existing_pid"; then
      echo -e "${YELLOW}$label already running (PID $existing_pid).${NC}"
      echo -e "${BLUE}  log: ${LOG_FILE:-$log_file}${NC}"
      return 0
    fi
    remove_state "$state_file"
  fi

  ensure_log_dir
  local pid
  pid="$(
    cd "$workdir"
    nohup "$@" >"$log_file" 2>&1 &
    printf '%s' "$!"
  )"

  write_process_state "$state_file" "$label" "$pid" "$log_file"
  echo -e "${GREEN}$label launching in background (PID $pid).${NC}"
  echo -e "${BLUE}  log: $log_file${NC}"
}

# Resolves the host IP that mobile devices should use to reach local backends.
# The explicit environment override wins because multi-NIC setups are common.
resolve_demo_host() {
  if [[ -n "${FIELDIQ_DEMO_HOST:-}" ]]; then
    printf '%s\n' "$FIELDIQ_DEMO_HOST"
    return 0
  fi

  # Node provides a cross-macOS way to inspect active interfaces without adding
  # extra dependencies or relying on fragile parsing of `ifconfig` output.
  node <<'NODE'
const { networkInterfaces } = require('node:os');

const interfaces = networkInterfaces();
for (const addresses of Object.values(interfaces)) {
  if (!addresses) continue;
  for (const address of addresses) {
    if (address.family === 'IPv4' && !address.internal) {
      process.stdout.write(address.address);
      process.exit(0);
    }
  }
}

process.exit(1);
NODE
}

# Applies the deterministic availability fixture used by the two-instance mobile
# demo. Both backends must already be healthy because the seed script calls them.
run_seed_demo() {
  if ! backend_healthy 8080 || ! backend_healthy 8081; then
    echo -e "${RED}Both backend instances must be healthy before seeding demo availability.${NC}"
    return 1
  fi

  echo -e "${BLUE}Seeding deterministic demo availability...${NC}"
  node "$ROOT_DIR/scripts/seed-demo-availability.mjs" --reset
}

# Starts the agent worker in detached mode so demo notifications and queue-driven
# workflows can run alongside the backends.
start_agent_detached() {
  start_detached_process "Agent" "$AGENT_DIR" "$AGENT_STATE_FILE" "$AGENT_LOG_FILE" npm run dev
}

# Starts one Expo Metro server pointed at a specific backend instance while
# defending against accidental port collisions from unrelated local processes.
start_mobile_instance_detached() {
  local label=$1
  local port=$2
  local api_base=$3
  local state_file=$4
  local log_file=$5

  local listener_pid
  local tracked_pid=""
  if [[ -f "$state_file" ]]; then
    # shellcheck disable=SC1090
    source "$state_file"
    tracked_pid="${PID:-}"
  fi

  listener_pid="$(resolve_listener_pid "$port")"
  if [[ -n "$listener_pid" ]]; then
    if [[ -z "$tracked_pid" ]] || ! is_pid_running "$tracked_pid"; then
      echo -e "${YELLOW}$label could not be started because port $port is already in use by PID $listener_pid.${NC}"
      return 1
    fi
  fi

  start_detached_process "$label" "$MOBILE_DIR" "$state_file" "$log_file" \
    env "EXPO_PUBLIC_API_URL=$api_base" npx expo start --host lan --port "$port"
  wait_for_listener_port "$port" "$label" "$log_file"
}

# Starts the paired Metro servers used by the cross-instance mobile demo so one
# device can target instance A while another targets instance B.
start_mobile_demo() {
  local demo_host
  demo_host="$(resolve_demo_host)" || {
    echo -e "${RED}Unable to determine a LAN IPv4 address. Set FIELDIQ_DEMO_HOST to override.${NC}"
    return 1
  }

  local api_base_a="http://${demo_host}:8080"
  local api_base_b="http://${demo_host}:8081"

  echo -e "${BLUE}Starting Expo Metro for backend instance A with EXPO_PUBLIC_API_URL=$api_base_a${NC}"
  start_mobile_instance_detached "Expo Metro A" "$EXPO_PORT_A" "$api_base_a" "$METRO_A_STATE_FILE" "$METRO_A_LOG_FILE"

  echo -e "${BLUE}Starting Expo Metro for backend instance B with EXPO_PUBLIC_API_URL=$api_base_b${NC}"
  start_mobile_instance_detached "Expo Metro B" "$EXPO_PORT_B" "$api_base_b" "$METRO_B_STATE_FILE" "$METRO_B_LOG_FILE"

  echo -e "${GREEN}Expo Metro demo servers are ready.${NC}"
  echo -e "  ${BLUE}Phone / Instance A:${NC} exp://$demo_host:$EXPO_PORT_A"
  echo -e "  ${BLUE}Simulator / Instance B:${NC} exp://$demo_host:$EXPO_PORT_B"
}

# Prints the interactive-mode summary shown after backends finish booting.
print_summary() {
  echo ""
  echo -e "${GREEN}============================================${NC}"
  echo -e "${GREEN}  FieldIQ Development Environment Running${NC}"
  echo -e "${GREEN}============================================${NC}"
  echo ""
  echo -e "  ${BLUE}Infrastructure:${NC}"
  echo -e "    Postgres A:    localhost:5432  (fieldiq)"
  echo -e "    Postgres B:    localhost:5433  (fieldiq_team_b)"
  echo -e "    Redis:         localhost:6379"
  echo -e "    LocalStack:    localhost:4566"
  echo ""
  if [[ -n "$INSTANCE_A_PID" ]]; then
    echo -e "  ${BLUE}Backend:${NC}"
    echo -e "    Instance A:    http://localhost:8080  (PID ${INSTANCE_A_APP_PID:-$INSTANCE_A_PID})"
  fi
  if [[ -n "$INSTANCE_B_PID" ]]; then
    echo -e "    Instance B:    http://localhost:8081  (PID ${INSTANCE_B_APP_PID:-$INSTANCE_B_PID})"
  fi
  echo ""
  echo -e "  ${YELLOW}Press Ctrl+C to stop backend instances${NC}"
  echo ""
}

# Prints the detached demo summary, including the seeded credentials and Expo
# bundle URLs operators need on phones and simulators.
print_demo_summary() {
  local demo_host=$1

  echo ""
  echo -e "${GREEN}====================================================${NC}"
  echo -e "${GREEN}  FieldIQ Demo Environment Started (Detached Mode)${NC}"
  echo -e "${GREEN}====================================================${NC}"
  echo ""
  echo -e "  ${BLUE}Backends:${NC}"
  echo -e "    Instance A:    http://localhost:8080"
  echo -e "    Instance B:    http://localhost:8081"
  echo ""
  echo -e "  ${BLUE}Agent:${NC}"
  echo -e "    Log:           $AGENT_LOG_FILE"
  echo ""
  echo -e "  ${BLUE}Expo Metro:${NC}"
  echo -e "    Instance A:    exp://$demo_host:$EXPO_PORT_A"
  echo -e "    Instance B:    exp://$demo_host:$EXPO_PORT_B"
  echo -e "    Metro A log:   $METRO_A_LOG_FILE"
  echo -e "    Metro B log:   $METRO_B_LOG_FILE"
  echo ""
  echo -e "  ${BLUE}Seeded demo users:${NC}"
  echo -e "    Instance A:    +15551234567 / OTP 000000"
  echo -e "    Instance B:    +15559876543 / OTP 000000"
  echo ""
  echo -e "  ${YELLOW}Use './dev.sh stop' to stop all managed demo services.${NC}"
  echo ""
}

# Runs the full detached demo pipeline in the documented order: infrastructure,
# both backends, the agent, seeded data, and the paired Expo bundles.
run_demo_up() {
  local demo_host
  demo_host="$(resolve_demo_host)" || {
    echo -e "${RED}Unable to determine a LAN IPv4 address. Set FIELDIQ_DEMO_HOST to override.${NC}"
    return 1
  }

  start_infra
  start_instance_detached "instance-a" 8080 "Instance A" "$INSTANCE_A_STATE_FILE" "$INSTANCE_A_LOG_FILE"
  start_instance_detached "instance-b" 8081 "Instance B" "$INSTANCE_B_STATE_FILE" "$INSTANCE_B_LOG_FILE"
  start_agent_detached
  run_seed_demo
  start_mobile_demo
  print_demo_summary "$demo_host"
}

# Prints the intentionally small command surface for this script. Commands map to
# the development and demo workflows called out in the file header.
print_usage() {
  cat <<'USAGE'
Usage:
  ./dev.sh
  ./dev.sh start
  ./dev.sh infra
  ./dev.sh a
  ./dev.sh b
  ./dev.sh start-agent
  ./dev.sh seed-demo
  ./dev.sh start-mobile-demo
  ./dev.sh demo-up
  ./dev.sh stop [--all]
USAGE
}

# --- Main ---

MODE="${1:-all}"

case "$MODE" in
  start|all)
    start_infra
    trap cleanup EXIT INT TERM
    start_instance_a
    start_instance_b
    wait_for_backend 8080 "Instance A"
    INSTANCE_A_APP_PID="$(capture_instance_pid "$INSTANCE_A_STATE_FILE" "Instance A" "8080" "$INSTANCE_A_PID")"
    wait_for_backend 8081 "Instance B"
    INSTANCE_B_APP_PID="$(capture_instance_pid "$INSTANCE_B_STATE_FILE" "Instance B" "8081" "$INSTANCE_B_PID")"
    print_summary
    wait
    ;;
  infra)
    start_infra
    ;;
  a)
    start_infra
    trap cleanup EXIT INT TERM
    start_instance_a
    wait_for_backend 8080 "Instance A"
    INSTANCE_A_APP_PID="$(capture_instance_pid "$INSTANCE_A_STATE_FILE" "Instance A" "8080" "$INSTANCE_A_PID")"
    print_summary
    wait
    ;;
  b)
    start_infra
    trap cleanup EXIT INT TERM
    start_instance_b
    wait_for_backend 8081 "Instance B"
    INSTANCE_B_APP_PID="$(capture_instance_pid "$INSTANCE_B_STATE_FILE" "Instance B" "8081" "$INSTANCE_B_PID")"
    print_summary
    wait
    ;;
  start-agent)
    start_agent_detached
    ;;
  seed-demo)
    run_seed_demo
    ;;
  start-mobile-demo)
    start_mobile_demo
    ;;
  demo-up)
    run_demo_up
    ;;
  stop)
    case "${2:-}" in
      "")
        stop_all
        ;;
      --all)
        stop_all_and_prune
        ;;
      *)
        print_usage
        exit 1
        ;;
    esac
    ;;
  help|--help|-h)
    print_usage
    ;;
  *)
    print_usage
    exit 1
    ;;
esac
