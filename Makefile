.PHONY: help build up down logs clean backend frontend dev install-deps test docker-build restart shell
.PHONY: setup dev-backend dev-frontend watch-backend watch-frontend
.PHONY: logs-backend logs-frontend logs-mongodb logs-ollama
.PHONY: health-check shell-mongodb shell-ollama
.PHONY: backend-test clean-docker clean-all update-deps
.PHONY: qe-download-lib

# Variables
DOCKER_COMPOSE := docker compose
MAVEN := mvn
NODE := npm
JAVA_PORT := 8080
UI_PORT := 3000
MONGO_PORT := 27017
OLLAMA_PORT := 11434

# Colors for output
GREEN := \033[0;32m
BLUE := \033[0;34m
YELLOW := \033[0;33m
NC := \033[0m # No Color

help: ## Show this help message
	@echo "$(BLUE)╔════════════════════════════════════════════════════════════╗$(NC)"
	@echo "$(BLUE)║       AI-Native Payments - Development Commands            ║$(NC)"
	@echo "$(BLUE)╚════════════════════════════════════════════════════════════╝$(NC)"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "$(GREEN)%-25s$(NC) %s\n", $$1, $$2}'
	@echo ""
	@echo "$(YELLOW)Quick Start:$(NC)"
	@echo "  make setup      # First time setup"
	@echo "  make up         # Start all services with Docker Compose"
	@echo "  make down       # Stop all services"
	@echo ""
	@echo "$(YELLOW)Service URLs (when running):$(NC)"
	@echo "  Java Backend: http://localhost:$(JAVA_PORT)"
	@echo "  Next.js UI:   http://localhost:$(UI_PORT)"
	@echo "  MongoDB:      localhost:$(MONGO_PORT)"
	@echo "  Ollama:       http://localhost:$(OLLAMA_PORT)"

setup: ## First-time setup (install dependencies)
	@echo "$(BLUE)Setting up project...$(NC)"
	@echo "$(YELLOW)Installing frontend dependencies...$(NC)"
	cd agent-ui && $(NODE) install
	@echo "$(GREEN)✓ Frontend dependencies installed$(NC)"
	@echo "$(YELLOW)Maven will auto-download dependencies on first build$(NC)"
	@echo "$(GREEN)✓ Setup complete!$(NC)"

# ============================================================================
# DOCKER COMPOSE COMMANDS
# ============================================================================

up: ## Start all services with Docker Compose
	@echo "$(BLUE)Starting all services...$(NC)"
	$(DOCKER_COMPOSE) up --build -d
	@echo "$(GREEN)✓ Services started$(NC)"
	@sleep 5
	@echo "$(BLUE)Waiting for services to be ready...$(NC)"
	@$(MAKE) health-check
	@echo ""
	@echo "$(GREEN)╔════════════════════════════════════════════╗$(NC)"
	@echo "$(GREEN)║     All services are running! ✓           ║$(NC)"
	@echo "$(GREEN)╚════════════════════════════════════════════╝$(NC)"
	@echo ""
	@echo "$(YELLOW)Access your services:$(NC)"
	@echo "  Next.js UI:      $(BLUE)http://localhost:$(UI_PORT)$(NC)"
	@echo "  Java API:        $(BLUE)http://localhost:$(JAVA_PORT)$(NC)"
	@echo "  MongoDB:         $(BLUE)localhost:$(MONGO_PORT)$(NC)"
	@echo "  Ollama:          $(BLUE)http://localhost:$(OLLAMA_PORT)$(NC)"

down: ## Stop all services
	@echo "$(BLUE)Stopping services...$(NC)"
	$(DOCKER_COMPOSE) down
	@echo "$(GREEN)✓ Services stopped$(NC)"

restart: down up ## Restart all services

logs: ## View logs from all services
	$(DOCKER_COMPOSE) logs -f

logs-backend: ## View logs from Java backend only
	$(DOCKER_COMPOSE) logs -f api-gateway

logs-frontend: ## View logs from Next.js frontend only
	$(DOCKER_COMPOSE) logs -f agent-ui

logs-mongodb: ## View logs from MongoDB only
	$(DOCKER_COMPOSE) logs -f mongodb-atlas

logs-ollama: ## View logs from Ollama only
	$(DOCKER_COMPOSE) logs -f ollama

health-check: ## Check health of all services
	@echo "$(YELLOW)Checking service health...$(NC)"
	@echo -n "MongoDB:      "
	@$(DOCKER_COMPOSE) exec -T mongodb-atlas mongosh --eval "db.adminCommand('ping')" > /dev/null 2>&1 && echo "$(GREEN)✓$(NC)" || echo "$(YELLOW)⏳$(NC)"
	@echo -n "Ollama:       "
	@$(DOCKER_COMPOSE) exec -T ollama ollama list > /dev/null 2>&1 && echo "$(GREEN)✓$(NC)" || echo "$(YELLOW)⏳$(NC)"
	@echo -n "Java Backend: "
	@curl -s http://localhost:$(JAVA_PORT)/actuator/health > /dev/null 2>&1 && echo "$(GREEN)✓$(NC)" || echo "$(YELLOW)⏳$(NC)"
	@echo -n "Agent UI:     "
	@curl -s http://localhost:$(UI_PORT) > /dev/null 2>&1 && echo "$(GREEN)✓$(NC)" || echo "$(YELLOW)⏳$(NC)"

shell-mongodb: ## Open MongoDB shell
	$(DOCKER_COMPOSE) exec mongodb-atlas mongosh

shell-ollama: ## Open Ollama container
	$(DOCKER_COMPOSE) exec ollama sh

# ============================================================================
# BUILD COMMANDS
# ============================================================================

build: backend frontend ## Build backend and frontend

backend: ## Build Java backend with Maven
	@echo "$(BLUE)Building Java backend...$(NC)"
	$(MAVEN) -f pom.xml clean package -DskipTests
	@echo "$(GREEN)✓ Backend built successfully$(NC)"

backend-test: ## Build Java backend and run tests
	@echo "$(BLUE)Building and testing Java backend...$(NC)"
	$(MAVEN) -f pom.xml clean package
	@echo "$(GREEN)✓ Backend built and tests passed$(NC)"

frontend: ## Build Next.js frontend
	@echo "$(BLUE)Building Next.js frontend...$(NC)"
	cd agent-ui && $(NODE) run build
	@echo "$(GREEN)✓ Frontend built successfully$(NC)"

docker-build: ## Build Docker images (without starting)
	@echo "$(BLUE)Building Docker images...$(NC)"
	$(DOCKER_COMPOSE) build --no-cache
	@echo "$(GREEN)✓ Docker images built$(NC)"

# ============================================================================
# DEVELOPMENT COMMANDS
# ============================================================================

dev: setup up ## Full setup and start (first-time development setup)

dev-backend: ## Run Java backend locally (requires MongoDB running)
	@echo "$(BLUE)Starting Java backend in development mode...$(NC)"
	$(MAVEN) -f pom.xml spring-boot:run

dev-frontend: ## Run Next.js frontend in development mode
	@echo "$(BLUE)Starting Next.js frontend in development mode...$(NC)"
	cd agent-ui && $(NODE) run dev

watch-backend: ## Watch and rebuild backend on changes (requires manual restart)
	@echo "$(BLUE)Watching backend for changes...$(NC)"
	$(MAVEN) -f pom.xml compile -DskipTests -e

watch-frontend: ## Watch and rebuild frontend on changes (auto-restart)
	@echo "$(BLUE)Watching frontend for changes...$(NC)"
	cd agent-ui && $(NODE) run dev

# ============================================================================
# TESTING COMMANDS
# ============================================================================

test: ## Run all tests
	@echo "$(BLUE)Running tests...$(NC)"
	$(MAVEN) -f pom.xml test
	@echo "$(GREEN)✓ Tests passed$(NC)"

test-backend: ## Run backend tests only
	@echo "$(BLUE)Running backend tests...$(NC)"
	$(MAVEN) -f pom.xml test

# ============================================================================
# CLEANUP COMMANDS
# ============================================================================

clean: ## Clean build artifacts
	@echo "$(BLUE)Cleaning build artifacts...$(NC)"
	$(MAVEN) -f pom.xml clean
	cd agent-ui && $(NODE) run build:clean 2>/dev/null || rm -rf .next out node_modules/.cache
	@echo "$(GREEN)✓ Cleaned$(NC)"

clean-docker: ## Remove all Docker containers and volumes
	@echo "$(BLUE)Cleaning Docker resources...$(NC)"
	$(DOCKER_COMPOSE) down -v
	@echo "$(GREEN)✓ Docker containers and volumes removed$(NC)"

clean-all: clean clean-docker ## Full cleanup (build artifacts + Docker resources)
	@echo "$(BLUE)Full cleanup complete$(NC)"

# ============================================================================
# INSTALLATION & DEPENDENCY COMMANDS
# ============================================================================

install-deps: ## Install all dependencies (frontend + Maven)
	@echo "$(BLUE)Installing dependencies...$(NC)"
	cd agent-ui && $(NODE) install
	$(MAVEN) -f pom.xml dependency:resolve
	@echo "$(GREEN)✓ Dependencies installed$(NC)"

update-deps: ## Update dependencies to latest versions
	@echo "$(BLUE)Updating dependencies...$(NC)"
	cd agent-ui && $(NODE) update
	$(MAVEN) -f pom.xml versions:use-latest-versions
	@echo "$(GREEN)✓ Dependencies updated$(NC)"

qe-download-lib: ## Download mongo_crypt_v1 library into src/main/resources/qe-native
	@echo "$(BLUE)Downloading QE crypt shared library...$(NC)"
	sh scripts/download-qe-lib.sh
	@echo "$(GREEN)✓ QE library installed into src/main/resources/qe-native$(NC)"

# ============================================================================
# DATABASE COMMANDS
# ============================================================================

db-reset: ## Reset MongoDB database (WARNING: deletes all data)
	@echo "$(YELLOW)⚠️  WARNING: This will delete all MongoDB data!$(NC)"
	@read -p "Are you sure? (y/N) " -n 1 -r; \
	echo; \
	if [[ $$REPLY =~ ^[Yy]$$ ]]; then \
		echo "$(BLUE)Resetting MongoDB...$(NC)"; \
		$(DOCKER_COMPOSE) exec -T mongodb-atlas mongosh --eval "db.dropDatabase()"; \
		echo "$(GREEN)✓ Database reset$(NC)"; \
	else \
		echo "Cancelled"; \
	fi

db-backup: ## Backup MongoDB database
	@echo "$(BLUE)Backing up MongoDB...$(NC)"
	mkdir -p ./backups
	$(DOCKER_COMPOSE) exec -T mongodb-atlas mongodump --out /backups/mongodb-backup-$$(date +%Y%m%d-%H%M%S)
	@echo "$(GREEN)✓ Database backed up$(NC)"

db-shell: ## Open MongoDB shell
	$(DOCKER_COMPOSE) exec mongodb-atlas mongosh

# ============================================================================
# UTILITY COMMANDS
# ============================================================================

status: ## Show status of all services
	@echo "$(BLUE)Docker Compose Services:$(NC)"
	$(DOCKER_COMPOSE) ps

version: ## Show version information
	@echo "$(BLUE)Version Information:$(NC)"
	@echo "Java: $$(java -version 2>&1 | head -1)"
	@echo "Node: $$($(NODE) --version)"
	@echo "Maven: $$($(MAVEN) --version | head -1)"
	@echo "Docker: $$(docker --version)"
	@echo "Docker Compose: $$($(DOCKER_COMPOSE) --version)"

info: ## Show project information
	@echo "$(BLUE)╔════════════════════════════════════════════════════════════╗$(NC)"
	@echo "$(BLUE)║       AI-Native Payments System - Project Info             ║$(NC)"
	@echo "$(BLUE)╚════════════════════════════════════════════════════════════╝$(NC)"
	@echo ""
	@echo "$(YELLOW)Project Structure:$(NC)"
	@echo "  Backend:  Java 21 + Spring Boot + LangChain4j"
	@echo "  Frontend: Next.js 14 + TypeScript"
	@echo "  Database: MongoDB Atlas Local"
	@echo "  LLM:      Ollama (trinity-mini)"
	@echo "  Embedding: Voyage AI (via Ollama)"
	@echo "  Reranker: TEI with BAAI bge-m3"
	@echo ""
	@echo "$(YELLOW)Key Files:$(NC)"
	@echo "  Backend:      pom.xml"
	@echo "  Frontend:     agent-ui/package.json"
	@echo "  Compose:      docker-compose.yaml"
	@echo "  Dockerfile:   Dockerfile (backend)"
	@echo ""

env-check: ## Check environment and prerequisites
	@echo "$(BLUE)Checking prerequisites...$(NC)"
	@command -v java >/dev/null 2>&1 && echo "$(GREEN)✓$(NC) Java installed" || echo "$(YELLOW)✗$(NC) Java not found - required for local development"
	@command -v $(NODE) >/dev/null 2>&1 && echo "$(GREEN)✓$(NC) Node.js installed" || echo "$(YELLOW)✗$(NC) Node.js not found"
	@command -v $(MAVEN) >/dev/null 2>&1 && echo "$(GREEN)✓$(NC) Maven installed" || echo "$(YELLOW)✗$(NC) Maven not found - required for building"
	@command -v docker >/dev/null 2>&1 && echo "$(GREEN)✓$(NC) Docker installed" || echo "$(YELLOW)✗$(NC) Docker not found - required for Docker Compose"
	@command -v $(DOCKER_COMPOSE) >/dev/null 2>&1 && echo "$(GREEN)✓$(NC) Docker Compose installed" || echo "$(YELLOW)✗$(NC) Docker Compose not found"

# ============================================================================
# LINT & CODE QUALITY
# ============================================================================

lint-frontend: ## Lint frontend code
	@echo "$(BLUE)Linting frontend...$(NC)"
	cd agent-ui && $(NODE) run lint
	@echo "$(GREEN)✓ Frontend lint passed$(NC)"

format-frontend: ## Format frontend code
	@echo "$(BLUE)Formatting frontend...$(NC)"
	cd agent-ui && $(NODE) run format 2>/dev/null || echo "$(YELLOW)Format script not configured$(NC)"

# ============================================================================
# DOCUMENTATION
# ============================================================================

docs: ## Open project documentation
	@echo "$(BLUE)Opening documentation...$(NC)"
	@cat README.md

# ============================================================================
# TROUBLESHOOTING
# ============================================================================

troubleshoot: env-check health-check ## Run full troubleshooting diagnostics
	@echo "$(GREEN)✓ Troubleshooting complete$(NC)"

.DEFAULT_GOAL := help
