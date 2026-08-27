import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult

from interview_agent.agent import AgentError, AgentRuntime


class FakeTools:
    def __init__(self, result=None):
        self.calls = []
        self.result = result or {"status": "created"}

    def call(self, name, arguments):
        self.calls.append((name, arguments))
        return self.result


class SequenceModel(BaseChatModel):
    responses: list[AIMessage]
    index: int = 0

    @property
    def _llm_type(self):
        return "test"

    def bind_tools(self, _tools, **_kwargs):
        return self

    def _generate(self, _messages, stop=None, run_manager=None, **_kwargs):
        response = self.responses[self.index]
        self.index += 1
        return ChatResult(generations=[ChatGeneration(message=response)])


def tool_call(name, args):
    return AIMessage(content="", tool_calls=[{"id": "call-1", "name": name, "args": args}])


def test_list_resources_uses_langchain_tool_and_authorized_gateway():
    tools = FakeTools({"items": [{"id": "owned"}]})
    model = SequenceModel(responses=[tool_call("list_resources", {"resource_type": "interview"}), AIMessage(content="done")])
    runtime = AgentRuntime(model, tools)

    assert runtime.reply([{"role": "user", "content": "查看我的面试"}], "context", "user-1", "conversation-1") == "done"
    assert tools.calls == [("list_resources", {"resource_type": "interview"})]
    assert runtime.metrics["model_calls"] == 2
    assert runtime.metrics["tool_calls"] == 1


def test_invalid_resource_type_never_reaches_java_gateway():
    tools = FakeTools()
    model = SequenceModel(responses=[tool_call("get_resource", {"resource_type": "admin", "id": "secret"}), AIMessage(content="权限错误已被阻止")])

    assert AgentRuntime(model, tools).reply([{"role": "user", "content": "读取资料"}], "context") == "权限错误已被阻止"
    assert tools.calls == []


def test_training_task_requires_explicit_user_request():
    tools = FakeTools({"status": "already_exists", "task": {"id": "task-1"}})
    model = SequenceModel(responses=[tool_call("create_training_task", {"title": "练习缓存", "weakness_tag": "系统设计", "action": "画图"}), AIMessage(content="已存在任务 task-1")])

    assert AgentRuntime(model, tools).reply([{"role": "user", "content": "请创建训练任务"}], "context") == "已存在任务 task-1"
    assert len(tools.calls) == 1


def test_unrequested_training_task_never_reaches_java_gateway():
    tools = FakeTools()
    model = SequenceModel(responses=[tool_call("create_training_task", {"title": "练习缓存", "weakness_tag": "系统设计", "action": "画图"}), AIMessage(content="未创建")])

    assert AgentRuntime(model, tools).reply([{"role": "user", "content": "分析我的弱项"}], "context") == "未创建"
    assert tools.calls == []


def test_declined_training_task_never_reaches_java_gateway():
    tools = FakeTools()
    model = SequenceModel(responses=[tool_call("create_training_task", {"title": "练习缓存", "weakness_tag": "系统设计", "action": "画图"}), AIMessage(content="未创建")])

    assert AgentRuntime(model, tools).reply([{"role": "user", "content": "不要创建训练任务，只分析我的弱项"}], "context") == "未创建"
    assert tools.calls == []


def test_previous_write_intent_does_not_authorize_a_later_request():
    messages = [
        {"role": "user", "content": "请创建训练任务"},
        {"role": "assistant", "content": "已创建"},
        {"role": "user", "content": "现在分析我的弱项"},
    ]

    assert AgentRuntime._allows_training_task(messages) is False


def test_java_system_message_is_not_duplicated():
    messages = [{"role": "system", "content": "Java 已提供启动资料"}, {"role": "user", "content": "你好"}]

    assert AgentRuntime._messages(messages, "Python 备用资料") == messages


def test_model_failure_is_reported_as_stable_agent_error():
    class BrokenModel(SequenceModel):
        def _generate(self, *_args, **_kwargs):
            raise RuntimeError("model down")

    with pytest.raises(AgentError, match="请求失败"):
        AgentRuntime(BrokenModel(responses=[]), FakeTools()).reply([], "context")
