# Web Streaming MVP

This repository contains:

- a browser-based Janus VideoRoom MVP in `index.html` and `app.js`
- a Java viewer client in `java-client/`

## GitHub Actions CI

The repository includes a GitHub Actions workflow at `.github/workflows/ci.yml`.

It runs automatically on:

- every push to `main`
- every pull request
- manual runs from the GitHub Actions tab

The workflow does two things:

- checks that the web entry files exist and that `app.js` has valid JavaScript syntax
- builds the Maven project in `java-client/` with Java 17

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

## Local CI Command

You can run the same Java build locally with:

```bash
cd /home/daniela/web-streaming/java-client
mvn -DskipTests package
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

### Why These Containers

This repository is not a microservices backend, but it does have multiple runnable parts:

- Janus Gateway
- a browser client
- a Java client

Containerizing them separately keeps each runtime simple and easier to start.
