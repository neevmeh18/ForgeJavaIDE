import json
import os
import pathlib
import shutil
import tempfile
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = pathlib.Path(os.environ["BACKUP_WORKER_TOKEN_FILE"]).read_text().strip()
WORKSPACE_ROOT = pathlib.Path("/workspace").resolve()
BACKUP_ROOT = pathlib.Path("/backups").resolve()


def safe_component(value):
    if not value or any(ch not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for ch in value):
        raise ValueError()
    return value


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/backup" or self.headers.get("Authorization") != "Bearer " + TOKEN:
            self.send_response(404)
            self.end_headers()
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 8192:
                raise ValueError()
            data = json.loads(self.rfile.read(length))
            user = safe_component(str(data["user"]))
            workspace = safe_component(str(data["workspace"]))
            source = pathlib.Path(str(data["source"])).resolve(strict=True)
            if source != WORKSPACE_ROOT and WORKSPACE_ROOT not in source.parents:
                raise ValueError()
            destination = str(data["destination"])
            if not destination or "\x00" in destination or len(destination) > 240:
                raise ValueError()
            base = BACKUP_ROOT / user / workspace
            base.mkdir(parents=True, exist_ok=True)
            target = base / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix="forge-backup-", suffix=".zip", dir="/tmp")
            os.close(fd)
            try:
                with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as archive:
                    for root, dirs, files in os.walk(source, followlinks=False):
                        dirs[:] = [d for d in dirs if not pathlib.Path(root, d).is_symlink()]
                        for name in files:
                            item = pathlib.Path(root, name)
                            if item.is_symlink():
                                continue
                            archive.write(item, item.relative_to(source))
                shutil.move(temporary, target)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
            payload = json.dumps({"status": "Completed"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except Exception:
            self.send_response(400)
            self.end_headers()

    def log_message(self, format, *args):
        return


ThreadingHTTPServer(("0.0.0.0", 8081), Handler).serve_forever()
