# Metabase Rill Driver - Makefile
# ================================

# Configuration
METABASE_DIR ?= ../metabase
METABASE_VERSION ?= v0.50.0
METABASE_JAR_URL ?= https://downloads.metabase.com/$(METABASE_VERSION)/metabase.jar
DUCKDB_DRIVER_VERSION ?= 0.2.6
DUCKDB_DRIVER_URL ?= https://github.com/MotherDuck-Open-Source/metabase_duckdb_driver/releases/download/$(DUCKDB_DRIVER_VERSION)/duckdb.metabase-driver.jar

# Paths
DRIVER_JAR = target/rill.metabase-driver.jar
PLUGINS_DIR = $(METABASE_DIR)/plugins
METABASE_JAR = $(METABASE_DIR)/target/uberjar/metabase.jar

# Java options
JAVA_OPTS ?= -Xmx2g

.PHONY: help setup setup-metabase build build-driver clean run run-jar test test-unit test-integration lint deps docker-build docker-run

# Default target
help:
	@echo "Metabase Rill Driver - Available targets:"
	@echo ""
	@echo "  Setup:"
	@echo "    make setup            - Full setup (clone metabase, install deps, build driver)"
	@echo "    make setup-metabase   - Clone/update Metabase repository"
	@echo "    make deps             - Install Clojure dependencies"
	@echo ""
	@echo "  Build:"
	@echo "    make build            - Build the Rill driver JAR"
	@echo "    make build-metabase   - Build Metabase uberjar (takes a while)"
	@echo "    make clean            - Remove build artifacts"
	@echo ""
	@echo "  Run:"
	@echo "    make run              - Run Metabase with Rill plugin (dev mode)"
	@echo "    make run-jar          - Run Metabase from JAR with plugins"
	@echo "    make download-metabase-jar - Download pre-built Metabase JAR"
	@echo ""
	@echo "  Test:"
	@echo "    make test             - Run all tests"
	@echo "    make test-unit        - Run unit tests"
	@echo "    make test-integration - Run integration tests"
	@echo "    make lint             - Run linter"
	@echo ""
	@echo "  Docker:"
	@echo "    make docker-build-oss        - Build OSS Docker image"
	@echo "    make docker-build-enterprise - Build Enterprise Docker image"
	@echo "    make docker-run              - Run Docker container"
	@echo ""
	@echo "  Configuration (environment variables):"
	@echo "    METABASE_DIR          - Path to Metabase repo (default: ../metabase)"
	@echo "    METABASE_VERSION      - Metabase version for JAR download (default: v0.50.0)"

# ============================================================================
# Setup
# ============================================================================

setup: setup-metabase deps build install-plugin
	@echo "✓ Setup complete! Run 'make run' to start Metabase with the Rill plugin."

setup-metabase:
	@if [ ! -d "$(METABASE_DIR)" ]; then \
		echo "Cloning Metabase repository..."; \
		git clone --depth 1 https://github.com/metabase/metabase.git $(METABASE_DIR); \
	else \
		echo "Metabase directory exists, updating..."; \
		cd $(METABASE_DIR) && git pull; \
	fi
	@mkdir -p $(PLUGINS_DIR)

deps:
	@echo "Installing Clojure dependencies..."
	@clojure -P

# ============================================================================
# Build
# ============================================================================

build: build-driver

build-driver: $(DRIVER_JAR)

$(DRIVER_JAR): src/metabase/driver/rill.clj deps.edn resources/metabase-plugin.yaml
	@echo "Building Rill driver..."
	@cd $(METABASE_DIR) && clojure \
		-Sdeps '{:aliases {:rill {:extra-deps {metabase/rill-driver {:local/root "$(CURDIR)"}}}}}' \
		-X:build:rill build-drivers.build-driver/build-driver! \
		:driver :rill \
		:project-dir '"$(CURDIR)"' \
		:target-dir '"$(CURDIR)/target"'
	@echo "✓ Built $(DRIVER_JAR)"

build-metabase:
	@echo "Building Metabase uberjar (this will take a while)..."
	@cd $(METABASE_DIR) && ./bin/build.sh

install-plugin: $(DRIVER_JAR)
	@echo "Installing Rill driver to Metabase plugins directory..."
	@mkdir -p $(PLUGINS_DIR)
	@cp $(DRIVER_JAR) $(PLUGINS_DIR)/
	@echo "✓ Installed to $(PLUGINS_DIR)/rill.metabase-driver.jar"

install-duckdb-plugin:
	@echo "Downloading MotherDuck/DuckDB driver..."
	@mkdir -p $(PLUGINS_DIR)
	@curl -fsSL -o $(PLUGINS_DIR)/duckdb.metabase-driver.jar $(DUCKDB_DRIVER_URL)
	@echo "✓ Installed DuckDB driver to $(PLUGINS_DIR)/"

clean:
	@echo "Cleaning build artifacts..."
	@rm -rf target
	@rm -f $(PLUGINS_DIR)/rill.metabase-driver.jar
	@echo "✓ Clean complete"

# ============================================================================
# Run
# ============================================================================

