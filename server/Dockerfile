FROM python:3.12-slim
WORKDIR /app
# google-auth is only needed for push; the server runs without it.
RUN pip install --no-cache-dir "google-auth[requests]>=2.30"
COPY duffleserver ./duffleserver
ENV DUFFLE_DB=/data/duffle.db PORT=8787 PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
VOLUME /data
EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/healthz', timeout=4)"
CMD ["python", "-m", "duffleserver"]
