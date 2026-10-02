import json
import time
from uuid import uuid4
from types import SimpleNamespace

import pytest

from interview_agent.simulation import SimulationError, generate, validate_request


def request(operation="TEXT_MAIN_QUESTION", marker="甲"):
    data = {"materials": {"company": marker, "role": "开发", "round": "一面", "jd": marker,
                          "resume": marker + "项目", "cards": []}, "history": []}
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
                               competency=f"能力{i}", projectName="甲项目" if 6 <= i <= 9 else "", technology="", angle=f"角度{i}") for i in range(1, 11)],
                 "firstQuestion": {"questionText": "如何验证？", "type": "FUNDAMENTAL", "competency": "能力1", "projectName": "", "technology": ""}}
    if operation == "VOICE_QUESTION":
        value.update(type="FUNDAMENTAL", competency="原理", projectName="", technology="")
    model = Model(json.dumps(value))
    result = generate(payload, lambda remaining: model)
    assert result == {"version": "simulation.v1", "requestId": payload["requestId"], "result": value}
    assert len(model.prompts) == 1


def test_knowledge_extends_text_and_voice_contract_and_prompt():
    payload = request()
    payload["input"]["knowledge"] = "来源：React.md；片段：useEffect 处理副作用。"
    model = Model('{"questionText":"useEffect 如何处理副作用？"}')
    assert generate(payload, lambda remaining: model)["result"]["questionText"] == "useEffect 如何处理副作用？"
    assert "必须紧扣input.knowledge" in model.prompts[0]
    for operation in ("VOICE_PLAN", "VOICE_QUESTION", "VOICE_FEEDBACK"):
        payload = request(operation)
        payload["input"]["knowledge"] = "来源：React.md；片段：useEffect 处理副作用。"
        validate_request(payload)
        payload["input"]["knowledge"] = "x" * 6001
        with pytest.raises(SimulationError) as failure:
            validate_request(payload)
        assert failure.value.code == "INVALID_REQUEST"
    payload = request("VOICE_QUESTION")
    payload["input"]["knowledge"] = "来源：React.md；片段：组件状态更新。"
    voice_model = Model(json.dumps({"questionText": "组件状态如何更新？", "type": "FUNDAMENTAL", "competency": "原理", "projectName": "", "technology": ""}))
    generate(payload, lambda remaining: voice_model)
    assert "必须紧扣input.knowledge" in voice_model.prompts[0]


