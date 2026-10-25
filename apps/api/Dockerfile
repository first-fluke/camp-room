FROM python:3.13-slim
WORKDIR /app
COPY apps/api/pyproject.toml ./pyproject.toml
RUN pip install --no-cache-dir fastapi uvicorn httpx python-dotenv
COPY apps/api/app ./app
ENV CAMP_DATA_MODE=demo CAMP_DB_PATH=/data/camp-room.sqlite3
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
