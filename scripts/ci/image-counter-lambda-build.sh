#!/usr/bin/env bash

apt-get update
apt-get install zip

set -ex

SCRIPT_DIR=$(dirname ${0})

pushd ${SCRIPT_DIR}/../../image-counter-lambda

npm ci
npm test
npm run riffraff-artefact

popd