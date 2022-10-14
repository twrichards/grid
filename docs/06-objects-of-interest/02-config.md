# Config

The Grid has different config files depending on the service and usage e.g. the Guardian or BBC-specific features.
[`GridConfigLoader.scala`](https://github.com/guardian/grid/blob/main/common-lib/src/main/scala/com/gu/mediaservice/lib/config/GridConfigLoader.scala) loads the configs, which get parsed and turned into a tree of config by a function in this library (https://github.com/lightbend/config) which will recursively merge the trees.

#### [`application.conf`](https://github.com/guardian/grid/blob/3fb1d936f82a0cd02a2a3c4293b680f7f8f5caf5/common-lib/src/main/resources/application.conf)

The file lists configs that every user and service should have. This file should provide good defaults for grid features where required. This file has the lowest priority and keys will be overridden if set in other files.

### Other config files

Other config files will be read from `$HOME/.grid/` (when running locally) or `/etc/grid` (when deployed, as determined by the contents of `/etc/grid/stage`).
For local development, files will be generated by the [generate-config script](/dev/script/generate-config). It is up to the implementor to determine how to load config files onto deployed instances - we at the Guardian currently fetch files from S3 in a [UserData script](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/user-data.html).
 
This file will be packaged by sbt-native-packager, available once installed on the destination instance and automatically loaded by Play.

> **Note**
> Currently this is the only file that is loaded at Play's first initialisation. Some play settings will only take effect if set in this file.

#### `<configRoot>/<stage>/common.conf`
Anything that's specific to an organisation and to a stage, but is common across all the services. Keys set here will override those set in `application.conf` but be overridden by the service-specific configs.

#### `<configRoot>/<stage>/<service>.conf`
Service-specific configs. These will override all other config files.

[Documentation](https://docs.google.com/document/d/1CSERbLwbu6nT_ggzzYxdUt9IHpfGUJLIYPnU_9MZbpc/edit) on the existing Grid config options 