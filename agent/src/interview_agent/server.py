import json
import hmac
import logging
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict
from urllib.request import Request, urlopen

from .agent import AgentError, AgentRuntime


logger = logging.getLogger(__name__)


class ModelClient:
    def __init__(self, url: str, key: str, model: str):
        self.url, self.key, self.model = url, key, model

    def complete(self, messages, tools):
        if not all((self.url, self.key, self.model)):
            raise AgentError("AI Agent 服务尚未配置，请联系管理员后重试。")
        body = json.dumps({
            "model": self.model, "temperature": 0.2, "messages": messages,
            "tools": tools, "tool_choice": "auto",
        }).encode("utf-8")
        request = Request(self.url, data=body, headers={
            "Authorization": "Bearer " + self.key,
            "Content-Type": "application/json",
        })
        try:
            with urlopen(request, timeout=60) as response:
                payload = json.loads(response.read().decode("utf-8"))
            message = payload.get("choices", [{}])[0].get("message") or {}
            if not message.get("content") and not message.get("tool_calls"):
                raise AgentError("AI Agent 返回格式无效，请重试。")
            return message
        except AgentError:
            raise
        except Exception as exc:
            raise AgentError("AI Agent 超时或请求失败，请稍后重试。") from exc


class JavaToolClient:
    def __init__(self, url: str, key: str, user_id: str):
        self.url, self.key, self.user_id = url, key, user_id

    def call(self, name: str, arguments: Dict[str, Any]):
        body = json.dumps({"userId": self.user_id, "name": name, "arguments": arguments}).encode("utf-8")
        request = Request(self.url, data=body, headers={
            "X-Agent-Key": self.key, "Content-Type": "application/json",
        })
        try:
            with urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as exc:
            raise AgentError("Java Agent 工具服务暂时不可用，请稍后重试。") from exc


class Handler(BaseHTTPRequestHandler):
    runtime_factory = None
    internal_key = ""

    def do_GET(self):
        if self.path == "/health":
            self._write(200, {"status": "ok"})
        else:
            self._write(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/v1/agent/reply":
            self._write(404, {"error": "not found"})
            return
        if not self.internal_key or not hmac.compare_digest(self.headers.get("X-Agent-Key", ""), self.internal_key):
            self._write(401, {"error": "unauthorized"})
            return
        try:
            request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
            user_id = str(request.get("userId") or "").strip()
            if not user_id or not isinstance(request.get("messages"), list):
                raise ValueError("Agent 请求格式无效。")
            runtime = self.runtime_factory(user_id)
            answer = runtime.reply(request["messages"], request.get("context", ""))
            self._write(200, {"content": answer})
        except (AgentError, ValueError) as exc:
            self._write(502, {"error": str(exc)})
        except Exception:
            logger.exception("Agent request failed")
            self._write(500, {"error": "Agent 服务内部错误，请稍后重试。"})

    def log_message(self, *_args):
        return

    def _write(self, status: int, payload: Dict[str, Any]):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def load_env_file(path: str = ".env.local"):
    try:
        with open(path, "r", encoding="utf-8") as file:
            for line in file:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                name, value = line.split("=", 1)
                os.environ.setdefault(name.strip(), value.strip().strip('"').strip("'"))
    except FileNotFoundError:
        pass


def main():
    load_env_file()
    key = os.getenv("AGENT_INTERNAL_KEY", "")
    Handler.internal_key = key
    Handler.runtime_factory = staticmethod(lambda user_id: AgentRuntime(
        ModelClient(os.getenv("AGENT_MODEL_API_URL", ""), os.getenv("AGENT_MODEL_API_KEY", ""), os.getenv("AGENT_MODEL", "")),
        JavaToolClient(os.getenv("JAVA_AGENT_TOOL_URL", "http://localhost:8080/internal/agent/tools"), key, user_id),
    ))
    server = ThreadingHTTPServer((os.getenv("AGENT_HOST", "127.0.0.1"), int(os.getenv("AGENT_PORT", "8090"))), Handler)
    print("Interview Agent listening on http://%s:%s" % server.server_address, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
