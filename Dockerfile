# syntax=docker/dockerfile:1
# Construction multi-étapes de Reveil : API Java (cible "api") et interface Angular + nginx (cible "web").

# ---------- API : build Maven ----------
FROM maven:3.9-eclipse-temurin-17 AS api-build
WORKDIR /src
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
# Les tests (MongoDB embarqué) se lancent hors image : mvn test dans backend/.
RUN mvn -B -q -DskipTests package && cp target/reveil-backend-*.jar /app.jar

# ---------- API : exécution ----------
FROM eclipse-temurin:17-jre AS api
RUN useradd --system --uid 10001 reveil
COPY --from=api-build /app.jar /app/app.jar
USER reveil
ENV PORT=8081
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ---------- Web : build Angular ----------
FROM node:22-alpine AS web-build
ARG BASE_HREF=/
WORKDIR /src
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npx ng build --configuration production --base-href "${BASE_HREF}"

# ---------- Web : nginx (sert l'UI et relaie /api vers l'API) ----------
FROM nginxinc/nginx-unprivileged:1.27-alpine AS web
COPY web/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=web-build /src/dist/web/browser /usr/share/nginx/html
EXPOSE 8080
