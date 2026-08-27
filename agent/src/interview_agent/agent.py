import json
import re
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Literal

from langchain.agents import create_agent
from langchain.tools import ToolRuntime, tool
from langchain_core.messages import AIMessage, ToolMessage
from langgraph.errors import GraphRecursionError


ResourceType = Literal[
    "interview_package", "resume", "resume_file", "job_description",
    "evidence_card", "interview", "weakness", "training_task",
]


class AgentError(RuntimeError):
    pass


@dataclass
class AgentContext:
    user_id: str
    conversation_id: str
    tools: Any
    allow_training_task: bool


def _tool_result(runtime: ToolRuntime[AgentContext], name: str, arguments: Dict[str, Any]) -> str:
    try:
        return json.dumps(runtime.context.tools.call(name, arguments), ensure_ascii=False, default=str)
    except (AgentError, ValueError) as exc:
        return json.dumps({"error": str(exc)}, ensure_ascii=False)


@tool
def list_resources(resource_type: ResourceType, runtime: ToolRuntime[AgentContext]) -> str:
    """列出当前用户的一类业务资料。先列出再按 ID 读取详情。"""
    return _tool_result(runtime, "list_resources", {"resource_type": resource_type})


@tool
def get_resource(resource_type: ResourceType, id: str, runtime: ToolRuntime[AgentContext]) -> str:
    """读取当前用户指定业务资料的完整详情。"""
    if not id.strip():
        return json.dumps({"error": "工具参数 id 不能为空。"}, ensure_ascii=False)
    return _tool_result(runtime, "get_resource", {"resource_type": resource_type, "id": id})


@tool
def create_training_task(
    title: str,
    weakness_tag: str,
    action: str,
    runtime: ToolRuntime[AgentContext],
    source_interview_id: str = "",
    source_review_report_id: str = "",
) -> str:
    """为当前用户创建训练任务。仅在用户明确要求创建训练任务时使用。"""
    if not runtime.context.allow_training_task:
        return json.dumps({"error": "用户未明确要求创建训练任务。"}, ensure_ascii=False)
    required = {"title": title, "weakness_tag": weakness_tag, "action": action}
    if any(not value.strip() for value in required.values()):
        return json.dumps({"error": "训练任务的标题、弱项和行动不能为空。"}, ensure_ascii=False)
    arguments = required | {
        "source_interview_id": source_interview_id,
        "source_review_report_id": source_review_report_id,
    }
    return _tool_result(runtime, "create_training_task", {key: value for key, value in arguments.items() if value})


TOOLS = [list_resources, get_resource, create_training_task]
# ponytail: conservative request grammar; add a Java confirmation field if intent needs richer language.
TRAINING_TASK_REQUEST = re.compile(
    r"(?:^|[。！？!?；;]\s*)(?:(?:请|麻烦)(?:帮我|为我|替我|给我)?|帮我|为我|替我|给我|我要|我想|please|can you|i (?:want|need) to)?\s*"
    r"(?:创建|新建|添加|安排|生成|create|add|new)\s*(?:一个|一项|a |an )?\s*(?:训练任务|练习任务|training\s*task)",
    re.I,
)


class AgentRuntime:
    """LangChain single-agent runtime; Java remains the source of truth for access and history."""

    def __init__(self, model: Any, tools: Any, max_steps: int = 8):
        self.agent = create_agent(model, TOOLS, context_schema=AgentContext, system_prompt=self._system_prompt())
        self.tools = tools
        self.max_steps = max_steps
        self.metrics: Dict[str, int | float] = {}

    def reply(self, messages: List[Dict[str, Any]], context: str, user_id: str = "", conversation_id: str = "") -> str:
        started = time.perf_counter()
        try:
            state = self.agent.invoke(
                {"messages": self._messages(messages, context)},
                context=AgentContext(user_id, conversation_id, self.tools, self._allows_training_task(messages)),
                # Each tool step contains a model and tool node; leave room for the final model response.
                config={"recursion_limit": self.max_steps * 2 + 2},
            )
        except GraphRecursionError as exc:
            raise AgentError("AI Agent 执行步骤过多，请缩小任务范围后重试。") from exc
        except AgentError:
            raise
        except Exception as exc:
            raise AgentError("AI Agent 超时或请求失败，请稍后重试。") from exc
        finally:
            self.metrics["elapsed_ms"] = round((time.perf_counter() - started) * 1000)

        result_messages = state["messages"]
        self.metrics.update({
            "model_calls": sum(isinstance(message, AIMessage) for message in result_messages),
            "tool_calls": sum(isinstance(message, ToolMessage) for message in result_messages),
        })
        answer = str(result_messages[-1].content or "").strip()
        if not answer:
            raise AgentError("AI Agent 未生成最终回复，请重试。")
        return answer

    @staticmethod
    def _messages(messages: List[Dict[str, Any]], context: str) -> List[Dict[str, Any]]:
        # Java normally supplies the complete startup prompt as the first system message.
        if messages and messages[0].get("role") == "system":
            return messages
        return [{"role": "system", "content": "【启动资料】\n" + (context or "待补充")}, *messages]

    @staticmethod
    def _allows_training_task(messages: List[Dict[str, Any]]) -> bool:
        latest_user_message = next((message for message in reversed(messages) if message.get("role") == "user"), {})
        return bool(TRAINING_TASK_REQUEST.search(str(latest_user_message.get("content") or "")))

    @staticmethod
    def _system_prompt() -> str:
        return (
            "你是可调用业务工具的面试准备 Agent。只能读取当前用户授权的资料；"
            "不得编造经历、指标、隐私信息、能力评级、通过概率或招聘结论。"
            "资料不足必须明确写‘待补充’。仅当用户明确要求创建训练任务时才调用写入工具；"
            "创建后要说明创建结果。"
        )
