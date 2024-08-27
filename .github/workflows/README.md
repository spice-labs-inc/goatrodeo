# Github Actions Workflows

This folder contains Github Actions workflows

- `scala-ci.yml` uses sbt to run a build & test job 
  - This automatically triggers on the following events:
    1. Push to any branch (`**`)
    2. Pull requests to `main` branch
- `scala_container_publishing.yml` publishes container images to docker hub and ghcr (github container repo) with attestations
  - This will automatically trigger on the following events:
    1. Push to the 'main' branch (which should only happen with a merge)
    2. Setting of tags on `main` with semver tags 
    3. The Scala Build & Test (`scala-ci.yml`) job runs successfully
