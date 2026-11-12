FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOST=0.0.0.0 \
    HF_HOME=/home/app/.cache/huggingface

WORKDIR /app

COPY requirements.txt .
# Compose runs this service on CPU; avoid downloading CUDA dependencies.
RUN python -m pip install --no-cache-dir --timeout 120 --retries 10 \
        torch --index-url https://download.pytorch.org/whl/cpu \
    && python -m pip install --no-cache-dir --timeout 120 --retries 10 -r requirements.txt

RUN useradd --create-home --uid 10001 app \
    && mkdir -p /home/app/.cache/huggingface \
    && chown -R app:app /home/app/.cache

COPY server.py .

USER app
EXPOSE 8000

CMD ["python", "server.py"]
