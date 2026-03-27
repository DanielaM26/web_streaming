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
