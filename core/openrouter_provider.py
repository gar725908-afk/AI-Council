import os
from typing import Iterable
from dotenv import load_dotenv
from openai import OpenAI


class OpenRouterProvider:
    """Optional API gateway. It is NOT a Council participant."""

    def __init__(self) -> None:
        load_dotenv()
        key = os.getenv("OPENROUTER_API_KEY", "").strip()
        if not key:
            raise RuntimeError("OPENROUTER_API_KEY не найден в .env")
        self.client = OpenAI(api_key=key, base_url="https://openrouter.ai/api/v1")
        self._free_models_cache: list[str] = []

    def free_models(self, refresh: bool = False) -> list[str]:
        if self._free_models_cache and not refresh:
            return list(self._free_models_cache)
        models = self.client.models.list().data
        self._free_models_cache = [m.id for m in models if m.id.endswith(":free")]
        return list(self._free_models_cache)

    def ask(self, messages: list[dict], model: str | None = None, candidates: Iterable[str] | None = None) -> tuple[str, str]:
        models = [model] if model else list(candidates or self.free_models())
        if not models:
            raise RuntimeError("OpenRouter не вернул бесплатных моделей.")
        last = None
        for model_id in models:
            try:
                response = self.client.chat.completions.create(model=model_id, messages=messages)
                return (response.choices[0].message.content or "").strip(), model_id
            except Exception as exc:
                if getattr(exc, "status_code", None) == 429:
                    last = exc
                    continue
                raise
        raise RuntimeError("Доступные бесплатные модели OpenRouter сейчас ограничены.") from last
