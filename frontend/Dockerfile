# Szablon dla React + TypeScript + Vite (npm, wynik budowania w dist/).
# Wymaga dodania aplikacji oraz package.json i package-lock.json.
FROM node:24-alpine AS build
WORKDIR /app

# Osobna warstwa pozwala zachować zależności w cache po zmianie źródeł.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM nginx/nginx-unprivileged:stable-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/

EXPOSE 8080
CMD ["nginx", "-g", "daemon off;"]
