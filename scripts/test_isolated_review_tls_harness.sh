#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
set -eu

repo=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
script="$repo/scripts/build_nexus_desktop_macos.sh"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/shackcq-review-tls-test.XXXXXX")
trap 'case "$test_root" in "${TMPDIR:-/tmp}"/shackcq-review-tls-test.*) rm -rf "$test_root";; esac' EXIT HUP INT TERM

expect_failure() {
  expected=$1
  shift
  set +e
  "$@" >"$test_root/failure.log" 2>&1
  status=$?
  set -e
  test "$status" -eq 2
  grep -F "$expected" "$test_root/failure.log" >/dev/null
}

expect_failure "SHACKCQ_ISOLATED_REVIEW_TLS_CERT must name" env \
  SHACKCQ_ISOLATED_REVIEW_BUILD=1 SHACKCQ_TEST_REVIEW_TLS_PREFLIGHT_ONLY=1 \
  "$script" "$test_root/missing"
printf 'not a certificate\n' >"$test_root/invalid.pem"
expect_failure "must contain exactly one PEM certificate" env \
  SHACKCQ_ISOLATED_REVIEW_BUILD=1 SHACKCQ_TEST_REVIEW_TLS_PREFLIGHT_ONLY=1 \
  SHACKCQ_ISOLATED_REVIEW_TLS_CERT="$test_root/invalid.pem" \
  "$script" "$test_root/invalid"
openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 1 \
  -subj '/CN=ShackCQ Isolated Review Test CA' \
  -addext 'basicConstraints=critical,CA:TRUE,pathlen:0' \
  -addext 'keyUsage=critical,keyCertSign,cRLSign' \
  -keyout "$test_root/private.key" -out "$test_root/public-ca.crt" >/dev/null 2>&1
SHACKCQ_ISOLATED_REVIEW_BUILD=1 SHACKCQ_TEST_REVIEW_TLS_PREFLIGHT_ONLY=1 \
  SHACKCQ_ISOLATED_REVIEW_TLS_CERT="$test_root/public-ca.crt" \
  "$script" "$test_root/valid" | grep -F ISOLATED_REVIEW_TLS_PREFLIGHT_OK >/dev/null
echo "ISOLATED_REVIEW_TLS_HARNESS_TESTS_OK missing=fail invalid=fail valid=pass"
