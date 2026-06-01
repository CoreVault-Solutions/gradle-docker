CoreVault Docker Gradle Plugin
==============================

> **Fork notice:** This project is a Kotlin port and continuation of the
> [Palantir `gradle-docker` plugin](https://github.com/palantir/gradle-docker)
> (Apache 2.0), maintained by CoreVault Solutions since 2025.
> Plugin IDs have moved from `com.palantir.*` to `com.corevaultsolutions.*` and the
> configuration DSL now uses Kotlin-friendly property assignment.

This repository provides three Gradle plugins for working with Docker containers:
- `com.corevaultsolutions.docker`: adds basic tasks for building and pushing
  docker images based on a simple configuration block that specifies the image
  name, the Dockerfile, task dependencies, and any additional file resources
  required for the Docker build.
- `com.corevaultsolutions.docker-compose`: adds a task for populating placeholders in a
  docker-compose template file with image versions resolved from dependencies.
- `com.corevaultsolutions.docker-run`: adds tasks for starting, stopping, statusing and
  cleaning up a named container based on a specified image.

The plugins are compatible with Gradle 8.14+ and Gradle 9.x and are compatible
with the Gradle configuration cache.

Docker Plugin
-------------

Apply the plugin using standard gradle convention:

````gradle
plugins {
    id 'com.corevaultsolutions.docker' version '<version>'
}
````

Set the image name, and then optionally specify a Dockerfile, any task
dependencies and file resources required for the Docker build. This plugin will
automatically include outputs of task dependencies in the Docker build context.

**Docker Configuration Parameters**
- `imageName` the name to use for this image, may include a tag (**required**).
- `tags` (optional) a set of tags to create; defaults to the empty set. A tag for
  the project version (`project.version`) is always created in addition to these.
- `tag` (optional) a tag to create under a specified task name: `tag 'taskName', 'value'`.
- `dockerfile` (optional) the dockerfile to use for building the image; defaults to
  `project.file('Dockerfile')` and must be a `File`.
- `files` (optional) an argument list of files to be included in the Docker build context,
  evaluated per `Project#files`. For example, `files tasks.distTar.outputs` adds the TAR/TGZ
  file produced by the `distTar` task, and `files tasks.distTar.outputs, 'my-file.txt'` adds the
  archive in addition to file `my-file.txt`. The files are collected in a Gradle CopySpec that may
  be copied `into` the Docker build context directory; the underlying `copySpec` may also be used
  to copy entire directories:
````gradle
docker {
    files tasks.distTar.outputs, 'my-file.txt'
    copySpec.from("src/myDir").into("myDir")
}
````
- `buildArgs` (optional) a map of string to string which sets `--build-arg`
  arguments on the docker build command; defaults to empty.
- `labels` (optional) a map of string to string which sets `--label` arguments
  on the docker build command; defaults to empty. Label keys must match `^[a-z0-9.-]+$`.
- `pull` (optional) whether Docker should attempt to pull a newer base image before
  building; defaults to `false`.
- `noCache` (optional) whether the build should add `--no-cache`; defaults to `false`.
- `target` (optional) the multi-stage Docker build target passed as `--target`; defaults to none.
- `network` (optional) the network mode for the build (`--network`); defaults to none.
- `buildx` (optional) whether to use `docker buildx` for cross-platform builds; defaults to `false`.
- `platform` (optional) a set of platforms for buildx to target; defaults to empty.
- `builder` (optional) the buildx builder to use; defaults to `null`.
- `load` (optional) whether buildx should add `--load` (load into the local repository); `false`.
- `push` (optional) whether buildx should add `--push` (push to the remote registry); `false`.
- `secrets` (optional) a list of `--secret` arguments for the build; defaults to empty.

To build a docker image, run the `docker` task. To push it, run `dockerPush`.
Tag and push tasks are generated for each `tags` entry, each `tag`, and the project version.

**Examples**

Simplest configuration:

```gradle
docker {
    imageName = 'hub.docker.com/username/my-app:version'
}
```

Building from a distribution archive:

```gradle
// Assumes that the Gradle "distribution" plugin is applied
docker {
    imageName = 'hub.docker.com/username/my-app:version'
    files tasks.distTar.outputs   // adds resulting *.tgz to the build context
}
```

Configuration specifying many parameters:

```gradle
docker {
    imageName = 'hub.docker.com/username/my-app:version'
    tags = ['latest', 'release']
    tag 'myRegistry', 'my.registry.com/username/my-app:version'
    dockerfile project.file('Dockerfile')
    files tasks.distTar.outputs, 'file1.txt', 'file2.txt'
    buildArgs = [BUILD_VERSION: 'version']
    labels['maintainer'] = 'team@example.com'
    pull = true
    noCache = true
    target = 'runtime'
}
```

> **DSL note (migrating from `com.palantir.docker`):** configuration now uses
> property assignment. `name '...'` → `imageName = '...'`, `tags 'a', 'b'` →
> `tags = ['a', 'b']`, `pull true` → `pull = true`, `labels(['k': 'v'])` →
> `labels['k'] = 'v'`, `buildArgs([...])` → `buildArgs = [...]`. The `tag`,
> `files`, and `dockerfile` settings are unchanged.

Managing Docker image dependencies
----------------------------------
The `com.corevaultsolutions.docker` and `com.corevaultsolutions.docker-compose` plugins provide
functionality to declare and resolve version-aware dependencies between docker
images, primarily to generate `docker-compose.yml` files whose image versions
are mutually compatible.

### Specifying and publishing dependencies on Docker images

The `docker` plugin adds a `docker` Gradle component and a `docker` Gradle
configuration that can be used to specify and publish dependencies on other
Docker containers.

```gradle
plugins {
    id 'maven-publish'
    id 'com.corevaultsolutions.docker'
}

dependencies {
    docker 'foogroup:barmodule:0.1.2'
    docker project(":someSubProject")
}

publishing {
    publications {
        dockerPublication(MavenPublication) {
            from components.docker
            artifactId project.name + "-docker"
        }
    }
}
```

### Generating docker-compose.yml files from dependencies

The `com.corevaultsolutions.docker-compose` plugin uses the transitive dependencies of the
`docker` configuration to populate a `docker-compose.yml.template` file with the
resolved image versions. The `generateDockerCompose` task replaces each
`{{group:name}}` token in the template with the concrete resolved version and
writes the result to `docker-compose.yml`. User-supplied `{{key}}` tokens can be
provided via `templateTokens`.

```gradle
plugins {
    id 'com.corevaultsolutions.docker-compose'
}

dependencies {
    docker 'othergroup:otherservice:0.1.2'
}

dockerCompose {
    // template 'my-template.yml'
    // dockerComposeFile 'my-docker-compose.yml'
    // composeCommand 'docker compose'   // default: 'docker-compose'
    templateTokens(['currentImageName': 'repository/current:1.0.0'])
}
```

Given a template:

```yaml
myservice:
  image: 'repository/myservice:{{mygroup:myservice}}'
```

`generateDockerCompose` resolves `{{mygroup:myservice}}` to the version declared by
the `docker` dependencies. It fails if the template contains tokens that cannot be
resolved. The `dockerComposeUp`/`dockerComposeDown` tasks bring the services up
(detached) and down. The compose CLI defaults to `docker-compose`; set
`composeCommand 'docker compose'` for the Compose v2 plugin.

> **Execution order:** `dockerComposeUp`/`dockerComposeDown` operate directly on
> `dockerComposeFile` and are intentionally **not** wired to depend on
> `generateDockerCompose` — this lets you bring up an existing, hand-written
> compose file. When using the template workflow, run `generateDockerCompose`
> first, e.g. `./gradlew generateDockerCompose dockerComposeUp`.

Docker Run Plugin
-----------------

```gradle
plugins {
    id 'com.corevaultsolutions.docker-run' version '<version>'
}
```

Use the `dockerRun` block to configure the container:

```gradle
dockerRun {
    name = 'my-container'
    image = 'busybox'
    network = 'bridge'
    volumes 'hostvolume': '/containervolume'
    ports '7080:5000'
    env 'MYVAR1': 'MYVALUE1', 'MYVAR2': 'MYVALUE2'
    command 'sleep', '100'
    arguments '--hostname=custom', '-P'
    daemonize = true
    clean = false
}
```

**Docker Run Configuration Parameters**
- `name` the container name (property: `name = '...'`).
- `image` the image to use (property: `image = '...'`).
- `network` (optional) the network mode (property: `network = '...'`).
- `volumes` (optional) map of host path (resolved via `project.file()`) to container path.
- `ports` (optional) `local:container` port mappings.
- `env` (optional) environment variables to supply to the container.
- `command` (optional) the command to run.
- `arguments` (optional) extra arguments passed to `docker run`.
- `daemonize` daemonize the container after starting (property); defaults to `true`.
- `clean` (optional) add `--rm` so the container is removed after running (property); `false`.
- `ignoreExitValue` (optional) ignore the docker command's exit code (property); `false`.

Tasks
-----

 * **Docker**
   * `docker`: build a docker image with the specified name and Dockerfile
   * `dockerTag`: tag the docker image with all specified tags
   * `dockerTag<tag>`: tag the docker image with `<tag>`
   * `dockerPush`: push the specified image to a docker repository
   * `dockerPush<tag>`: push the `<tag>` docker image to a docker repository
   * `dockerTagsPush`: push all tagged docker images to a docker repository
   * `dockerPrepare`: prepare to build a docker image by copying dependent task
     outputs, referenced files, and `dockerfile` into a temporary directory
   * `dockerClean`: remove the temporary directory associated with the docker build
   * `dockerfileZip`: build a ZIP file containing the configured Dockerfile
 * **Docker Compose**
   * `generateDockerCompose`: populate a docker-compose template with image versions
   * `dockerComposeUp`: bring up services defined in `dockerComposeFile` (detached)
   * `dockerComposeDown`: stop services defined in `dockerComposeFile`
 * **Docker Run**
   * `dockerRun`: run the specified image with the specified name
   * `dockerStop`: stop the running container
   * `dockerRunStatus`: report the run status of the container
   * `dockerNetworkModeStatus`: report the container's network mode
   * `dockerRemoveContainer`: remove the container

License
-------
This plugin is made available under the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0).
It is a derivative work of the Palantir `gradle-docker` plugin; see the `NOTICE`
file and per-file headers for attribution.

Contributing
------------
Contributions to this project must follow the [contribution guide](CONTRIBUTING.md).

Releasing
---------

Releases are automated through GitHub Actions. Push a clean Git tag matching
`v*` (for example `v0.38.0`) to trigger the publish workflow, which releases to
both Maven Central and the Gradle Plugin Portal.

The repository must have these GitHub Actions secrets configured:
- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`
- `SIGNING_KEY`
- `SIGNING_PASSWORD`
- `SIGNING_KEY_ID` (optional, only if your signing subkey requires it)
- `GRADLE_PUBLISH_KEY`
- `GRADLE_PUBLISH_SECRET`

The workflow maps the Maven Central and signing secrets to the Gradle
properties expected by the publication plugins. The Plugin Portal credentials
are consumed directly from `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`.

The workflow validates the Plugin Portal publication before upload, then runs
`publishAndReleaseToMavenCentral` and `publishPlugins`. Maven Central typically
takes several minutes to surface newly published artifacts, and Plugin Portal
approval or public visibility can lag behind workflow completion.
