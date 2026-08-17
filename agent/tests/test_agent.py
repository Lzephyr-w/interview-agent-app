import json

import pytest

from interview_agent.agent import AgentError, AgentRuntime


class FakeTools:
    def __init__(self, result=None):
        self.calls = []
        self.result = result or {"status": "created"}

    def call(self, name, arguments):
        self.calls.append((name, arguments))
        return self.result


class FakeModel:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def complete(self, messages, tools):
        self.calls.append((messages, tools))
        return self.responses.pop(0)


def test_tool_call_is_whitelisted_and_passes_authorized_gateway():
    tools = FakeTools({"items": [{"id": "owned"}]})
    model = FakeModel({"content": None, "tool_calls": [{"id": "1", "function": {"name": "list_resources", "arguments": '{"resource_type":"interview"}'}}]}, {"content": "done"})
    assert AgentRuntime(model, tools).reply([], "context") == "done"
    assert tools.calls == [("list_resources", {"resource_type": "interview"})]


def test_unauthorized_resource_type_never_reaches_java_gateway():
    tools = FakeTools()
    model = FakeModel({"content": None, "tool_calls": [{"id": "1", "function": {"name": "get_resource", "arguments": json.dumps({"resource_type": "admin", "id": "secret"})}}]}, {"content": "权限错误已被阻止"})
    assert AgentRuntime(model, tools).reply([], "context") == "权限错误已被阻止"
    assert tools.calls == []


def test_training_task_request_is_delegated_once_for_one_model_call():
    tools = FakeTools({"status": "already_exists", "task": {"id": "task-1"}})
    model = FakeModel({"content": None, "tool_calls": [{"id": "1", "function": {"name": "create_training_task", "arguments": '{"title":"练习缓存","weakness_tag":"系统设计","action":"画图"}'}}]}, {"content": "已存在任务 task-1"})
    assert AgentRuntime(model, tools).reply([], "context") == "已存在任务 task-1"
    assert len(tools.calls) == 1


def test_model_failure_is_reported_as_agent_error():
    class BrokenModel:
        def complete(self, *_args):
            raise AgentError("model down")

    with pytest.raises(AgentError, match="model down"):
        AgentRuntime(BrokenModel(), FakeTools()).reply([], "context")
