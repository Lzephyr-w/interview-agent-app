import json
import time
from uuid import uuid4
from types import SimpleNamespace

import pytest

from interview_agent.simulation import SimulationError, generate, validate_request


def request(operation="TEXT_MAIN_QUESTION", marker="甲"):
    data = {"materials": {"company": marker, "role": "开发", "round": "一面", "jd": marker,
                          "resume": marker, "cards": []}, "history": []}
    if operation in {"VOICE_FEEDBACK", "TEXT_FEEDBACK", "TEXT_FOLLOW_UP"}:
        data.update(questionText="如何验证？", answer="测试")
    if operation == "VOICE_QUESTION":
        data["slot"] = dict(order=1, type="FUNDAMENTAL", competency="原理", projectName="", technology="", angle="机制")
    return dict(version="simulation.v1", requestId=str(uuid4()), operation=operation,
                deadlineAtEpochMs=int(time.time() * 1000) + 70000, input=data)


class Model:
    def __init__(self, *values):
        self.values, self.prompts = iter(values), []

    def invoke(self, messages):
        self.prompts.append(str(messages))
        result = next(self.values)
        if isinstance(result, Exception):
            raise result
        return SimpleNamespace(content=result)

    async def ainvoke(self, messages):
        return self.invoke(messages)


@pytest.mark.parametrize("operation", ["VOICE_PLAN", "VOICE_QUESTION", "VOICE_FEEDBACK", "TEXT_MAIN_QUESTION", "TEXT_FOLLOW_UP", "TEXT_FEEDBACK"])
def test_operations(operation):
    payload = request(operation)
    value = {"feedback": "请补充证据。"} if "FEEDBACK" in operation else {"questionText": "如何验证？"}
    if operation == "VOICE_PLAN":
        value = {"plan": [dict(order=i, type="FUNDAMENTAL" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO",
                               competency=f"能力{i}", projectName="", technology="", angle=f"角度{i}") for i in range(1, 11)]}
    if operation == "VOICE_QUESTION":
        value.update(type="FUNDAMENTAL", competency="原理", projectName="", technology="")
    model = Model(json.dumps(value))
    result = generate(payload, lambda remaining: model)
    assert result == {"version": "simulation.v1", "requestId": payload["requestId"], "result": value}
    assert len(model.prompts) == 1


def test_format_retry_only_once_and_isolation():
    model = Model("not json", '{"questionText":"如何验证？"}', '{"questionText":"如何排查？"}')
    generate(request(marker="用户甲独有"), lambda remaining: model)
    generate(request(marker="用户乙独有"), lambda remaining: model)
    assert len(model.prompts) == 3
    assert "用户乙独有" not in model.prompts[0]
    assert "用户甲独有" not in model.prompts[2]
    with pytest.raises(SimulationError) as failure:
        generate(request(), lambda remaining: Model("{}", "{}"))
    assert failure.value.code == "INVALID_MODEL_OUTPUT"


@pytest.mark.parametrize("field,value", [("version", "v2"), ("requestId", "bad"), ("operation", "PROMPT"), ("deadlineAtEpochMs", True)])
def test_invalid_envelope(field, value):
    payload = request()
    payload[field] = value
    with pytest.raises(SimulationError):
        validate_request(payload)


def test_rejects_arbitrary_prompt_ids_and_bad_types():
    for field in ("prompt", "userId", "packageId"):
        payload = request()
        payload["input"][field] = "arbitrary"
        with pytest.raises(SimulationError):
            validate_request(payload)
    payload = request()
    payload["input"]["materials"]["jd"] = 123
    with pytest.raises(SimulationError):
        validate_request(payload)


def test_deadline_and_provider_failures_do_not_retry():
    payload = request()
    payload["deadlineAtEpochMs"] = int(time.time() * 1000) - 1
    with pytest.raises(SimulationError) as failure:
        generate(payload, lambda remaining: pytest.fail("expired request called model"))
    assert failure.value.code == "MODEL_TIMEOUT"
    for error, code in [(TimeoutError("private"), "MODEL_TIMEOUT"), (RuntimeError("secret"), "MODEL_UNAVAILABLE")]:
        model = Model(error)
        with pytest.raises(SimulationError) as failure:
            generate(request(), lambda remaining: model)
        assert failure.value.code == code
        assert len(model.prompts) == 1
        assert "secret" not in str(failure.value)


def test_http_contract_auth_browser_denial_and_no_chat_tools(monkeypatch):
    from http.server import ThreadingHTTPServer
    from threading import Thread
    from urllib.request import Request, urlopen
    from urllib.error import HTTPError
    from interview_agent.server import Handler, JavaToolClient

    monkeypatch.setattr(JavaToolClient, "call", lambda *args: pytest.fail("simulation called Java tools"))
    class TestHandler(Handler):
        internal_key = "internal-test"
        runtime_factory = staticmethod(lambda *args: pytest.fail("simulation used chat runtime"))
        simulation_model_factory = staticmethod(lambda remaining: Model('{"questionText":"如何验证？"}'))

    server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        for headers, code in [({}, "UNAUTHORIZED"), ({"X-Agent-Key": "wrong"}, "UNAUTHORIZED"),
                              ({"X-Agent-Key": "internal-test", "Origin": "http://localhost:3000"}, "UNAUTHORIZED"),
                              ({"X-Agent-Key": "internal-test"}, None)]:
            payload = request()
            req = Request(f"http://127.0.0.1:{server.server_port}/v1/agent/simulations",
                          data=json.dumps(payload).encode(), headers=headers)
            try:
                response = urlopen(req, timeout=2)
            except HTTPError as error:
                response = error
            with response:
                body = json.load(response)
            assert body["requestId"] == payload["requestId"]
            assert body["version"] == "simulation.v1"
            if code:
                assert set(body["error"]) == {"code", "message", "retryable"}
                assert body["error"]["code"] == code
                assert body["error"]["retryable"] is False
            else:
                assert body["result"]["questionText"] == "如何验证？"
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def test_async_model_is_cancelled_at_request_deadline():
    import asyncio
    class Slow:
        async def ainvoke(self, messages):
            await asyncio.sleep(10)
    payload = request()
    payload["deadlineAtEpochMs"] = int(time.time()*1000)+60
    start = time.monotonic()
    with pytest.raises(SimulationError) as error:
        generate(payload, lambda remaining: Slow())
    assert error.value.code == "MODEL_TIMEOUT"
    assert time.monotonic()-start < 1


@pytest.mark.parametrize("operation", ["VOICE_PLAN", "VOICE_QUESTION", "VOICE_FEEDBACK", "TEXT_MAIN_QUESTION", "TEXT_FOLLOW_UP", "TEXT_FEEDBACK"])
def test_each_operation_rejects_wrong_result_schema(operation):
    model = Model('{"wrong":123}', '{"wrong":123}')
    with pytest.raises(SimulationError) as failure:
        generate(request(operation), lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    assert len(model.prompts) == 2


def test_lengths_and_missing_fields_are_rejected():
    payload = request()
    payload["input"]["materials"]["jd"] = "长"*8001
    with pytest.raises(SimulationError):
        validate_request(payload)
    payload = request("TEXT_FEEDBACK")
    del payload["input"]["answer"]
    with pytest.raises(SimulationError):
        validate_request(payload)
    model = Model(json.dumps({"questionText": "长"*801}), json.dumps({"questionText": "长"*801}))
    with pytest.raises(SimulationError) as failure:
        generate(request(), lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
