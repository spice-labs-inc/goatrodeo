# GitHub Actions Workflows

This folder contains GitHub Actions workflows.

**`build_test.yml` uses Maven to run a build & test job**
- This automatically triggers on the following events:
    1. Push to any branch (`**`)
    2. Pull requests to the `main` branch

**`publish.yml` publishes container images to [Docker Hub](https://hub.docker.com/u/spicelabs) and JARs to GitHub Packages (and, via a separate Maven Central job, to Maven Central)**
- The image includes provenance attestations and a software bill of materials (SBOM)
- This workflow triggers automatically on:
    1. Semantic version tags pushed to the repository (e.g. `v1.2.3`)
    2. Manual dispatch via the GitHub Actions UI

