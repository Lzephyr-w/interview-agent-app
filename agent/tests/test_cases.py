import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult

from interview_agent.agent import AgentRuntime


# Scripted LangChain responses make tool selection and output constraints deterministic.
REGRESSION_CASES = [
    ("list-interviews", "查看我的面试", "list_resources", {"resource_type": "interview"}, {"items": []}, "资料待补充", False),
    ("get-resume", "读取我的简历详情", "get_resource", {"resource_type": "resume", "id": "resume-1"}, {"id": "resume-1"}, "简历资料待补充", False),
    ("list-weaknesses", "列出我的薄弱点", "list_resources", {"resource_type": "weakness"}, {"items": []}, "薄弱点待补充", False),
    ("get-evidence-card", "读取项目证据卡", "get_resource", {"resource_type": "evidence_card", "id": "card-1"}, {"id": "card-1"}, "证据卡待补充", False),
    ("missing-material", "资料不足时告诉我待补充", None, None, None, "资料待补充", False),
    ("no-fabrication", "根据现有资料分析，不要编造指标", None, None, None, "不得编造指标", False),
    ("create-training-task", "请创建训练任务", "create_training_task", {"title": "练习缓存", "weakness_tag": "系统设计", "action": "画图"}, {"status": "created"}, "训练任务创建成功", True),
    ("add-training-task", "帮我添加一个训练任务", "create_training_task", {"title": "练习缓存", "weakness_tag": "系统设计", "action": "画图"}, {"status": "created"}, "训练任务创建成功", True),
    ("analysis-does-not-write", "分析我的弱项并给建议", "list_resources", {"resource_type": "weakness"}, {"items": []}, "建议先补充资料", False),
    ("tool-error", "资料查询失败时说明原因", "list_resources", {"resource_type": "interview"}, {"error": "查询失败"}, "查询失败，请重试", False),
]


class FakeTools:
    def __init__(self, result):
        self.calls = []
        self.result = result

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


@pytest.mark.parametrize("_name,message,expected_tool,arguments,tool_result,answer,allows_write", REGRESSION_CASES)
def test_regression_cases(_name, message, expected_tool, arguments, tool_result, answer, allows_write):
    responses = [AIMessage(content=answer)] if expected_tool is None else [
        AIMessage(content="", tool_calls=[{"id": "call-1", "name": expected_tool, "args": arguments}]),
        AIMessage(content=answer),
    ]
    tools = FakeTools(tool_result)

    assert AgentRuntime(SequenceModel(responses=responses), tools).reply([{"role": "user", "content": message}], "待补充") == answer
    assert AgentRuntime._allows_training_task([{"role": "user", "content": message}]) is allows_write
    assert tools.calls == ([] if expected_tool is None else [(expected_tool, arguments)])
