#!/usr/bin/env bash
# ============================================================================
# dev.sh — Start FieldIQ local development environment
#
# Starts Docker infrastructure (Postgres x2, Redis, LocalStack), waits for
# health checks, then launches both backend instances (A on :8080, B on :8081).
#
# Usage:
#   ./dev.sh          Start everything (infra + both backends)
#   ./dev.sh infra    Start infrastructure only
#   ./dev.sh a        Start infra + Instance A only
#   ./dev.sh b        Start infra + Instance B only
#   ./dev.sh stop     Stop Docker containers for this stack
#   ./dev.sh stop --all  Stop Docker containers and remove compose images + volumes
# ============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"
RUNTIME_DIR="$ROOT_DIR/.fieldiq-dev"
INSTANCE_A_STATE_FILE="$RUNTIME_DIR/instance-a.env"
INSTANCE_B_STATE_FILE="$RUNTIME_DIR/instance-b.env"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# PIDs for cleanup
INSTANCE_A_PID=""
INSTANCE_B_PID=""
INSTANCE_A_APP_PID=""
INSTANCE_B_APP_PID=""

ensure_runtime_dir() {
  mkdir -p "$RUNTIME_DIR"
}

write_instance_state() {
  local state_file=$1
  local instance_name=$2
  local instance_port=$3
  local launcher_pid=$4
  local app_pid=$5

  ensure_runtime_dir
  printf 'INSTANCE_NAME=%q\nINSTANCE_PORT=%q\nLAUNCHER_PID=%q\nAPP_PID=%q\n' \
    "$instance_name" "$instance_port" "$launcher_pid" "$app_pid" > "$state_file"
}

remove_instance_state() {
  local state_file=$1
  rm -f "$state_file"
}

resolve_listener_pid() {
  local port=$1
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

capture_instance_pid() {
  local state_file=$1
  local instance_name=$2
  local port=$3
  local launcher_pid=$4
  local app_pid

  app_pid="$(resolve_listener_pid "$port")"
  if [[ -z "$app_pid" ]]; then
    echo -e "${YELLOW}  ! Could not determine $instance_name listener PID; using launcher PID $launcher_pid for cleanup.${NC}"
    app_pid="$launcher_pid"
  fi

  write_instance_state "$state_file" "$instance_name" "$port" "$launcher_pid" "$app_pid"
  printf '%s\n' "$app_pid"
}

stop_pid() {
  local pid=$1
  local label=$2

  if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
    return 1
  fi

  echo -e "${BLUE}Stopping $label (PID $pid)...${NC}"
  kill "$pid" 2>/dev/null || true
  return 0
}

wait_for_pid_exit() {
  local pid=$1
  local timeout=${2:-15}
  local elapsed=0

  if [[ -z "$pid" ]]; then
    return 0
  fi

  while kill -0 "$pid" 2>/dev/null; do
    sleep 1
    elapsed=$((elapsed + 1))
    if [[ $elapsed -ge $timeout ]]; then
      echo -e "${YELLOW}  ! PID $pid is still running after ${timeout}s.${NC}"
      return 1
    fi
  done

  return 0
}

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

  remove_instance_state "$state_file"
}

stop_backend_instances() {
  stop_instance "Instance A" "$INSTANCE_A_STATE_FILE" "$INSTANCE_A_PID"
  stop_instance "Instance B" "$INSTANCE_B_STATE_FILE" "$INSTANCE_B_PID"
}

cleanup() {
  echo ""
  echo -e "${YELLOW}Shutting down...${NC}"
  stop_backend_instances
  echo -e "${GREEN}Backend instances stopped.${NC}"
  echo -e "${YELLOW}Docker containers still running. Run './dev.sh stop' to shut them down.${NC}"
}

stop_all() {
  echo -e "${YELLOW}Stopping backend instances...${NC}"
  stop_backend_instances
  echo -e "${YELLOW}Stopping all Docker containers...${NC}"
  docker compose -f "$ROOT_DIR/docker-compose.yml" down
  echo -e "${GREEN}All services stopped.${NC}"
}

stop_all_and_prune() {
  echo -e "${YELLOW}Stopping backend instances...${NC}"
  stop_backend_instances
  echo -e "${YELLOW}Stopping all Docker containers and removing compose images + volumes...${NC}"
  docker compose -f "$ROOT_DIR/docker-compose.yml" down --rmi all --volumes --remove-orphans
  echo -e "${GREEN}All services, compose images, and volumes removed.${NC}"
}

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

start_instance_a() {
  echo -e "${BLUE}Starting Backend Instance A (port 8080)...${NC}"
  cd "$BACKEND_DIR"
  SPRING_PROFILES_ACTIVE=instance-a ./gradlew bootRun --console=plain &
  INSTANCE_A_PID=$!
  write_instance_state "$INSTANCE_A_STATE_FILE" "Instance A" "8080" "$INSTANCE_A_PID" ""
  echo -e "${GREEN}  ✔ Instance A starting (PID $INSTANCE_A_PID)${NC}"
}

start_instance_b() {
  echo -e "${BLUE}Starting Backend Instance B (port 8081)...${NC}"
  cd "$BACKEND_DIR"
  SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun --console=plain &
  INSTANCE_B_PID=$!
  write_instance_state "$INSTANCE_B_STATE_FILE" "Instance B" "8081" "$INSTANCE_B_PID" ""
  echo -e "${GREEN}  ✔ Instance B starting (PID $INSTANCE_B_PID)${NC}"
}

wait_for_backend() {
  local port=$1
  local name=$2
  local max_wait=120
  local elapsed=0

  echo -e "${BLUE}Waiting for $name to be ready on port $port...${NC}"
  while ! curl -s "http://localhost:$port/actuator/health" >/dev/null 2>&1; do
    sleep 2
    elapsed=$((elapsed + 2))
    if [[ $elapsed -ge $max_wait ]]; then
      echo -e "${RED}  ✗ $name did not start within ${max_wait}s${NC}"
      return 1
    fi
  done
  echo -e "${GREEN}  ✔ $name ready (localhost:$port)${NC}"
}

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

# --- Main ---

MODE="${1:-all}"

case "$MODE" in
  stop)
    case "${2:-}" in
      "")
        stop_all
        ;;
      --all)
        stop_all_and_prune
        ;;
      *)
        echo "Usage: ./dev.sh stop [--all]"
        exit 1
        ;;
    esac
    exit 0
    ;;
  infra)
    start_infra
    exit 0
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
  all)
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
  *)
    echo "Usage: ./dev.sh [all|infra|a|b|stop [--all]]"
    exit 1
    ;;
esac
