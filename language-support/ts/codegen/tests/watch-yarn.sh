# Copyright (c) 2019 The DAML Authors. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"

pushd $DIR/ts
yarn install
yarn workspace "@digitalasset/daml-json-types" run build
yarn workspace "@digitalasset/daml-ledger-fetch" run build
yarn workspace generated run build:watch