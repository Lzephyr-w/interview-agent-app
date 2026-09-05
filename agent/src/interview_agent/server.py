import json
import hmac
import logging
import os
import time
from uuid import UUID
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict
from urllib.request import Request, urlopen

from langchain_openai import ChatOpenAI

from .agent import AgentError, AgentRuntime
from .simulation import SimulationError, generate


logger = logging.getLogger(__name__)


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
    simulation_model_factory = None

    def do_GET(self):
        if self.path == "/health":
            self._write(200, {"status": "ok"})
        else:
            self._write(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/v1/agent/simulations":
            self._simulation()
            return
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
            answer = runtime.reply(request["messages"], request.get("context", ""), user_id, str(request.get("conversationId") or ""))
            logger.info("agent_request user_id=%s conversation_id=%s model_calls=%s tool_calls=%s elapsed_ms=%s", user_id, request.get("conversationId", ""), runtime.metrics.get("model_calls", 0), runtime.metrics.get("tool_calls", 0), runtime.metrics.get("elapsed_ms", 0))
            self._write(200, {"content": answer})
        except (AgentError, ValueError) as exc:
            self._write(502, {"error": str(exc)})
        except Exception:
            logger.exception("Agent request failed")
            self._write(500, {"error": "Agent 服务内部错误，请稍后重试。"})

    def log_message(self, *_args):
        return

    def _simulation(self):
        started, request_id, operation = time.monotonic(), "", ""
        code = "OK"
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 400000:
                raise SimulationError("INVALID_REQUEST")
            self.connection.settimeout(5)
            payload = json.loads(self.rfile.read(size))
            if isinstance(payload, dict):
                try:
                    request_id = str(UUID(payload.get("requestId", "")))
                except (ValueError, TypeError, AttributeError):
                    pass
                from .simulation import OPERATIONS
                if payload.get("operation") in OPERATIONS:
                    operation = payload["operation"]
            if self.headers.get("Origin") or not self.internal_key or not hmac.compare_digest(self.headers.get("X-Agent-Key", ""), self.internal_key):
                raise SimulationError("UNAUTHORIZED")
            self._write(200, generate(payload, self.simulation_model_factory))
        except (ValueError, TypeError):
            code = "INVALID_REQUEST"
            self._write(400, SimulationError(code).envelope(request_id))
        except SimulationError as error:
            code = error.code
            status = 401 if code == "UNAUTHORIZED" else 400 if code == "INVALID_REQUEST" else 504 if code == "MODEL_TIMEOUT" else 502
            self._write(status, error.envelope(request_id))
        except Exception:
            code = "INTERNAL_ERROR"
            self._write(500, SimulationError(code).envelope(request_id))
        finally:
            logger.info("simulation requestId=%s operation=%s code=%s elapsed_ms=%s", request_id, operation, code, round((time.monotonic()-started)*1000))

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
    Handler.simulation_model_factory = staticmethod(lambda remaining: model_from_env(timeout=remaining, max_retries=0))
    Handler.runtime_factory = staticmethod(lambda user_id: AgentRuntime(
        model_from_env(),
        JavaToolClient(os.getenv("JAVA_AGENT_TOOL_URL", "http://localhost:8080/internal/agent/tools"), key, user_id),
    ))
    server = ThreadingHTTPServer((os.getenv("AGENT_HOST", "127.0.0.1"), int(os.getenv("AGENT_PORT", "8090"))), Handler)
    print("Interview Agent listening on http://%s:%s" % server.server_address, flush=True)
    server.serve_forever()


def model_from_env(timeout=60, max_retries=1):
    url, key, model = os.getenv("AGENT_MODEL_API_URL", ""), os.getenv("AGENT_MODEL_API_KEY", ""), os.getenv("AGENT_MODEL", "")
    if not all((url, key, model)):
        raise AgentError("AI Agent 服务尚未配置，请联系管理员后重试。")
    return ChatOpenAI(
        model=model, api_key=key, base_url=url.removesuffix("/chat/completions"),
        temperature=0.2, timeout=timeout, max_retries=max_retries, max_completion_tokens=2048,
        use_responses_api=False,
    )


if __name__ == "__main__":
    main()
