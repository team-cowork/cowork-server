FROM python:3.13.11-slim-bookworm@sha256:20080e807bfc404f8450b185cf0fc95d553462673598549613735f70a5b4d5d0
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_DISABLE_PIP_VERSION_CHECK=1
RUN pip install --no-cache-dir --only-binary=:all: confluent-kafka==2.16.0 \
    && groupadd --gid 1000 cowork \
    && useradd --uid 1000 --gid 1000 --no-create-home cowork
WORKDIR /app
COPY --chown=1000:1000 deploy/runtime/kafka/initialize.py /app/initialize.py
USER 1000:1000
ENTRYPOINT ["python", "/app/initialize.py"]
