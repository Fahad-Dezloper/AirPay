FROM python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir "pynacl>=1.5" && useradd -u 10001 -m payer && mkdir /data && chown payer /data
COPY skrpayer ./skrpayer
# The key and the shared secret are mounted read-only at run time, never baked into the image.
USER payer
ENV PAYER_DB=/data/payer.db PORT=8788 PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1
VOLUME /data
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8788/healthz', timeout=4)"
CMD ["python", "-m", "skrpayer"]
