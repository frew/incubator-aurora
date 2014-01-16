import os

# Alias for src/main/python/apache/aurora/config/schema
python_library(
  name = 'schema',
  dependencies = [
    pants('src/main/python/apache/aurora/config/schema'),
  ]
)

python_library(
  name = 'config',
  sources = (
    '__init__.py',
    'loader.py',
    'port_resolver.py',
    'thrift.py',
  ),
  dependencies = [
    pants('src/main/python/apache/aurora/BUILD.thirdparty:pystachio'),
    pants('aurora/twitterdeps/src/python/twitter/common/lang'),
    pants('src/main/python/apache/aurora/common'),
    pants('src/main/python/apache/aurora/config/schema'),
    pants('src/main/thrift/org/apache/aurora/gen:py-thrift'),
  ],

)

python_library(
  name = 'config-packaged',
  dependencies = [
    pants(':config'),

    # covering dependencies
    pants('src/main/python/apache/thermos/config'),
  ],
  provides = setup_py(
    name = 'apache.aurora.config',
    version = open(os.path.join(get_buildroot(), '.auroraversion')).read().strip().upper(),
    description = 'Aurora/Thermos Pystachio schemas for describing job configurations.',
    license = 'Apache License, Version 2.0',
  )
)