from core.council import CouncilEngine


class DemoCouncil:
    """Compatibility wrapper for the existing UI."""

    def __init__(self):
        self.engine = CouncilEngine()

    def run(self, prompt: str, rounds: int = 2):
        yield {"name": "Система", "text": "Запускаю реальный запрос через OpenRouter…"}
        try:
            messages = self.engine.run_openrouter(prompt, rounds)
            for message in messages:
                yield {"name": message.author, "text": message.text}
        except Exception as exc:
            yield {
                "name": "Система",
                "text": f"Не удалось получить ответ от OpenRouter: {exc}",
            }
