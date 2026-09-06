#!/usr/bin/env python3
"""Configure one persistent signing key using the Codespaces owner's gh login.

Private key material is kept outside the repository and uploaded only as a
GitHub Actions secret. Existing secrets are never replaced automatically.
"""
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

REPO = 'tns80/gpt-web-native'
NAME = 'RELEASE_SIGNING_JSON'

def configure():
    if not shutil.which('gh') or not shutil.which('keytool'):
        raise RuntimeError('请在安装了 gh 和 JDK 的 Codespaces 终端执行。')
    listed = subprocess.run(['gh', 'secret', 'list', '--repo', REPO, '--json', 'name'],
                            text=True, capture_output=True)
    if listed.returncode:
        raise RuntimeError('当前 gh 登录无法读取仓库 Secrets；未生成或更换密钥。请先确认 Codespaces 对该仓库的 Secrets 管理权限。')
    if any(item['name'] == NAME for item in json.loads(listed.stdout)):
        print('固定签名 Secret 已存在，保持原密钥。')
        return

    directory = Path.home() / '.local/share/gpt-web-native-signing'
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    bundle_path = directory / 'signing-private.json'
    key_path = directory / 'release.p12'
    if not bundle_path.exists():
        if key_path.exists():
            raise RuntimeError('发现旧密钥但缺少密码备份，已停止以避免覆盖。')
        password = secrets.token_urlsafe(36)
        # Persist the password before key generation so interrupted setup is recoverable.
        password_path = directory / 'signing-password.txt'
        if password_path.exists():
            password = password_path.read_text()
        else:
            with password_path.open('x') as f:
                password_path.chmod(0o600)
                f.write(password)
        environment = os.environ.copy()
        environment['GPT_SIGNING_PASSWORD'] = password
        result = subprocess.run([
            'keytool', '-genkeypair', '-noprompt', '-keystore', str(key_path),
            '-storetype', 'PKCS12', '-alias', 'gptwebnative', '-keyalg', 'RSA',
            '-keysize', '3072', '-validity', '10000',
            '-dname', 'CN=GPT Web Native Personal',
            '-storepass:env', 'GPT_SIGNING_PASSWORD', '-keypass:env', 'GPT_SIGNING_PASSWORD'
        ], env=environment, capture_output=True)
        if result.returncode:
            raise RuntimeError('keytool 生成失败，私有目录已保留。未提交或推送代码。')
        key_path.chmod(0o600)
        bundle = {'password': password, 'keystore': base64.b64encode(key_path.read_bytes()).decode()}
        with bundle_path.open('x') as f:
            bundle_path.chmod(0o600)
            json.dump(bundle, f)

    uploaded = subprocess.run(['gh', 'secret', 'set', NAME, '--repo', REPO],
                              input=bundle_path.read_bytes(), capture_output=True)
    if uploaded.returncode:
        raise RuntimeError('Secret 上传失败，密钥已保留。可在 GitHub 仓库 Settings → Secrets and variables → Actions 新建 RELEASE_SIGNING_JSON，值使用 ' + str(bundle_path) + ' 的完整内容。不要把它上传到仓库或聊天。')
    print('固定签名已配置。请离线备份私有文件：' + str(bundle_path))
    print('首次切换密钥可能需要卸载旧 APK 一次；之后保持此 Secret 即可稳定签名。')

if __name__ == '__main__':
    try:
        configure()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
