#!/bin/bash
set -e
./gradlew distTar
./pants src/main/python/apache/aurora/client/bin:aurora_client
./pants src/main/python/apache/aurora/client/cli:aurora2
./pants src/main/python/apache/aurora/client/bin:aurora_admin
./pants src/main/python/apache/aurora/executor/bin:gc_executor
./pants src/main/python/apache/aurora/executor/bin:thermos_executor
./pants src/main/python/apache/aurora/executor/bin:thermos_runner
./pants src/main/python/apache/thermos/observer/bin:thermos_observer

python <<EOF
import contextlib
import zipfile
with contextlib.closing(zipfile.ZipFile('dist/thermos_executor.pex', 'a')) as zf:
  zf.writestr('apache/aurora/executor/resources/__init__.py', '')
  zf.write('dist/thermos_runner.pex', 'apache/aurora/executor/resources/thermos_runner.pex')
EOF

