FROM postgis/postgis:18-3.6

# Keep PostGIS and add vector search support for the same PostgreSQL major version.
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-18-pgvector \
    && rm -rf /var/lib/apt/lists/*
