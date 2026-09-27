FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    FOXDROID_LIBRARY_PATH=/library \
    FOXDROID_DATA_PATH=/data \
    FOXDROID_HOST=0.0.0.0 \
    FOXDROID_PORT=8080

WORKDIR /app

RUN addgroup --system foxdroid && adduser --system --ingroup foxdroid foxdroid

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir . && \
    mkdir -p /library /data && \
    chown -R foxdroid:foxdroid /app /data

USER foxdroid

EXPOSE 8080
VOLUME ["/library", "/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/api/v1/health', timeout=2)"

CMD ["foxdroid-server"]

