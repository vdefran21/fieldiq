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
#   ./dev.sh stop     Stop everything
# ============================================================================

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$ROOT_DIR/backend"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# PIDs for cleanup
INSTANCE_A_PID=""
INSTANCE_B_PID=""

cleanup() {
  echo ""
  echo -e "${YELLOW}Shutting down...${NC}"
  if [[ -n "$INSTANCE_A_PID" ]]; then
    echo -e "${BLUE}Stopping Instance A (PID $INSTANCE_A_PID)...${NC}"
    kill "$INSTANCE_A_PID" 2>/dev/null || true
    wait "$INSTANCE_A_PID" 2>/dev/null || true
  fi
  if [[ -n "$INSTANCE_B_PID" ]]; then
    echo -e "${BLUE}Stopping Instance B (PID $INSTANCE_B_PID)...${NC}"
    kill "$INSTANCE_B_PID" 2>/dev/null || true
    wait "$INSTANCE_B_PID" 2>/dev/null || true
  fi
  echo -e "${GREEN}Backend instances stopped.${NC}"
  echo -e "${YELLOW}Docker containers still running. Run './dev.sh stop' to shut them down.${NC}"
}

stop_all() {
  echo -e "${YELLOW}Stopping all Docker containers...${NC}"
  docker compose -f "$ROOT_DIR/docker-compose.yml" down
  echo -e "${GREEN}All services stopped.${NC}"
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
  echo -e "${GREEN}  ✔ Instance A starting (PID $INSTANCE_A_PID)${NC}"
}

start_instance_b() {
  echo -e "${BLUE}Starting Backend Instance B (port 8081)...${NC}"
  cd "$BACKEND_DIR"
  SPRING_PROFILES_ACTIVE=instance-b ./gradlew bootRun --console=plain &
  INSTANCE_B_PID=$!
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
    echo -e "    Instance A:    http://localhost:8080  (PID $INSTANCE_A_PID)"
  fi
  if [[ -n "$INSTANCE_B_PID" ]]; then
    echo -e "    Instance B:    http://localhost:8081  (PID $INSTANCE_B_PID)"
  fi
  echo ""
  echo -e "  ${YELLOW}Press Ctrl+C to stop backend instances${NC}"
  echo ""
}

# --- Main ---

MODE="${1:-all}"

case "$MODE" in
  stop)
    stop_all
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
    print_summary
    wait
    ;;
  b)
    start_infra
    trap cleanup EXIT INT TERM
    start_instance_b
    wait_for_backend 8081 "Instance B"
    print_summary
    wait
    ;;
  all)
    start_infra
    trap cleanup EXIT INT TERM
    start_instance_a
    start_instance_b
    wait_for_backend 8080 "Instance A"
    wait_for_backend 8081 "Instance B"
    print_summary
    wait
    ;;
  *)
    echo "Usage: ./dev.sh [all|infra|a|b|stop]"
    exit 1
    ;;
esac
