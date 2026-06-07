# Web Streaming MVP

This repository contains:

- a browser-based Janus VideoRoom MVP in `index.html` and `app.js`
- a Java viewer client in `java-client/`

## GitHub Actions CI

The repository includes a GitHub Actions workflow at `.github/workflows/ci.yml`.

It runs automatically on:

- every push to `main`
- every push to `develop`
- every pull request
- manual runs from the GitHub Actions tab

The workflow currently includes these jobs:

- `Web checks`
- `Java client build`
- `Java Checkstyle`
- `Java SpotBugs`
- `Terraform checks`
- `Secret scan`
- `Docker checks`

What CI verifies:

- frontend entry files exist
- `app.js` has valid JavaScript syntax
- `index.html` and `app.js` are wired correctly
- the Java client builds with Java 17
- Checkstyle rules pass for the Java code
- SpotBugs finds no medium-or-higher bug patterns in the Java code
- Terraform files are formatted and valid
- Gitleaks does not detect committed secrets
- Docker Compose configuration is valid
- the web Docker image builds and responds over HTTP

## Local Quality Checks

Frontend:

```bash
cd /home/daniela/web-streaming
node --check app.js
node scripts/check-web-smoke.mjs
```

Java:

```bash
cd /home/daniela/web-streaming/java-client
mvn -DskipTests package
mvn org.apache.maven.plugins:maven-checkstyle-plugin:3.4.0:check
mvn com.github.spotbugs:spotbugs-maven-plugin:4.8.6.2:check
```

Docker:

```bash
cd /home/daniela/web-streaming
docker compose config
docker build -f Dockerfile.web -t web-streaming-web .
```

## Branch Workflow

Recommended workflow:

- `main` is the stable branch
- `develop` is the working branch for changes and testing
- open pull requests from `develop` into `main`

Typical flow:

```bash
cd /home/daniela/web-streaming
git checkout develop
git add .
git commit -m "Describe your change"
git push origin develop
```

Then create a pull request:

```text
develop -> main
```

If you enable GitHub branch protection for `main`, you can require the CI jobs to pass before merge.

## Put This Project On GitHub

If you have not created the GitHub repository yet:

1. Create an empty repository on GitHub, for example `web-streaming`.
2. In this local project, add the remote:

```bash
cd /home/daniela/web-streaming
git remote add origin https://github.com/<your-user>/<your-repo>.git
```

3. Push the `main` branch:

```bash
git push -u origin main
```

After that, GitHub Actions will start running CI automatically on new pushes.

## Typical First Push

If you want to commit the workflow files before pushing:

```bash
cd /home/daniela/web-streaming
git add .github/workflows/ci.yml README.md
git commit -m "Add GitHub Actions CI"
git push -u origin main
```

## Docker

This project can be split into three containers:

- a `janus` container that runs Janus Gateway
- a `web` container that serves `index.html` and `app.js` with Nginx
- a `java-viewer` container that runs the Java Janus viewer

Files added for this:

- `Dockerfile.web`
- `java-client/Dockerfile`
- `docker-compose.yml`

### Start the Whole Stack

```bash
cd /home/daniela/web-streaming
docker compose up --build
```

This starts:

- Janus on `ws://localhost:8188`
- the web UI on `http://localhost:8080`

The browser app already defaults to `ws://localhost:8188`, so it can talk to the Janus container without extra changes.

### Start Only the Web UI and Janus

```bash
cd /home/daniela/web-streaming
docker compose up --build janus web
```

Then open:

```text
http://localhost:8080
```

### Start the Java Viewer Container

The Java viewer needs a mapped feed id and access to Janus.

```bash
cd /home/daniela/web-streaming
JANUS_FEED_ID=123 docker compose --profile viewer up --build java-viewer
```

By default the Java container connects to the Janus service over the Docker network:

```text
ws://janus:8188
```

### Janus Container Details

The Janus service uses `swmansion/janus-gateway:0.11.8-0` and exposes:

- `8188` for WebSocket signaling
- `10000-10099/udp` for RTP media

Environment variables you can override:

- `JANUS_GATEWAY_IP`
- `JANUS_STUN_SERVER`
- `JANUS_STUN_PORT`
- `JANUS_RTP_PORT_RANGE`

Example:

```bash
cd /home/daniela/web-streaming
JANUS_GATEWAY_IP=192.168.1.10 docker compose up --build
```

Use your real machine IP for `JANUS_GATEWAY_IP` if clients outside the container need stable ICE candidates.

### Quick Accessibility Test

To verify that the web app is reachable from a container:

```bash
cd /home/daniela/web-streaming
docker build -f Dockerfile.web -t web-streaming-web-test .
docker run --rm -p 8090:80 web-streaming-web-test
```

Then open:

```text
http://localhost:8090
```

You can also test the response from the terminal:

```bash
curl -I http://localhost:8090
```

### Why These Containers

This repository is not a microservices backend, but it does have multiple runnable parts:

- Janus Gateway
- a browser client
- a Java client

Containerizing them separately keeps each runtime simple and easier to start.

## Optional Local Monitoring

The project includes a minimal local monitoring setup for dissertation/demo use.
It is optional and does not change the normal application startup.

Components:

- Prometheus for collecting metrics
- Grafana for visualizing metrics

Start only monitoring:

```bash
cd /home/daniela/web_streaming
docker compose --profile monitoring up --build prometheus grafana
```

Start the application and monitoring together:

```bash
cd /home/daniela/web_streaming
docker compose --profile monitoring up --build
```

Open:

```text
Web UI:     http://localhost:8080
Grafana:    http://localhost:13000
Prometheus: http://localhost:19090
```

Grafana credentials:

```text
admin / admin
```

Grafana is provisioned automatically with a Prometheus datasource and a `Web Streaming Monitoring` dashboard.
The monitoring ports can be overridden if needed:

```bash
GRAFANA_PORT=3000 PROMETHEUS_PORT=9090 docker compose --profile monitoring up --build prometheus grafana
```
