# Imagen del frontend para Railway: compila Angular y lo sirve con Caddy, que reenvía /api al backend.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx ng build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/dist/frontend/browser /srv
