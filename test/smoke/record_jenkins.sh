#!/bin/bash
set -eo pipefail

readonly SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

readonly MAPPING_TARGET="${SCRIPT_DIR}/jenkins.tar.gz"
readonly EXAMPLE_DIR="${SCRIPT_DIR}/../../examples/jenkins"

readonly WIREMOCK_PORT="3341"

readonly WIREMOCK_BASE_URL="http://localhost:${WIREMOCK_PORT}"
readonly JENKINS_BASE_URL="http://localhost:8080"
readonly SYNC_URL="$WIREMOCK_BASE_URL"

readonly MAPPING_TMP_DIR="/tmp/record.wiremock.$$"


extract_base_url() {
    sed 's/^\(.*\/\/[^\/]*\).*$/\1/'
}

start_container() {
    "$EXAMPLE_DIR/run.sh" start
}

stop_container() {
    "$EXAMPLE_DIR/run.sh" stop
}

start_wiremock() {
    "${SCRIPT_DIR}/run_wiremock.sh" install
    mkdir -p "$MAPPING_TMP_DIR"
    ROOT_DIR="$MAPPING_TMP_DIR" PORT="$WIREMOCK_PORT" "${SCRIPT_DIR}/run_wiremock.sh" start

    echo "{\"targetBaseUrl\": \"$JENKINS_BASE_URL\", \"repeatsAsScenarios\": false}" \
        | curl --fail --silent --output /dev/null -X POST -d@- "${WIREMOCK_BASE_URL}/__admin/recordings/start"
}

stop_wiremock() {
    curl --fail --silent --output /dev/null -X POST "${WIREMOCK_BASE_URL}/__admin/recordings/stop"
    cd "$MAPPING_TMP_DIR"
    tar -czf "$MAPPING_TARGET" ./*
    cd - > /dev/null

    "${SCRIPT_DIR}/run_wiremock.sh" stop

    echo "Recorded in ${MAPPING_TARGET}"

    rm -rf "$MAPPING_TMP_DIR"
}

sync_builds() {
    "${SCRIPT_DIR}/../../lein" run -m build-facts.main jenkins "$SYNC_URL" --from 2000-01-01
}

clean_up() {
    stop_container
    stop_wiremock
}

ensure_empty_mappings() {
    if [[ -e "$MAPPING_TARGET" ]]; then
        echo "Please remove ${MAPPING_TARGET} first"
        exit 1
    fi
}

main() {
    ensure_empty_mappings

    # Handle Ctrl+C and errors
    trap clean_up EXIT

    start_container
    start_wiremock

    sync_builds
}

main
