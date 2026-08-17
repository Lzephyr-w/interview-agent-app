import json
from typing import Any, Dict, List, Optional


TOOL_DEFINITIONS = [
    {
        "type": "function",
        "function": {
            "name": "list_resources",
            "description": "列出当前用户的一类业务资料。先列出再按 ID 读取详情。",
            "parameters": {
                "type": "object",
                "properties": {
                    "resource_type": {
                        "type": "string",
                        "enum": [
                            "interview_package", "resume", "resume_file",
                            "job_description", "evidence_card", "interview",
                            "weakness", "training_task",
                        ],
                    }
                },
                "required": ["resource_type"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_resource",
            "description": "读取当前用户指定业务资料的完整详情。",
            "parameters": {
                "type": "object",
                "properties": {
                    "resource_type": {"type": "string"},
                    "id": {"type": "string"},
                },
                "required": ["resource_type", "id"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "create_training_task",
            "description": "为当前用户创建训练任务。只在有助于完成用户明确请求时调用。",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "weakness_tag": {"type": "string"},
                    "action": {"type": "string"},
                    "source_interview_id": {"type": "string"},
                    "source_review_report_id": {"type": "string"},
                },
                "required": ["title", "weakness_tag", "action"],
                "additionalProperties": False,
            },
        },
    },
]

ALLOWED_RESOURCE_TYPES = {
    "interview_package", "resume", "resume_file", "job_description",
    "evidence_card", "interview", "weakness", "training_task",
}
ALLOWED_TOOLS = {"list_resources", "get_resource", "create_training_task"}


class AgentError(RuntimeError):
    pass


class AgentRuntime:
    """Prompt, state, tool loop and failure handling for one authorized request."""

    def __init__(self, model: Any, tools: Any, max_steps: int = 8):
        self.model = model
        self.tools = tools
        self.max_steps = max_steps

    def reply(self, messages: List[Dict[str, Any]], context: str) -> str:
        state = list(messages)
        if not state or state[0].get("role") != "system":
            state.insert(0, {"role": "system", "content": self._system_prompt(context)})
        for _ in range(self.max_steps):
            response = self.model.complete(state, TOOL_DEFINITIONS)
            calls = response.get("tool_calls") or []
            if not calls:
                answer = str(response.get("content") or "").strip()
                if not answer:
                    raise AgentError("AI Agent 未生成最终回复，请重试。")
                return answer
            state.append(self._assistant_tool_message(response))
            for call in calls:
                call_id = str(call.get("id") or "").strip()
                function = call.get("function") or {}
                name = str(function.get("name") or "").strip()
                if not call_id or name not in ALLOWED_TOOLS:
                    raise AgentError("AI Agent 工具调用格式无效，请重试。")
                arguments = self._arguments(function.get("arguments"))
                try:
                    result = self._execute(name, arguments)
                except (AgentError, ValueError) as exc:
                    result = {"error": str(exc)}
                state.append({
                    "role": "tool", "tool_call_id": call_id,
                    "content": json.dumps(result, ensure_ascii=False, default=str),
                })
        raise AgentError("AI Agent 执行步骤过多，请缩小任务范围后重试。")

    def _execute(self, name: str, arguments: Dict[str, Any]) -> Any:
        resource_type = arguments.get("resource_type")
        if name in {"list_resources", "get_resource"} and resource_type not in ALLOWED_RESOURCE_TYPES:
            raise ValueError("资料类型无效。")
        if name == "list_resources":
            return self.tools.call(name, arguments)
        if name == "get_resource":
            if not str(arguments.get("id") or "").strip():
                raise ValueError("工具参数 id 不能为空。")
            return self.tools.call(name, arguments)
        for key in ("title", "weakness_tag", "action"):
            if not str(arguments.get(key) or "").strip():
                raise ValueError("工具参数 %s 不能为空。" % key)
        return self.tools.call(name, arguments)

    @staticmethod
    def _arguments(value: Any) -> Dict[str, Any]:
        if isinstance(value, dict):
            return value
        try:
            parsed = json.loads(value or "{}")
        except (TypeError, ValueError):
            raise AgentError("AI Agent 工具参数格式无效，请重试。")
        if not isinstance(parsed, dict):
            raise AgentError("AI Agent 工具参数格式无效，请重试。")
        return parsed

    @staticmethod
    def _assistant_tool_message(response: Dict[str, Any]) -> Dict[str, Any]:
        result = {"role": "assistant", "tool_calls": response.get("tool_calls", [])}
        if response.get("content") is not None:
            result["content"] = response["content"]
        return result

    @staticmethod
    def _system_prompt(context: str) -> str:
        return (
            "你是可调用业务工具的面试准备 Agent。只能读取当前用户授权的资料；"
            "不得编造经历、指标、隐私信息、能力评级、通过概率或招聘结论。"
            "资料不足必须明确写‘待补充’。创建训练任务后要说明创建结果。\n\n"
            "【启动资料】\n" + (context or "待补充")
        )
