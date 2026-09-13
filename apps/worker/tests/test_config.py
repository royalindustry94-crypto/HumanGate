from worker.core.config import WorkerSettings, get_settings


def test_settings_load_from_environment() -> None:
    settings = get_settings()
    assert settings.service_name == "content-orchestrator-worker"
    assert settings.health_check_interval_seconds > 0


def test_database_url_is_optional() -> None:
    settings = WorkerSettings(api_base_url="http://api:8000")
    assert settings.database_url is None
    assert settings.api_base_url == "http://api:8000"