def test_invalid_structure_returns_retryable_error_after_one_call():
    model = Model("not json", '{"questionText":"如何验证？"}')
    with pytest.raises(SimulationError) as failure:
        generate(request(marker="用户甲独有"), lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    assert failure.value.retryable is True
    assert len(model.prompts) == 1


def test_plan_only_returns_three_focuses_per_slot_without_a_first_question():
    payload = request("VOICE_PLAN_ONLY", marker="真实项目")
    value = {"plan": [dict(order=i, type="FUNDAMENTAL" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO",
                           competency=f"能力{i}", projectName="真实项目" if 6 <= i <= 9 else "", technology=f"技术{i}", angle=f"角度{i}",
                           alternatives=[dict(competency=f"能力{i}切入{j}", angle=f"角度{i}切入{j}") for j in (1, 2)]) for i in range(1, 11)]}
    model = Model(json.dumps(value, ensure_ascii=False))
    assert generate(payload, lambda remaining: model)["result"] == value
    assert "不生成任何题目正文或首题" in model.prompts[0]
    for invalid in (dict(value, firstQuestion={}), {"plan": value["plan"][:9]},
                    {"plan": [dict(item, competency="重复能力") for item in value["plan"]]}):
        with pytest.raises(SimulationError) as failure:
            generate(payload, lambda remaining: Model(json.dumps(invalid)))
        assert failure.value.code == "INVALID_MODEL_OUTPUT"


@pytest.mark.parametrize("shape", ["array", "separate_objects", "missing_options", "bad_options"])
def test_plan_representation_repair_keeps_ten_grounded_slots(shape):
    payload = request("VOICE_PLAN_ONLY", marker="真实项目")
    plan = [dict(order=str(i), type="fundamental" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO",
                 competency=f"能力{i}", projectName="真实项目" if 6 <= i <= 9 else "", angle=f"角度{i}") for i in range(1, 11)]
    if shape == "bad_options":
        for item in plan:
            item["alternatives"] = [dict(competency="能力1", angle="机制"), dict(competency="?", angle="?")]
    content = json.dumps(plan) if shape == "array" else ",\n".join(json.dumps(item) for item in plan) if shape == "separate_objects" else json.dumps({"plan": plan})
    model = Model(content)
    result = generate(payload, lambda remaining: model)["result"]["plan"]
    assert len(result) == 10 and [item["order"] for item in result] == list(range(1, 11))
    assert all(item["alternatives"] == [] and item["technology"] == "" for item in result)
    assert all(item["projectName"] == "真实项目" for item in result[5:9])
    assert len(model.prompts) == 1


def test_plan_simplified_retry_prompt_and_ambiguous_json_stay_bounded():
    payload = request("VOICE_PLAN_ONLY")
    payload["input"]["focusCount"] = 1
    plan = [dict(order=i, type="FUNDAMENTAL" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO",
                 competency=f"能力{i}", projectName="甲项目" if 6 <= i <= 9 else "", technology="", angle=f"角度{i}", alternatives=[]) for i in range(1, 11)]
    model = Model(json.dumps({"plan": plan}))
    assert generate(payload, lambda remaining: model)["result"]["plan"] == plan
    assert "本次优先完成十题基础计划" in model.prompts[0] and "省略号" in model.prompts[0]
    for content in (json.dumps({"plan": plan}) * 2, json.dumps({"plan": plan})[:-5], json.dumps({"plan": [dict(item, projectName="虚构项目") for item in plan]})):
        with pytest.raises(SimulationError) as failure:
            generate(payload, lambda remaining: Model(content))
        assert failure.value.code == "INVALID_MODEL_OUTPUT"
    payload["input"]["focusCount"] = True
    with pytest.raises(SimulationError) as failure:
        validate_request(payload)
    assert failure.value.code == "INVALID_REQUEST"


def test_ungrounded_plan_project_name_is_rejected_without_second_call():
    payload = request("VOICE_PLAN", marker="真实项目")
    invalid = {"plan": [dict(order=i, type="FUNDAMENTAL" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO",
                             competency=f"能力{i}", projectName="虚构项目" if i == 6 else "", technology="", angle=f"角度{i}") for i in range(1, 11)],
               "firstQuestion": {"questionText": "如何验证？", "type": "FUNDAMENTAL", "competency": "能力1", "projectName": "", "technology": ""}}
    model = Model(json.dumps(invalid))
    with pytest.raises(SimulationError) as failure:
        generate(payload, lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    assert len(model.prompts) == 1


def test_resume_experience_anchor_is_allowed_without_evidence_card():
    payload = request("VOICE_PLAN", marker="汇量科技")
    payload["input"]["materials"]["experienceAnchors"] = ["汇量科技", "中康科技"]
    plan = {"plan": [dict(order=i, type="FUNDAMENTAL" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO",
                           competency=f"能力{i}", projectName="汇量科技" if 6 <= i <= 9 else "", technology="", angle=f"角度{i}") for i in range(1, 11)],
            "firstQuestion": {"questionText": "如何验证？", "type": "FUNDAMENTAL", "competency": "能力1", "projectName": "", "technology": ""}}
    result = generate(payload, lambda remaining: Model(json.dumps(plan)))
    assert result["result"] == plan


def test_model_markdown_json_is_safely_parsed():
    response = "说明：\n```json\n{\"questionText\":\"如何验证缓存策略？\"}\n```\n"
    assert generate(request(), lambda remaining: Model(response))["result"]["questionText"] == "如何验证缓存策略？"


def test_operation_prompts_restore_quality_rules():
    prompts = []
    for operation in ("VOICE_PLAN", "VOICE_QUESTION", "TEXT_MAIN_QUESTION", "TEXT_FOLLOW_UP", "TEXT_FEEDBACK", "VOICE_FEEDBACK"):
        value = {"feedback": "请补充证据。"} if "FEEDBACK" in operation else {"questionText": "如何验证？"}
        if operation == "VOICE_PLAN":
            value = {"plan": [dict(order=i, type="FUNDAMENTAL" if i <= 5 else "PROJECT" if i <= 9 else "SCENARIO", competency=f"能力{i}", projectName="甲项目" if 6 <= i <= 9 else "", technology="", angle=f"角度{i}") for i in range(1, 11)],
                     "firstQuestion": {"questionText": "如何验证？", "type": "FUNDAMENTAL", "competency": "能力1", "projectName": "", "technology": ""}}
        if operation == "VOICE_QUESTION":
            value.update(type="FUNDAMENTAL", competency="原理", projectName="", technology="")
        model = Model(json.dumps(value))
        generate(request(operation), lambda remaining: model)
        prompts.append(model.prompts[0])
    assert "一次返回计划和第一题" in prompts[0] and "资料优先级" in prompts[0]
    assert "只出一道中文问题" in prompts[1] and "可独立回答" in prompts[1]
    assert "同一能力点" in prompts[2]
    assert "具体、可回答" in prompts[3]
    assert "简短、具体、可执行" in prompts[4]
    assert "不得推断语速、停顿、重复词或情绪" in prompts[5]


def test_ungrounded_voice_question_project_is_not_normalized():
    model = Model(json.dumps({"questionText": "请说明虚构项目。", "type": "FUNDAMENTAL", "competency": "原理", "projectName": "虚构项目", "technology": ""}))
    with pytest.raises(SimulationError) as failure:
        generate(request("VOICE_QUESTION"), lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    assert len(model.prompts) == 1


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


def test_timeout_log_identifies_the_model_attempt(caplog):
    with pytest.raises(SimulationError):
        generate(request("VOICE_PLAN"), lambda remaining: Model(TimeoutError()))
    assert "operation=VOICE_PLAN" in caplog.text
    assert "attempt=1" in caplog.text


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
    model = Model('{"wrong":123}')
    with pytest.raises(SimulationError) as failure:
        generate(request(operation), lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    assert len(model.prompts) == 1


def test_lengths_and_missing_fields_are_rejected():
    payload = request()
    payload["input"]["materials"]["jd"] = "长"*8001
    with pytest.raises(SimulationError):
        validate_request(payload)
    payload = request("TEXT_FEEDBACK")
    del payload["input"]["answer"]
    with pytest.raises(SimulationError):
        validate_request(payload)
    model = Model(json.dumps({"questionText": "长"*801}))
    with pytest.raises(SimulationError) as failure:
        generate(request(), lambda remaining: model)
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
    assert len(model.prompts) == 1


@pytest.mark.parametrize("operation", ["VOICE_QUESTION", "TEXT_MAIN_QUESTION", "TEXT_FOLLOW_UP"])
def test_question_quality_matches_java_contract(operation):
    value = {"questionText": "长" * 201}
    if operation == "VOICE_QUESTION":
        value.update(type="FUNDAMENTAL", competency="原理", projectName="", technology="")
    with pytest.raises(SimulationError) as failure:
        generate(request(operation), lambda remaining: Model(json.dumps(value)))
    assert failure.value.code == "INVALID_MODEL_OUTPUT"

    value["questionText"] = "如何验证？再说明？"
    if operation == "VOICE_QUESTION":
        assert generate(request(operation), lambda remaining: Model(json.dumps(value)))["result"]["questionText"] == "如何验证？"
        return
    with pytest.raises(SimulationError) as failure:
        generate(request(operation), lambda remaining: Model(json.dumps(value)))
    assert failure.value.code == "INVALID_MODEL_OUTPUT"


def test_voice_question_repair_preserves_context_and_metadata_without_another_model_call():
    payload = request("VOICE_QUESTION")
    value = dict(questionText="浏览器执行完同步任务后，微任务何时执行？请再举一个例子？",
                 type="FUNDAMENTAL", competency="原理", projectName="", technology="")
    model = Model(json.dumps(value))
    result = generate(payload, lambda remaining: model)["result"]
    assert result == dict(value, questionText="浏览器执行完同步任务后，微任务何时执行？")
    assert len(model.prompts) == 1
    for invalid in (dict(value, questionText='代码里的 "?" 如何解释？还可以举例？'),
                    dict(value, questionText="长" * 201 + "？再说明？"),
                    dict(value, questionText="（这段背景如何理解？继续说明）？"),
                    dict(value, projectName="虚构项目")):
        with pytest.raises(SimulationError) as failure:
            generate(payload, lambda remaining: Model(json.dumps(invalid)))
        assert failure.value.code == "INVALID_MODEL_OUTPUT"


@pytest.mark.parametrize("operation", ["VOICE_FEEDBACK", "TEXT_FEEDBACK"])
def test_feedback_sentence_limit_matches_java_contract(operation):
    with pytest.raises(SimulationError) as failure:
        generate(request(operation), lambda remaining: Model(json.dumps({"feedback": "第一句。第二句。第三句。"})))
    assert failure.value.code == "INVALID_MODEL_OUTPUT"
