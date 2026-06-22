#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

error() { echo -e "${RED}Error: $*${NC}" >&2; }
warn()  { echo -e "${YELLOW}Warning: $*${NC}" >&2; }
info()  { echo -e "${GREEN}$*${NC}"; }

check_java() {
    if ! command -v java &> /dev/null; then
        error "Java is not installed or not on PATH."
        error "Please install JDK 17 or higher: https://adoptium.net/"
        exit 1
    fi

    local version
    version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | head -1)
    local major
    major=$(echo "$version" | cut -d. -f1 | sed 's/^1\.//')

    if [ -z "$major" ] || [ "$major" -lt 17 ]; then
        error "Java $version detected. JDK 17 or higher is required."
        error "Current JAVA_HOME: ${JAVA_HOME:-<not set>}"
        exit 1
    fi

    info "Java version: $version"
}

check_maven() {
    if ! command -v mvn &> /dev/null; then
        error "Maven is not installed or not on PATH."
        error "Please install Maven 3.6 or higher: https://maven.apache.org/download.cgi"
        exit 1
    fi

    local version
    version=$(mvn -v 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    local major minor
    major=$(echo "$version" | cut -d. -f1)
    minor=$(echo "$version" | cut -d. -f2)

    if [ -z "$major" ] || [ "$major" -lt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -lt 6 ]; }; then
        error "Maven $version detected. Maven 3.6 or higher is required."
        exit 1
    fi

    info "Maven version: $version"
}

check_java
check_maven

info "Building PKSPKMS..."
if ! mvn clean package -q; then
    error "Build failed. See Maven output above for details."
    exit 1
fi

JAR="$SCRIPT_DIR/pkspkms-desktop/target/pkspkms-desktop-0.1.0.jar"
if [ ! -f "$JAR" ]; then
    error "Expected JAR not found: $JAR"
    exit 1
fi

info "Build successful!"
info "Fat JAR: $JAR"
info ""
info "Next steps:"
info "  ./install.sh          # Install to ~/.local"
info "  ./install.sh --system # Install to /usr/local (requires sudo)"
