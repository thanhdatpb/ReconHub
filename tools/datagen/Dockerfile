# Writes sample orders, partner files and ground truth into /data once (skips when /data/.seeded exists).
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY generate.py eval_matching.py bank_statement.py ./
RUN mkdir -p /data && chown 1000:1000 /data
USER 1000:1000
ENTRYPOINT ["python", "generate.py"]
CMD ["--out", "/data"]
