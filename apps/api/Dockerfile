FROM python:3.14-slim
COPY --from=ghcr.io/astral-sh/uv:0.13.7 /uv /uvx /bin/
WORKDIR /app
ENV UV_PROJECT_ENVIRONMENT=/opt/venv UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock ./
COPY apps/api/pyproject.toml ./apps/api/pyproject.toml
COPY apps/worker/pyproject.toml ./apps/worker/pyproject.toml
COPY apps/worker/src ./apps/worker/src
RUN uv sync --all-packages --frozen --no-dev --no-editable
COPY apps/api/app ./apps/api/app
ENV PATH="/opt/venv/bin:$PATH" CAMP_DATA_MODE=demo
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--app-dir", "apps/api", "--host", "0.0.0.0", "--port", "8000"]