download-metabase-jar:
	@echo "Downloading Metabase $(METABASE_VERSION) JAR..."
	@mkdir -p $(METABASE_DIR)/target/uberjar
	@curl -fsSL -o $(METABASE_JAR) $(METABASE_JAR_URL)
	@echo "✓ Downloaded to $(METABASE_JAR)"

# Run Metabase in development mode with the driver loaded
run: install-plugin
	@echo "Starting Metabase in development mode..."
	@cd $(METABASE_DIR) && clojure \
		-Sdeps '{:aliases {:rill {:extra-deps {metabase/rill-driver {:local/root "$(CURDIR)"}}}}}' \
		-M:run:rill

# Run Metabase from JAR with plugins
run-jar: install-plugin
	@if [ ! -f "$(METABASE_JAR)" ]; then \
		echo "Metabase JAR not found. Run 'make download-metabase-jar' or 'make build-metabase' first."; \
		exit 1; \
	fi
	@echo "Starting Metabase from JAR..."
	@cd $(METABASE_DIR) && java $(JAVA_OPTS) \
		-Duser.timezone=UTC \
		-jar target/uberjar/metabase.jar

# Run with all plugins (Rill + DuckDB)
run-all-plugins: install-plugin install-duckdb-plugin run-jar

# ============================================================================
# Test
# ============================================================================

test: test-unit

test-unit:
	@echo "Running unit tests..."
	@cd $(METABASE_DIR) && clojure \
		-Sdeps '{:aliases {:rill {:extra-deps {metabase/rill-driver {:local/root "$(CURDIR)"}}}}}' \
		-X:dev:test:rill \
		:only metabase.driver.rill-test

test-integration:
	@echo "Running integration tests..."
	@echo "Note: Requires a running Rill instance"
	@cd $(METABASE_DIR) && clojure \
		-Sdeps '{:aliases {:rill {:extra-deps {metabase/rill-driver {:local/root "$(CURDIR)"}}}}}' \
		-X:dev:test:rill \
		:only metabase.driver.rill-integration-test

# Basic connectivity test
test-connection:
	@echo "Testing Rill connection..."
	@curl -s http://localhost:9009/v1/instances/default/api | head -c 500 || echo "Failed to connect to Rill at localhost:9009"

lint:
	@echo "Running linter..."
	@clojure -M:lint

# REPL for development
repl:
	@echo "Starting REPL with Rill driver..."
	@cd $(METABASE_DIR) && clojure \
		-Sdeps '{:aliases {:rill {:extra-deps {metabase/rill-driver {:local/root "$(CURDIR)"}}}}}' \
		-M:dev:rill:nrepl

# ============================================================================
# Docker
# ============================================================================

docker-build-oss:
	@echo "Building OSS Docker image..."
	@cd .. && docker build -f rill-driver/Dockerfile.oss -t metabase-rill:latest .

docker-build-enterprise:
	@echo "Building Enterprise Docker image..."
	@cd .. && docker build -f rill-driver/Dockerfile.enterprise -t metabase-rill-ee:latest .

docker-run:
	@echo "Running Metabase container..."
	@docker run -p 3000:3000 \
		-v metabase-data:/metabase-data \
		-e MB_DB_FILE=/metabase-data/metabase.db \
		metabase-rill:latest

docker-run-enterprise:
	@echo "Running Metabase Enterprise container..."
	@docker run -p 3000:3000 \
		-v metabase-data:/metabase-data \
		-e MB_DB_FILE=/metabase-data/metabase.db \
		-e MB_PREMIUM_EMBEDDING_TOKEN=$(MB_PREMIUM_EMBEDDING_TOKEN) \
		metabase-rill-ee:latest

docker-clean:
	@echo "Removing Docker images..."
	@docker rmi metabase-rill:latest metabase-rill-ee:latest 2>/dev/null || true
	@echo "✓ Docker images removed"

# ============================================================================
# Development helpers
# ============================================================================

# Watch for changes and rebuild
watch:
	@echo "Watching for changes..."
	@while true; do \
		$(MAKE) build-driver 2>/dev/null; \
		sleep 5; \
	done

# Show current configuration
info:
	@echo "Configuration:"
	@echo "  METABASE_DIR:          $(METABASE_DIR)"
	@echo "  METABASE_VERSION:      $(METABASE_VERSION)"
	@echo "  DRIVER_JAR:            $(DRIVER_JAR)"
	@echo "  PLUGINS_DIR:           $(PLUGINS_DIR)"
	@echo ""
	@echo "Status:"
	@echo "  Metabase repo:         $(shell [ -d "$(METABASE_DIR)" ] && echo "✓ exists" || echo "✗ missing")"
	@echo "  Metabase JAR:          $(shell [ -f "$(METABASE_JAR)" ] && echo "✓ exists" || echo "✗ missing")"
	@echo "  Driver JAR:            $(shell [ -f "$(DRIVER_JAR)" ] && echo "✓ exists" || echo "✗ missing")"
	@echo "  Plugin installed:      $(shell [ -f "$(PLUGINS_DIR)/rill.metabase-driver.jar" ] && echo "✓ yes" || echo "✗ no")"
